package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.CreateFxRateRequest;
import com.example.ledgers.schema.SchemaTestSupport;
import com.example.ledgers.tenancy.Scopes;
import com.example.ledgers.tenancy.TenantPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Plumbing: rates are append-only and deduplicated; system accounts are created once. */
class FxRateTest extends SchemaTestSupport {

    private static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2001, 1, 2, 16, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private FxRateService fxRateService;

    @Autowired
    private SystemAccounts systemAccounts;

    @Test
    void ratesAreAppendOnly() {
        FxRate rate = fxRateService.addRates(List.of(new CreateFxRateRequest("AED", "SGD", "0.3650", LONG_AGO, "test")))
                .getFirst();

        assertRejected(() -> jdbc.sql("UPDATE fx_rates SET rate = 1 WHERE id = ?").param(rate.getId()).update(),
                "LEDGER_IMMUTABLE");
        assertRejected(() -> jdbc.sql("DELETE FROM fx_rates WHERE id = ?").param(rate.getId()).update(),
                "LEDGER_IMMUTABLE");
    }

    @Test
    void addingTheSameRateTwiceStoresItOnce() {
        CreateFxRateRequest rate = new CreateFxRateRequest("AED", "SGD", "0.3651", LONG_AGO.plusDays(1), "test");

        assertThat(fxRateService.addRates(List.of(rate))).hasSize(1);
        assertThat(fxRateService.addRates(List.of(rate))).isEmpty();
    }

    @Test
    void baseAndQuoteMustDiffer() {
        assertRejected(() -> fxRateService.addRates(List.of(new CreateFxRateRequest("SGD", "SGD", "1", LONG_AGO, "test"))),
                "fx_rates_check");
    }

    @Test
    void systemAccountIsCreatedOnceAndMayGoNegative() {
        UUID tenantId = createTenant();
        TenantPrincipal principal = new TenantPrincipal(tenantId, UUID.randomUUID(), Scopes.ALL);

        systemAccounts.ensure(principal, SystemAccounts.tradingAccount("USD"));
        systemAccounts.ensure(principal, SystemAccounts.tradingAccount("USD"));

        assertThat(count("SELECT count(*) FROM accounts WHERE tenant_id = ? AND path = 'equity:fx:trading:usd' AND allow_negative",
                tenantId)).isEqualTo(1);
    }
}
