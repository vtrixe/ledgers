package com.example.ledgers.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Path format, derived type, renames and the never-reuse rule. */
class AccountPathTest extends SchemaTestSupport {

    private UUID tenant;

    @BeforeEach
    void setUp() {
        tenant = createTenant();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Assets:bank", "assets", "assets::bank", "assets:bank:", "cash:bank", "assets:bank-1"})
    void rejectsMalformedPaths(String path) {
        assertRejected(() -> createAccount(tenant, path), "accounts_path_check");
    }

    @Test
    void typeIsDerivedFromTheFirstSegment() {
        UUID id = createAccount(tenant, "revenue:fees:platform");

        assertThat(jdbc.sql("SELECT type FROM accounts WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("revenue");
    }

    @Test
    void renameIsAllowedAndRecorded() {
        UUID id = createAccount(tenant, "assets:psp:razorpay:clearing");
        rename(id, "assets:psp:rzp:clearing");

        assertThat(count("SELECT count(*) FROM account_paths WHERE account_id = ?", id)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM accounts_history WHERE id = ?", id)).isEqualTo(2);
    }

    @Test
    void renameCannotChangeTheAccountType() {
        UUID id = createAccount(tenant, "assets:psp:razorpay:clearing");

        assertRejected(() -> rename(id, "liabilities:psp:razorpay:clearing"), "LEDGER_IMMUTABLE");
    }

    @Test
    void retiredPathIsNeverReused() {
        UUID id = createAccount(tenant, "assets:psp:razorpay:clearing");
        rename(id, "assets:psp:rzp:clearing");

        assertRejected(() -> createAccount(tenant, "assets:psp:razorpay:clearing"), "LEDGER_PATH_RETIRED");
        assertRejected(() -> rename(id, "assets:psp:razorpay:clearing"), "LEDGER_PATH_RETIRED");
    }

    @Test
    void samePathIsFineInAnotherTenant() {
        createAccount(tenant, "assets:bank:main");
        createAccount(createTenant(), "assets:bank:main");
    }

    @Test
    void metadataAndAllowNegativeAreUpdatableWithHistory() {
        UUID id = createAccount(tenant, "assets:wallet:user_7");
        jdbc.sql("UPDATE accounts SET allow_negative = true, metadata = '{\"region\":\"south\"}' WHERE id = ?")
                .param(id).update();

        assertThat(count("SELECT count(*) FROM accounts_history WHERE id = ? AND allow_negative", id)).isEqualTo(1);
    }

    private void rename(UUID accountId, String path) {
        jdbc.sql("UPDATE accounts SET path = ? WHERE id = ?").params(path, accountId).update();
    }

}
