package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.FxPositionResponse;
import com.example.ledgers.fx.dto.RevaluationRequest;
import com.example.ledgers.fx.dto.RevaluationResponse;
import com.example.ledgers.posting.Asset;
import com.example.ledgers.posting.AssetRepository;
import com.example.ledgers.posting.BalanceService;
import com.example.ledgers.posting.PostingService;
import com.example.ledgers.posting.dto.AssetBalance;
import com.example.ledgers.posting.dto.BalanceResponse;
import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingLineRequest;
import com.example.ledgers.posting.dto.PostingResult;
import com.example.ledgers.tenancy.TenantAdminService;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class FxPositionServiceImpl implements FxPositionService {

    private final BalanceService balanceService;
    private final PostingService postingService;
    private final FxRateService fxRateService;
    private final TenantAdminService tenantAdminService;
    private final AssetRepository assetRepository;
    private final SystemAccounts systemAccounts;
    private final Clock clock;

    @Override
    public FxPositionResponse getPosition(TenantPrincipal principal, OffsetDateTime asOf) {
        Instant at = asOf == null
                ? clock.instant().truncatedTo(ChronoUnit.MICROS)
                : asOf.toInstant().truncatedTo(ChronoUnit.MICROS);
        String functionalAsset = tenantAdminService.getTenant(principal.tenantId()).getFunctionalAsset();
        Map<String, Short> scales = assetScales();

        BalanceResponse trading = balanceService.getRollupBalance(principal, SystemAccounts.TRADING_PREFIX,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), null);

        List<FxPositionResponse.Position> positions = new ArrayList<>();
        long netValue = 0;
        for (AssetBalance balance : trading.getBalances()) {
            FxQuote quote = fxRateService.findRate(balance.getAsset(), functionalAsset, at);
            long value = valueInFunctional(balance.getRawMinorUnits(), scales.get(balance.getAsset()), quote,
                    scales.get(functionalAsset));
            netValue = Math.addExact(netValue, value);
            positions.add(new FxPositionResponse.Position(
                    SystemAccounts.tradingAccount(balance.getAsset()),
                    balance.getAsset(),
                    balance.getRawMinorUnits(),
                    quote.rate().stripTrailingZeros().toPlainString(),
                    quote.asOf().toString(),
                    value,
                    format(value, scales.get(functionalAsset))));
        }

        return FxPositionResponse.builder()
                .asOf(at)
                .functionalAsset(functionalAsset)
                .positions(positions)
                .netValueMinorUnits(netValue)
                .netValue(format(netValue, scales.get(functionalAsset)))
                .build();
    }

    @Override
    public RevaluationResponse revalue(TenantPrincipal principal, String idempotencyKey, RevaluationRequest request) {
        FxPositionResponse position = getPosition(principal, request.getAsOf());

        systemAccounts.ensure(principal, SystemAccounts.tradingAccount(position.getFunctionalAsset()));
        systemAccounts.ensure(principal, SystemAccounts.UNREALIZED_GAIN);
        systemAccounts.ensure(principal, SystemAccounts.UNREALIZED_LOSS);

        List<PostingLineRequest> lines = revaluationLines(position);
        if (lines.isEmpty()) {
            return new RevaluationResponse(false, position, null);
        }

        Map<String, Object> fx = new LinkedHashMap<>();
        fx.put("type", "revaluation");
        fx.put("asOf", position.getAsOf().toString());
        fx.put("netValue", position.getNetValue());

        PostTransactionRequest posting = new PostTransactionRequest();
        posting.setEffectiveAt(OffsetDateTime.ofInstant(position.getAsOf(), ZoneOffset.UTC));
        posting.setMetadata(Map.of("fx", fx));
        posting.setPostings(lines);

        PostingResult result = postingService.postTransaction(principal, idempotencyKey, posting);
        log.info("FX revaluation tenant={} asOf={} net={}", principal.tenantId(), position.getAsOf(), position.getNetValue());
        return new RevaluationResponse(true, position, result.getTransaction());
    }

    private long valueInFunctional(long rawMinorUnits, short assetScale, FxQuote quote, short functionalScale) {
        BigDecimal val = BigDecimal.valueOf(rawMinorUnits,assetScale);

        BigDecimal converted = val.multiply(quote.rate());
        converted = converted.setScale(functionalScale, RoundingMode.HALF_EVEN);
        converted = converted.movePointRight(functionalScale);

        return converted.longValueExact();


    }

    private List<PostingLineRequest> revaluationLines(FxPositionResponse position) {

        BigDecimal net = new BigDecimal(position.getNetValue());
        if (net.signum() == 0) {
            return List.of();
        }

        String counterAccount;
        if (net.signum() < 0) {
            counterAccount = SystemAccounts.UNREALIZED_GAIN;
        } else {
            counterAccount = SystemAccounts.UNREALIZED_LOSS;
        }

        String functionalAsset = position.getFunctionalAsset();
        String tradingAccount = SystemAccounts.tradingAccount(functionalAsset);

        PostingLineRequest tradingLine = new PostingLineRequest(tradingAccount, functionalAsset, net.negate().toPlainString());
        PostingLineRequest counterLine = new PostingLineRequest(counterAccount, functionalAsset, net.toPlainString());

        return List.of(tradingLine, counterLine);

    }

    private static String format(long minorUnits, short scale) {
        return BigDecimal.valueOf(minorUnits, scale).toPlainString();
    }

    private Map<String, Short> assetScales() {
        Map<String, Short> scales = new HashMap<>();
        for (Asset asset : assetRepository.findAll()) {
            scales.put(asset.getCode(), asset.getScale());
        }
        return scales;
    }
}
