package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.ConversionRequest;
import com.example.ledgers.fx.dto.ConversionResponse;
import com.example.ledgers.fx.dto.CreateFxRateRequest;
import com.example.ledgers.fx.dto.RevaluationRequest;
import com.example.ledgers.posting.BalanceService;
import com.example.ledgers.posting.PostingService;
import com.example.ledgers.posting.dto.AssetBalance;
import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingLineRequest;
import com.example.ledgers.schema.SchemaTestSupport;
import com.example.ledgers.shared.ApiException;
import com.example.ledgers.tenancy.Scopes;
import com.example.ledgers.tenancy.TenantPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 5 acceptance. Rates are global, so every test uses its own pair or a time window far from the others
 * (more than the 7-day max rate age apart).
 */
class FxTest extends SchemaTestSupport {

    private static final String WALLET = "liabilities:wallets:user_7";
    private static final String BANK = "assets:bank:main";

    @Autowired
    private PostingService postingService;

    @Autowired
    private ConversionService conversionService;

    @Autowired
    private FxPositionService fxPositionService;

    @Autowired
    private FxRateService fxRateService;

    @Autowired
    private BalanceService balanceService;

    private TenantPrincipal principal;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        UUID tenantId = createTenant();
        createAccount(tenantId, WALLET);
        createAccount(tenantId, BANK);
        principal = new TenantPrincipal(tenantId, UUID.randomUUID(), Scopes.ALL);
        now = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void conversionBalancesEachAssetAndRecordsTheRate() {
        OffsetDateTime t = now.minusDays(300);
        rate("USD", "INR", "83.4567", t);
        fund("USD", "50.00", t);

        ConversionResponse response = convert("c1", "USD", "INR", "10.00", t.plusMinutes(1));

        assertThat(response.getToAmount()).isEqualTo("834.57");
        assertThat(response.getRate()).isEqualTo("83.4567");
        assertThat(response.getTransaction().getPostings()).hasSize(4);
        assertThat(walletBalance("USD")).isEqualTo("40.00");
        assertThat(walletBalance("INR")).isEqualTo("834.57");
        assertThat(count("""
                SELECT count(*) FROM (SELECT asset FROM postings WHERE transaction_id = ?
                                      GROUP BY asset HAVING sum(amount) <> 0) unbalanced""",
                response.getTransaction().getId())).isZero();
    }

    @Test
    void retryReplaysTheOriginalEvenAfterANewerRateArrives() {
        rate("USD", "EUR", "0.80", now.minusHours(1));
        fund("USD", "50.00", now.minusHours(2));

        ConversionResponse first = convert("c2", "USD", "EUR", "10.00", null);
        rate("USD", "EUR", "0.90", now.minusMinutes(1));
        ConversionResponse retry = convert("c2", "USD", "EUR", "10.00", null);

        assertThat(first.getToAmount()).isEqualTo("8.00");
        assertThat(retry.isReplayed()).isTrue();
        assertThat(retry.getToAmount()).isEqualTo("8.00");
        assertThat(retry.getTransaction().getId()).isEqualTo(first.getTransaction().getId());
        assertThatThrownBy(() -> convert("c2", "USD", "EUR", "11.00", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_IDEMPOTENCY_MISMATCH");
    }

    @Test
    void crossRateGoesThroughEurAndJpyHasNoDecimals() {
        OffsetDateTime t = now.minusDays(100);
        rate("EUR", "USD", "1.25", t);
        rate("EUR", "JPY", "200", t);
        fund("USD", "50.00", t);

        assertThat(convert("c3", "USD", "JPY", "10.00", t.plusMinutes(1)).getToAmount()).isEqualTo("1600");
    }

    @Test
    void kwdHasThreeDecimals() {
        OffsetDateTime t = now.minusDays(90);
        rate("EUR", "USD", "1.25", t);
        rate("EUR", "KWD", "0.375", t);
        fund("USD", "50.00", t);

        assertThat(convert("c4", "USD", "KWD", "10.01", t.plusMinutes(1)).getToAmount()).isEqualTo("3.003");
    }

    @Test
    void roundsHalfToEven() {
        OffsetDateTime t = now.minusDays(200);
        rate("USD", "INR", "83.45", t);
        fund("USD", "50.00", t);

        assertThat(convert("c5", "USD", "INR", "0.10", t.plusMinutes(1)).getToAmount()).isEqualTo("8.34");
        assertThat(convert("c6", "USD", "INR", "0.30", t.plusMinutes(2)).getToAmount()).isEqualTo("25.04");
    }

    @Test
    void missingOrStaleRateIsRejected() {
        assertThatThrownBy(() -> convert("c7", "GBP", "SGD", "10.00", now.minusDays(60)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_NO_RATE");

        rate("GBP", "SGD", "1.70", now.minusDays(60));
        assertThatThrownBy(() -> convert("c8", "GBP", "SGD", "10.00", now.minusDays(52)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_NO_RATE");
    }

    @Test
    void cannotConvertMoreThanTheWalletHolds() {
        OffsetDateTime t = now.minusDays(30);
        rate("USD", "INR", "83.00", t);
        fund("USD", "5.00", t);

        assertThatThrownBy(() -> convert("c9", "USD", "INR", "10.00", t.plusMinutes(1)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_INSUFFICIENT_FUNDS");
    }

    @Test
    void revaluationBooksTheUnrealizedResultOnce() {
        OffsetDateTime t = now.minusDays(150);
        rate("USD", "INR", "83.4567", t);
        fund("USD", "50.00", t);
        convert("c10", "USD", "INR", "10.00", t.plusMinutes(1));
        rate("USD", "INR", "84.00", t.plusHours(1));
        OffsetDateTime periodEnd = t.plusHours(2);

        assertThat(fxPositionService.getPosition(principal, periodEnd).getNetValueMinorUnits()).isEqualTo(-543);

        assertThat(fxPositionService.revalue(principal, "reval-1", revaluation(periodEnd)).isBooked()).isTrue();
        assertThat(inrBalance(SystemAccounts.UNREALIZED_GAIN)).isEqualTo("5.43");
        assertThat(fxPositionService.getPosition(principal, periodEnd).getNetValueMinorUnits()).isZero();

        assertThat(fxPositionService.revalue(principal, "reval-2", revaluation(periodEnd)).isBooked()).isFalse();
    }

    private void rate(String base, String quote, String rate, OffsetDateTime asOf) {
        fxRateService.addRates(List.of(new CreateFxRateRequest(base, quote, rate, asOf, "test")));
    }

    private void fund(String asset, String amount, OffsetDateTime effectiveAt) {
        PostTransactionRequest request = new PostTransactionRequest();
        request.setEffectiveAt(effectiveAt);
        request.setPostings(List.of(
                new PostingLineRequest(BANK, asset, amount),
                new PostingLineRequest(WALLET, asset, "-" + amount)));
        postingService.postTransaction(principal, "fund-" + UUID.randomUUID(), request);
    }

    private ConversionResponse convert(String key, String fromAsset, String toAsset, String amount, OffsetDateTime effectiveAt) {
        ConversionRequest request = new ConversionRequest();
        request.setFromAccount(WALLET);
        request.setFromAsset(fromAsset);
        request.setToAccount(WALLET);
        request.setToAsset(toAsset);
        request.setAmount(amount);
        request.setEffectiveAt(effectiveAt);
        return conversionService.convert(principal, key, request);
    }

    private static RevaluationRequest revaluation(OffsetDateTime asOf) {
        RevaluationRequest request = new RevaluationRequest();
        request.setAsOf(asOf);
        return request;
    }

    private String walletBalance(String asset) {
        return balanceService.getAccountBalance(principal, WALLET, null, null).getBalances().stream()
                .filter(balance -> balance.getAsset().equals(asset))
                .findFirst()
                .map(AssetBalance::getBalance)
                .orElseThrow();
    }

    private String inrBalance(String account) {
        return balanceService.getAccountBalance(principal, account, null, null).getBalances().stream()
                .filter(balance -> balance.getAsset().equals("INR"))
                .findFirst()
                .map(AssetBalance::getBalance)
                .orElseThrow();
    }
}
