package com.example.ledgers.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Bi-temporal rules: no future dating, the period lock, reopening, and the close/post race. */
class TimeAndPeriodLockTest extends SchemaTestSupport {

    @Autowired
    private DataSource dataSource;

    private UUID tenant;
    private UUID bank;
    private UUID equity;

    @BeforeEach
    void setUp() {
        tenant = createTenant();
        bank = createAccount(tenant, "assets:bank:main");
        equity = createAccount(tenant, "equity:owner");
    }

    @Test
    void futureDatedTransactionsAreRejectedBeyondClockSkew() {
        post(tenant, Instant.now().plus(Duration.ofMinutes(1)), null, leg(bank, "INR", 1), leg(equity, "INR", -1));

        assertRejected(() -> post(tenant, Instant.now().plus(Duration.ofHours(1)), null,
                leg(bank, "INR", 1), leg(equity, "INR", -1)), "LEDGER_FUTURE_DATED");
    }

    @Test
    void backdatingIsAllowedIntoAnOpenPeriodOnly() {
        post(tenant, Instant.now().minus(Duration.ofDays(30)), null, leg(bank, "INR", 1), leg(equity, "INR", -1));

        closeThrough("now() - interval '7 days'");

        assertRejected(() -> post(tenant, Instant.now().minus(Duration.ofDays(8)), null,
                leg(bank, "INR", 1), leg(equity, "INR", -1)), "LEDGER_PERIOD_CLOSED");
        post(tenant, Instant.now().minus(Duration.ofDays(6)), null, leg(bank, "INR", 1), leg(equity, "INR", -1));
    }

    @Test
    void periodCannotBeClosedInTheFuture() {
        assertRejected(() -> closeThrough("now() + interval '1 day'"), "LEDGER_INVALID");
    }

    @Test
    void reopeningAPeriodMustBeDeliberateAndIsRecorded() {
        closeThrough("now() - interval '1 day'");

        assertRejected(() -> closeThrough("now() - interval '2 days'"), "LEDGER_PERIOD_REOPEN");

        inTransaction(() -> {
            jdbc.sql("SET LOCAL ledger.allow_reopen = 'on'").update();
            jdbc.sql("SET LOCAL ledger.actor = 'admin:alice'").update();
            closeThrough("now() - interval '2 days'");
        });

        assertThat(count("SELECT count(*) FROM tenants_history WHERE id = ? AND changed_by = 'admin:alice'", tenant))
                .isEqualTo(1);
    }

    @Test
    void timezoneMustBeRealAndFreezesAfterFirstClose() {
        assertRejected(() -> jdbc.sql("UPDATE tenants SET reporting_timezone = '+05:30' WHERE id = ?")
                .param(tenant).update(), "LEDGER_INVALID");

        jdbc.sql("UPDATE tenants SET reporting_timezone = 'Europe/London' WHERE id = ?").param(tenant).update();
        closeThrough("now() - interval '1 day'");

        assertRejected(() -> jdbc.sql("UPDATE tenants SET reporting_timezone = 'Asia/Kolkata' WHERE id = ?")
                .param(tenant).update(), "LEDGER_IMMUTABLE");
    }

    /**
     * Session A has inserted a header (holding FOR SHARE on the tenant row) but not committed.
     * Session B's period close must wait for A instead of slipping in underneath it.
     */
    @Test
    void periodCloseWaitsForInFlightPostings() throws SQLException {
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            try (PreparedStatement insert = a.prepareStatement("""
                    INSERT INTO transactions (tenant_id, idempotency_key, request_hash, posting_count)
                    VALUES (?, 'in-flight', ?, 2)""")) {
                insert.setObject(1, tenant);
                insert.setBytes(2, randomHash());
                insert.executeUpdate();
            }

            try (Statement close = b.createStatement()) {
                close.execute("SET lock_timeout = '500ms'");
                assertThatThrownBy(() -> close.executeUpdate(
                        "UPDATE tenants SET closed_through = now() WHERE id = '" + tenant + "'"))
                        .hasMessageContaining("lock timeout");
            }
            finally {
                a.rollback();
            }
        }
    }

    private void closeThrough(String sqlInstant) {
        jdbc.sql("UPDATE tenants SET closed_through = " + sqlInstant + " WHERE id = ?").param(tenant).update();
    }

}
