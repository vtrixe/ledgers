package com.example.ledgers.schema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ledgers.TestcontainersConfiguration;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base for tests that prove the database itself enforces the ledger invariants.
 *
 * <p>Every helper commits for real: deferred constraint triggers only run at COMMIT, so a test that rolls back
 * would never exercise them. Because the ledger is append-only nothing can be cleaned up, so each test creates
 * its own tenant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class SchemaTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    protected record Leg(UUID accountId, String asset, long amount) {
    }

    protected static Leg leg(UUID accountId, String asset, long amount) {
        return new Leg(accountId, asset, amount);
    }

    protected UUID createTenant() {
        return jdbc.sql("""
                        INSERT INTO tenants (name, functional_asset, reporting_timezone)
                        VALUES (?, 'INR', 'Asia/Kolkata') RETURNING id""")
                .param("tenant-" + UUID.randomUUID())
                .query(UUID.class)
                .single();
    }

    protected UUID createAccount(UUID tenantId, String path) {
        return jdbc.sql("INSERT INTO accounts (tenant_id, path) VALUES (?, ?) RETURNING id")
                .params(tenantId, path)
                .query(UUID.class)
                .single();
    }

    /** Posts a balanced-or-not transaction (header + postings) in one database transaction and commits it. */
    protected UUID post(UUID tenantId, Leg... legs) {
        return post(tenantId, null, null, legs);
    }

    protected UUID post(UUID tenantId, Instant effectiveAt, UUID reverses, Leg... legs) {
        return inTransaction(() -> {
            UUID transactionId = insertHeader(tenantId, legs.length, effectiveAt, reverses);
            for (Leg leg : legs) {
                insertPosting(tenantId, transactionId, leg);
            }
            return transactionId;
        });
    }

    /** Inserts only the header; call inside {@link #inTransaction}. */
    protected UUID insertHeader(UUID tenantId, int postingCount, Instant effectiveAt, UUID reverses) {
        return insertHeader(tenantId, "key-" + UUID.randomUUID(), postingCount, effectiveAt, reverses);
    }

    protected UUID insertHeader(UUID tenantId, String idempotencyKey, int postingCount, Instant effectiveAt,
            UUID reverses) {
        return jdbc.sql("""
                        INSERT INTO transactions (tenant_id, idempotency_key, request_hash, effective_at,
                                                  posting_count, reverses_transaction_id)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(tenantId, idempotencyKey, randomHash(),
                        effectiveAt == null ? null : Timestamp.from(effectiveAt), postingCount, reverses)
                .query(UUID.class)
                .single();
    }

    protected void insertPosting(UUID tenantId, UUID transactionId, Leg leg) {
        jdbc.sql("INSERT INTO postings (tenant_id, transaction_id, account_id, asset, amount) VALUES (?, ?, ?, ?, ?)")
                .params(tenantId, transactionId, leg.accountId(), leg.asset(), leg.amount())
                .update();
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    protected void inTransaction(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    protected long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** Asserts the database rejected the call; {@code expected} is our LEDGER_* code or a constraint name. */
    protected static void assertRejected(ThrowingCallable call, String expected) {
        assertThatThrownBy(call).rootCause().hasMessageContaining(expected);
    }

    protected static byte[] randomHash() {
        byte[] hash = new byte[32];
        RANDOM.nextBytes(hash);
        return hash;
    }

}
