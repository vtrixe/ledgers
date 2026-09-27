package com.example.ledgers.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Append-only ledger, frozen columns, and the least-privilege app role. */
class ImmutabilityTest extends SchemaTestSupport {

    private UUID tenant;
    private UUID bank;
    private UUID equity;
    private UUID transaction;

    @BeforeEach
    void setUp() {
        tenant = createTenant();
        bank = createAccount(tenant, "assets:bank:main");
        equity = createAccount(tenant, "equity:owner");
        transaction = post(tenant, leg(bank, "INR", 100), leg(equity, "INR", -100));
    }

    @Test
    void ledgerRowsCannotBeUpdatedOrDeletedEvenByTheOwner() {
        assertRejected(() -> jdbc.sql("UPDATE postings SET amount = amount * 2 WHERE transaction_id = ?")
                .param(transaction).update(), "LEDGER_IMMUTABLE");
        assertRejected(() -> jdbc.sql("DELETE FROM postings WHERE transaction_id = ?")
                .param(transaction).update(), "LEDGER_IMMUTABLE");
        assertRejected(() -> jdbc.sql("UPDATE transactions SET metadata = '{\"x\":1}' WHERE id = ?")
                .param(transaction).update(), "LEDGER_IMMUTABLE");
        assertRejected(() -> jdbc.sql("DELETE FROM transactions WHERE id = ?")
                .param(transaction).update(), "LEDGER_IMMUTABLE");
    }

    @Test
    void truncateIsBlockedIncludingCascades() {
        assertRejected(() -> jdbc.sql("TRUNCATE postings").update(), "LEDGER_IMMUTABLE");
        assertRejected(() -> jdbc.sql("TRUNCATE tenants CASCADE").update(), "LEDGER_IMMUTABLE");
    }

    @Test
    void referenceRowsAreNeverDeleted() {
        assertRejected(() -> jdbc.sql("DELETE FROM accounts WHERE id = ?").param(bank).update(), "LEDGER_IMMUTABLE");
        assertRejected(() -> jdbc.sql("DELETE FROM tenants WHERE id = ?").param(tenant).update(), "LEDGER_IMMUTABLE");
    }

    @Test
    void historyIsAppendOnly() {
        assertRejected(() -> jdbc.sql("DELETE FROM accounts_history WHERE id = ?").param(bank).update(),
                "LEDGER_IMMUTABLE");
    }

    @Test
    void assetScaleCanNeverChange() {
        assertRejected(() -> jdbc.sql("UPDATE assets SET scale = 3 WHERE code = 'INR'").update(), "LEDGER_IMMUTABLE");
    }

    @Test
    void ledgerHeadCanOnlyAdvanceByOne() {
        assertRejected(() -> jdbc.sql("UPDATE ledger_heads SET last_seq = 0 WHERE tenant_id = ?")
                .param(tenant).update(), "LEDGER_IMMUTABLE");
    }

    @Test
    void functionalAssetFreezesAfterFirstTransaction() {
        UUID fresh = createTenant();
        jdbc.sql("UPDATE tenants SET functional_asset = 'USD' WHERE id = ?").param(fresh).update();

        assertRejected(() -> jdbc.sql("UPDATE tenants SET functional_asset = 'USD' WHERE id = ?")
                .param(tenant).update(), "LEDGER_IMMUTABLE");
    }

    @Test
    void onlyOneActiveApiKeyPerTenantAndRevocationIsFinal() {
        UUID key = insertApiKey(tenant);
        assertRejected(() -> insertApiKey(tenant), "api_keys_one_active_per_tenant");

        jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE id = ?").param(key).update();
        insertApiKey(tenant);   // rotation: the old one is revoked, a new one may be issued

        assertRejected(() -> jdbc.sql("UPDATE api_keys SET revoked_at = NULL WHERE id = ?").param(key).update(),
                "LEDGER_IMMUTABLE");
        assertThat(count("SELECT count(*) FROM api_keys_history WHERE id = ?", key)).isEqualTo(2);
    }

    @Test
    void appRoleCanPostButCannotRewriteHistory() {
        inTransaction(() -> {
            jdbc.sql("SET LOCAL ROLE ledger_app").update();
            UUID id = insertHeader(tenant, 2, null, null);
            insertPosting(tenant, id, leg(bank, "INR", 7));
            insertPosting(tenant, id, leg(equity, "INR", -7));
        });

        assertRejected(() -> asAppRole("UPDATE postings SET amount = 1 WHERE transaction_id = ?", transaction),
                "permission denied");
        assertRejected(() -> asAppRole("UPDATE ledger_heads SET last_seq = last_seq + 1 WHERE tenant_id = ?", tenant),
                "permission denied");
        assertRejected(() -> asAppRole("UPDATE tenants SET closed_through = now() WHERE id = ?", tenant),
                "permission denied");
        assertRejected(() -> asAppRole("UPDATE accounts SET tenant_id = tenant_id WHERE id = ?", bank),
                "permission denied");
    }

    private void asAppRole(String sql, UUID param) {
        inTransaction(() -> {
            jdbc.sql("SET LOCAL ROLE ledger_app").update();
            jdbc.sql(sql).param(param).update();
        });
    }

    private UUID insertApiKey(UUID tenantId) {
        return jdbc.sql("""
                        INSERT INTO api_keys (tenant_id, key_hash, hint, name, scopes)
                        VALUES (?, ?, 'xT7a', 'checkout', ARRAY['ledger:write']) RETURNING id""")
                .params(tenantId, randomHash())
                .query(UUID.class)
                .single();
    }

}
