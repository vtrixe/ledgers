package com.example.ledgers.posting;

import com.example.ledgers.accounts.Account;
import com.example.ledgers.accounts.AccountService;
import com.example.ledgers.posting.dto.AssetBalance;
import com.example.ledgers.posting.dto.BalanceResponse;
import com.example.ledgers.posting.dto.StatementResponse;
import com.example.ledgers.shared.ApiException;
import com.example.ledgers.tenancy.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BalanceServiceImpl implements BalanceService {

    private static final int MAX_STATEMENT_PAGE = 500;

    private static final Pattern ROLLUP_PREFIX =
            Pattern.compile("(assets|liabilities|equity|revenue|expenses)(:[a-z0-9_]+)*:?");

    private final AccountService accountService;
    private final PostingRepository postingRepository;
    private final AssetRepository assetRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public BalanceResponse getAccountBalance(TenantPrincipal principal, String accountRef, OffsetDateTime asOf, OffsetDateTime knownAt) {
        Account account = findAccount(principal, accountRef);
        UUID accountId = account.getId();
        Instant asOfInstant = orNow(asOf);
        Instant knownAtInstant = orNow(knownAt);

        Map<String, Long> rawBalances = rawBalancesOf(principal.tenantId(), accountId, asOfInstant, knownAtInstant);

        return BalanceResponse.builder()
                .accountId(account.getId())
                .path(account.getPath())
                .type(account.getType())
                .asOf(asOfInstant)
                .knownAt(knownAtInstant)
                .balances(toAssetBalances(rawBalances, account.normalSign()))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResponse getRollupBalance(TenantPrincipal principal, String prefix, OffsetDateTime asOf, OffsetDateTime knownAt) {
        if (prefix == null || !ROLLUP_PREFIX.matcher(prefix).matches()) {
            throw ApiException.badRequest("ERR_INVALID_PREFIX",
                    "prefix must start with an account type, e.g. revenue:fees: (types cannot be mixed in one total)");
        }
        String type = prefix.split(":")[0];
        Instant asOfInstant = orNow(asOf);
        Instant knownAtInstant = orNow(knownAt);

        Map<String, Long> rawBalances = rawBalancesUnder(principal.tenantId(), prefix, asOfInstant, knownAtInstant);

        return BalanceResponse.builder()
                .prefix(prefix)
                .type(type)
                .asOf(asOfInstant)
                .knownAt(knownAtInstant)
                .balances(toAssetBalances(rawBalances, Account.normalSignOf(type)))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public StatementResponse getStatement(TenantPrincipal principal, String accountRef, String cursor, int limit) {
        Account account = findAccount(principal, accountRef);
        UUID accountId = account.getId();
        int pageSize = Math.clamp(limit, 1, MAX_STATEMENT_PAGE);

        Limit fetchLimit = Limit.of(pageSize + 1);
        List<Posting> postings;
        if (cursor == null || cursor.isBlank()) {
            postings = postingRepository.findStatementFirstPage(principal.tenantId(), accountId, fetchLimit);
        } else {
            Cursor after = decodeCursor(cursor);
            postings = postingRepository.findStatementPageBefore(
                    principal.tenantId(), accountId, after.seq(), after.postingId(), fetchLimit);
        }

        boolean hasMore = postings.size() > pageSize;
        List<Posting> page = hasMore ? postings.subList(0, pageSize) : postings;

        Map<String, Short> scales = assetScales();
        List<StatementResponse.Entry> entries = new ArrayList<>(page.size());
        for (Posting posting : page) {
            LedgerTransaction t = posting.getTransaction();
            entries.add(new StatementResponse.Entry(
                    posting.getId(),
                    t.getId(),
                    t.getSeq(),
                    t.getEffectiveAt(),
                    t.getRecordedAt(),
                    posting.getAsset(),
                    BigDecimal.valueOf(posting.getAmount() * account.normalSign(), scales.get(posting.getAsset())).toPlainString()));
        }

        StatementResponse response = new StatementResponse();
        response.setAccountId(accountId);
        response.setPath(account.getPath());
        response.setEntries(entries);
        if (hasMore) {
            Posting last = page.getLast();
            response.setNextCursor(encodeCursor(last.getTransaction().getSeq(), last.getId()));
        }
        return response;
    }

    private static String encodeCursor(long seq, UUID postingId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((seq + ":" + postingId).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int i = raw.indexOf(':');
            return new Cursor(Long.parseLong(raw.substring(0, i)), UUID.fromString(raw.substring(i + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("ERR_INVALID_CURSOR", "cursor must be the nextCursor of a previous page");
        }
    }

    private record Cursor(long seq, UUID postingId) {
    }

    private Map<String, Long> rawBalancesOf(UUID tenantId, UUID accountId, Instant asOf, Instant knownAt) {
        return toMap(postingRepository.sumByAsset(tenantId, accountId, asOf, knownAt));
    }

    private Map<String, Long> rawBalancesUnder(UUID tenantId, String prefix, Instant asOf, Instant knownAt) {
        Set<UUID> accountIds = accountService.findAccountIdsUnder(tenantId, prefix);
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return toMap(postingRepository.sumByAssetForAccounts(tenantId, accountIds, asOf, knownAt));
    }

    private Map<String, Long> toMap(List<AssetTotal> totals) {
        Map<String, Long> rawBalances = new HashMap<>();
        for (AssetTotal total : totals) {
            rawBalances.put(total.getAsset(), total.getTotal());
        }
        return rawBalances;
    }

    private Account findAccount(TenantPrincipal principal, String accountRef) {
        UUID accountId = parseUuid(accountRef);
        if (accountId == null) {
            accountId = accountService.resolvePaths(principal.tenantId(), Set.of(accountRef)).get(accountRef);
        }
        return accountService.getAccount(principal, accountId);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<AssetBalance> toAssetBalances(Map<String, Long> rawBalances, int normalSign) {
        Map<String, Short> scales = assetScales();
        List<AssetBalance> balances = new ArrayList<>();
        for (String asset : new TreeSet<>(rawBalances.keySet())) {
            long raw = rawBalances.get(asset);
            String display = BigDecimal.valueOf(raw * normalSign, scales.get(asset)).toPlainString();
            balances.add(new AssetBalance(asset, display, raw));
        }
        return balances;
    }

    private Instant orNow(OffsetDateTime time) {
        Instant instant = time == null ? clock.instant() : time.toInstant();
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private Map<String, Short> assetScales() {
        return assetRepository.findAll().stream().collect(Collectors.toMap(Asset::getCode, Asset::getScale));
    }
}
