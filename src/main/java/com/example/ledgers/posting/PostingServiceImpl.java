package com.example.ledgers.posting;

import com.example.ledgers.accounts.Account;
import com.example.ledgers.accounts.AccountService;
import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingLineRequest;
import com.example.ledgers.posting.dto.PostingResult;
import com.example.ledgers.posting.dto.TransactionResponse;
import com.example.ledgers.shared.ApiException;
import com.example.ledgers.shared.LedgerSession;
import com.example.ledgers.tenancy.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Posts header + postings in one database transaction. The ledger invariants (balance per asset, posting count,
 * period lock, immutability, gap-free seq) are enforced by Postgres at INSERT/COMMIT; this service translates the
 * request, resolves accounts and handles idempotency.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostingServiceImpl implements PostingService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final PostingRepository postingRepository;
    private final AssetRepository assetRepository;
    private final AccountService accountService;
    private final LedgerSession ledgerSession;
    private final TransactionTemplate  transactionTemplate;

    @Override
    public PostingResult postTransaction(TenantPrincipal principal, String idempotencyKey, PostTransactionRequest request) {
        try {
            return transactionTemplate.execute(status -> attemptPostTransaction(principal, idempotencyKey, request));
        } catch (DataIntegrityViolationException e) {
            if (!isIdempotencyRace(e)) {
                throw e;
            }
            return transactionTemplate.execute(status -> attemptPostTransaction(principal, idempotencyKey, request));
        }
    }

    private static boolean isIdempotencyRace(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && "transactions_tenant_id_idempotency_key_key".equals(violation.getConstraintName());
    }


    private PostingResult attemptPostTransaction(TenantPrincipal principal, String idempotencyKey, PostTransactionRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw ApiException.badRequest("ERR_INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1-255 characters");
        }
        UUID tenantId = principal.tenantId();
        ledgerSession.bindActor(principal.actor());


        Instant effectiveAt = request.getEffectiveAt() == null ? null
                : request.getEffectiveAt().toInstant().truncatedTo(ChronoUnit.MICROS);
        Map<String, Short> scales = assetScales();

        List<PostingLineRequest> pLRs = request.getPostings();
        List<RequestHasher.CanonicalLeg> canonicalLegs = new ArrayList<>();

        for (PostingLineRequest pLR : pLRs) {
            RequestHasher.CanonicalLeg canonicalLeg = new RequestHasher.CanonicalLeg(pLR.getAccount(),pLR.getAsset(),toMinorUnits(pLR, scales));
            canonicalLegs.add(canonicalLeg);
        }

        byte[] bytes =  RequestHasher.hash(effectiveAt,request.getReversesTransactionId(),request.getMetadata(),canonicalLegs);

        Optional<LedgerTransaction> ltr =
                ledgerTransactionRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);

        if (ltr.isPresent()) {
            if (Arrays.equals(ltr.get().getRequestHash(), bytes)) {
                return new PostingResult(toResponse(ltr.get()),true);
            }
            else{
                throw ApiException.unprocessable("ERR_IDEMPOTENCY_MISMATCH",
                        "Idempotency-Key " + idempotencyKey + " was already used with a different request");            }
        }

        Set<String> paths = new HashSet<>();

        for (PostingLineRequest pLR : pLRs) {
            paths.add(pLR.getAccount());
        }

        Map<String,UUID> accountIDPathMap = accountService.resolvePaths(tenantId,paths);

        Map<UUID, Map<String, Long>> accountAssetAmounts = new HashMap<>();

        for (RequestHasher.CanonicalLeg leg : canonicalLegs) {
            UUID accountId = accountIDPathMap.get(leg.account());
            String asset = leg.asset();
            long amount = leg.amount();

            accountAssetAmounts
                    .computeIfAbsent(accountId, k -> new HashMap<>())
                    .merge(asset, amount, Long::sum);
        }

        checkOverdrafts(tenantId, accountAssetAmounts, scales);

        return save(tenantId,idempotencyKey,effectiveAt,request,scales,accountIDPathMap,bytes);


    }

    private void checkOverdrafts(UUID tenantId, Map<UUID, Map<String, Long>> deltas, Map<String, Short> scales) {

        Map<UUID, Account> accounts = loadAccounts(tenantId, deltas.keySet());

        List<BalanceCheck> checks = findBalancesToCheck(accounts, deltas);
        if (checks.isEmpty()) {
            return;
        }

        Set<UUID> accountIdsToLock = new HashSet<>();
        for (BalanceCheck check : checks) {
            accountIdsToLock.add(check.account().getId());
        }
        accountService.lockAccounts(tenantId, accountIdsToLock);

        for (BalanceCheck check : checks) {
            long currentBalance = postingRepository.sumAmount(check.account().getId(), check.asset());
            long balanceAfterPosting = currentBalance + check.delta();
            long displayBalanceAfterPosting = balanceAfterPosting * check.account().normalSign();

            if (displayBalanceAfterPosting < 0) {
                throw insufficientFunds(check, currentBalance, scales);
            }
        }
    }

    private Map<UUID, Account> loadAccounts(UUID tenantId, Set<UUID> accountIds) {
        Map<UUID, Account> accounts = new HashMap<>();
        for (Account account : accountService.getAccounts(tenantId, accountIds)) {
            accounts.put(account.getId(), account);
        }
        return accounts;
    }

    private List<BalanceCheck> findBalancesToCheck(Map<UUID, Account> accounts, Map<UUID, Map<String, Long>> deltas) {
        List<BalanceCheck> checks = new ArrayList<>();

        for (Map.Entry<UUID, Map<String, Long>> accountEntry : deltas.entrySet()) {
            Account account = accounts.get(accountEntry.getKey());
            if (account.isAllowNegative()) {
                continue;
            }

            for (Map.Entry<String, Long> assetEntry : accountEntry.getValue().entrySet()) {
                String asset = assetEntry.getKey();
                long delta = assetEntry.getValue();
                if (isMoneyGoingOut(account, delta)) {
                    checks.add(new BalanceCheck(account, asset, delta));
                }
            }
        }
        return checks;
    }

    private boolean isMoneyGoingOut(Account account, long delta) {
        return delta * account.normalSign() < 0;
    }

    private ApiException insufficientFunds(BalanceCheck check, long currentBalance, Map<String, Short> scales) {
        short scale = scales.get(check.asset());
        int sign = check.account().normalSign();
        String available = BigDecimal.valueOf(currentBalance * sign, scale).toPlainString();
        String requested = BigDecimal.valueOf(-check.delta() * sign, scale).toPlainString();

        return ApiException.unprocessable("ERR_INSUFFICIENT_FUNDS",
                "Account " + check.account().getPath() + " has " + available + " " + check.asset()
                        + " available; this posting takes " + requested);
    }

    private record BalanceCheck(Account account, String asset, long delta) {
    }

    /** Builds header + postings and saves them; the database checks balance and posting count at COMMIT. */
    private PostingResult save(UUID tenantId, String idempotencyKey, Instant effectiveAt, PostTransactionRequest request,
                               Map<String, Short> scales, Map<String, UUID> accountIds, byte[] requestHash) {
        LedgerTransaction transaction = new LedgerTransaction()
                .setTenantId(tenantId)
                .setIdempotencyKey(idempotencyKey)
                .setRequestHash(requestHash)
                .setEffectiveAt(effectiveAt)
                .setPostingCount(request.getPostings().size())
                .setReversesTransactionId(request.getReversesTransactionId())
                .setMetadata(request.getMetadata() == null ? Map.of() : request.getMetadata());

        for (PostingLineRequest line : request.getPostings()) {
            transaction.addPosting(new Posting()
                    .setTenantId(tenantId)
                    .setAccountId(accountIds.get(line.getAccount()))
                    .setAsset(line.getAsset())
                    .setAmount(toMinorUnits(line, scales)));
        }

        LedgerTransaction saved = ledgerTransactionRepository.saveAndFlush(transaction);
        log.info("Posted transaction={} tenant={} seq={}", saved.getId(), tenantId, saved.getSeq());
        return new PostingResult(toResponse(saved), false);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(TenantPrincipal principal, UUID transactionId) {
        return ledgerTransactionRepository.findByIdAndTenantId(transactionId, principal.tenantId())
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("ERR_TRANSACTION_NOT_FOUND", "No transaction " + transactionId));
    }

    private static long toMinorUnits(PostingLineRequest line, Map<String, Short> scales) {
        Short scale = scales.get(line.getAsset());
        if (scale == null) {
            throw ApiException.unprocessable("ERR_UNKNOWN_ASSET", "Unknown asset " + line.getAsset());
        }
        BigDecimal decimal = new BigDecimal(line.getAmount());
        if (decimal.signum() == 0) {
            throw ApiException.unprocessable("ERR_ZERO_AMOUNT", "Posting amounts cannot be zero");
        }
        if (decimal.stripTrailingZeros().scale() > scale) {
            throw ApiException.unprocessable("ERR_AMOUNT_PRECISION",
                    line.getAmount() + " has more than " + scale + " decimal places allowed for " + line.getAsset());
        }
        try {
            return decimal.movePointRight(scale).longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.unprocessable("ERR_AMOUNT_OUT_OF_RANGE", line.getAmount() + " " + line.getAsset() + " is too large");
        }
    }

    private TransactionResponse toResponse(LedgerTransaction transaction) {
        Map<String, Short> scales = assetScales();
        Map<UUID, String> paths = accountService.pathsById(transaction.getTenantId(),
                transaction.getPostings().stream().map(Posting::getAccountId).collect(Collectors.toSet()));

        return TransactionResponse.builder()
                .id(transaction.getId())
                .seq(transaction.getSeq())
                .idempotencyKey(transaction.getIdempotencyKey())
                .effectiveAt(transaction.getEffectiveAt())
                .recordedAt(transaction.getRecordedAt())
                .reversesTransactionId(transaction.getReversesTransactionId())
                .createdBy(transaction.getCreatedBy())
                .metadata(transaction.getMetadata())
                .postings(transaction.getPostings().stream()
                        .map(posting -> new TransactionResponse.PostingResponse(
                                posting.getId(),
                                posting.getAccountId(),
                                paths.get(posting.getAccountId()),
                                posting.getAsset(),
                                BigDecimal.valueOf(posting.getAmount(), scales.get(posting.getAsset())).toPlainString()))
                        .toList())
                .build();
    }

    private Map<String, Short> assetScales() {
        return assetRepository.findAll().stream().collect(Collectors.toMap(Asset::getCode, Asset::getScale));
    }
}
