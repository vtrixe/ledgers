package com.example.ledgers.fx;

import com.example.ledgers.accounts.Account;
import com.example.ledgers.accounts.AccountService;
import com.example.ledgers.fx.dto.ConversionRequest;
import com.example.ledgers.fx.dto.ConversionResponse;
import com.example.ledgers.posting.Asset;
import com.example.ledgers.posting.AssetRepository;
import com.example.ledgers.posting.PostingService;
import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingLineRequest;
import com.example.ledgers.posting.dto.PostingResult;
import com.example.ledgers.shared.ApiException;
import com.example.ledgers.shared.CanonicalJson;
import com.example.ledgers.tenancy.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * A conversion is one transaction of four postings through the per-asset trading accounts, so each asset still
 * balances on its own. It goes through PostingService, so idempotency, the race retry and overdraft checks apply.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversionServiceImpl implements ConversionService {

    private static final String FX_METADATA = "fx";

    private final PostingService postingService;
    private final AccountService accountService;
    private final AssetRepository assetRepository;
    private final FxRateService fxRateService;
    private final SystemAccounts systemAccounts;
    private final Clock clock;

    @Override
    public ConversionResponse convert(TenantPrincipal principal, String idempotencyKey, ConversionRequest request) {
        validate(request);
        String fingerprint = fingerprint(request);

        Optional<PostingResult> existing = postingService.findExisting(principal, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, idempotencyKey);
        }

        Map<String, Short> scales = assetScales();
        BigDecimal fromAmount = parseAmount(request.getAmount(), request.getFromAsset(), scales);
        Instant effectiveAt = request.getEffectiveAt() == null
                ? clock.instant().truncatedTo(ChronoUnit.MICROS)
                : request.getEffectiveAt().toInstant().truncatedTo(ChronoUnit.MICROS);

        FxQuote quote = fxRateService.findRate(request.getFromAsset(), request.getToAsset(), effectiveAt);
        BigDecimal toAmount = convertAmount(fromAmount, quote, scales.get(request.getToAsset()));
        if (toAmount.signum() == 0) {
            throw ApiException.unprocessable("ERR_AMOUNT_TOO_SMALL",
                    request.getAmount() + " " + request.getFromAsset() + " converts to 0 " + request.getToAsset());
        }

        Map<String, Account> accounts = loadAccounts(principal, request.getFromAccount(), request.getToAccount());
        String fromTrading = systemAccounts.ensure(principal, SystemAccounts.tradingAccount(request.getFromAsset()));
        String toTrading = systemAccounts.ensure(principal, SystemAccounts.tradingAccount(request.getToAsset()));

        BigDecimal fromLeg = fromAmount.multiply(BigDecimal.valueOf(-accounts.get(request.getFromAccount()).normalSign()));
        BigDecimal toLeg = toAmount.multiply(BigDecimal.valueOf(accounts.get(request.getToAccount()).normalSign()));

        List<PostingLineRequest> lines = List.of(
                new PostingLineRequest(request.getFromAccount(), request.getFromAsset(), fromLeg.toPlainString()),
                new PostingLineRequest(fromTrading, request.getFromAsset(), fromLeg.negate().toPlainString()),
                new PostingLineRequest(toTrading, request.getToAsset(), toLeg.negate().toPlainString()),
                new PostingLineRequest(request.getToAccount(), request.getToAsset(), toLeg.toPlainString()));

        Map<String, Object> fx = new LinkedHashMap<>();
        fx.put("type", "conversion");
        fx.put("fromAsset", request.getFromAsset());
        fx.put("fromAmount", fromAmount.toPlainString());
        fx.put("toAsset", request.getToAsset());
        fx.put("toAmount", toAmount.toPlainString());
        fx.put("rate", quote.rate().stripTrailingZeros().toPlainString());
        fx.put("rateAsOf", quote.asOf().toString());
        fx.put("rateSource", quote.source());
        fx.put("requestHash", fingerprint);

        Map<String, Object> metadata = new HashMap<>(request.getMetadata() == null ? Map.of() : request.getMetadata());
        metadata.put(FX_METADATA, fx);

        PostTransactionRequest posting = new PostTransactionRequest();
        posting.setEffectiveAt(OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC));
        posting.setMetadata(metadata);
        posting.setPostings(lines);

        try {
            return toResponse(postingService.postTransaction(principal, idempotencyKey, posting));
        } catch (ApiException e) {
            if (!"ERR_IDEMPOTENCY_MISMATCH".equals(e.getCode())) {
                throw e;
            }
            return postingService.findExisting(principal, idempotencyKey)
                    .map(result -> replay(result, fingerprint, idempotencyKey))
                    .orElseThrow(() -> e);
        }
    }

    private BigDecimal convertAmount(BigDecimal fromAmount, FxQuote quote, short toScale) {
        BigDecimal toAmount = fromAmount.multiply(quote.rate());

        return toAmount.setScale(toScale, RoundingMode.HALF_EVEN);

    }

    private void validate(ConversionRequest request) {
        if (request.getFromAsset().equals(request.getToAsset())) {
            throw ApiException.badRequest("ERR_SAME_ASSET", "fromAsset and toAsset must differ");
        }
        if (request.getMetadata() != null && request.getMetadata().containsKey(FX_METADATA)) {
            throw ApiException.badRequest("ERR_RESERVED_METADATA", "metadata key \"fx\" is reserved");
        }
    }

    private String fingerprint(ConversionRequest request) {
        Map<String, Object> canonical = new HashMap<>();
        canonical.put("fromAccount", request.getFromAccount());
        canonical.put("fromAsset", request.getFromAsset());
        canonical.put("toAccount", request.getToAccount());
        canonical.put("toAsset", request.getToAsset());
        canonical.put("amount", new BigDecimal(request.getAmount()).stripTrailingZeros().toPlainString());
        canonical.put("effectiveAt", request.getEffectiveAt() == null ? null
                : request.getEffectiveAt().toInstant().truncatedTo(ChronoUnit.MICROS).toString());
        canonical.put("metadata", request.getMetadata() == null ? Map.of() : request.getMetadata());
        return CanonicalJson.sha256Hex(canonical);
    }

    @SuppressWarnings("unchecked")
    private ConversionResponse replay(PostingResult existing, String fingerprint, String idempotencyKey) {
        Object fx = existing.getTransaction().getMetadata().get(FX_METADATA);
        if (!(fx instanceof Map<?, ?> fxMap) || !fingerprint.equals(fxMap.get("requestHash"))) {
            throw ApiException.unprocessable("ERR_IDEMPOTENCY_MISMATCH",
                    "Idempotency-Key " + idempotencyKey + " was already used with a different request");
        }
        return toResponse(existing);
    }

    @SuppressWarnings("unchecked")
    private ConversionResponse toResponse(PostingResult result) {
        Map<String, Object> fx = (Map<String, Object>) result.getTransaction().getMetadata().get(FX_METADATA);
        return ConversionResponse.builder()
                .fromAsset((String) fx.get("fromAsset"))
                .fromAmount((String) fx.get("fromAmount"))
                .toAsset((String) fx.get("toAsset"))
                .toAmount((String) fx.get("toAmount"))
                .rate((String) fx.get("rate"))
                .rateAsOf((String) fx.get("rateAsOf"))
                .rateSource((String) fx.get("rateSource"))
                .replayed(result.isReplayed())
                .transaction(result.getTransaction())
                .build();
    }

    private Map<String, Account> loadAccounts(TenantPrincipal principal, String fromPath, String toPath) {
        Map<String, UUID> ids = accountService.resolvePaths(principal.tenantId(), new HashSet<>(List.of(fromPath, toPath)));
        Map<String, Account> accounts = new HashMap<>();
        for (Account account : accountService.getAccounts(principal.tenantId(), ids.values())) {
            accounts.put(account.getPath(), account);
        }
        return accounts;
    }

    private static BigDecimal parseAmount(String amount, String asset, Map<String, Short> scales) {
        Short scale = scales.get(asset);
        if (scale == null) {
            throw ApiException.unprocessable("ERR_UNKNOWN_ASSET", "Unknown asset " + asset);
        }
        BigDecimal decimal = new BigDecimal(amount);
        if (decimal.signum() == 0) {
            throw ApiException.unprocessable("ERR_ZERO_AMOUNT", "Amount cannot be zero");
        }
        if (decimal.stripTrailingZeros().scale() > scale) {
            throw ApiException.unprocessable("ERR_AMOUNT_PRECISION",
                    amount + " has more than " + scale + " decimal places allowed for " + asset);
        }
        return decimal.setScale(scale);
    }

    private Map<String, Short> assetScales() {
        Map<String, Short> scales = new HashMap<>();
        for (Asset asset : assetRepository.findAll()) {
            scales.put(asset.getCode(), asset.getScale());
        }
        return scales;
    }
}
