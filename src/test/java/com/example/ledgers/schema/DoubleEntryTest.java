package com.example.ledgers.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Balance, posting-count and tenancy invariants, checked by Postgres at INSERT or COMMIT. */
class DoubleEntryTest extends SchemaTestSupport {

    private UUID tenant;
    private UUID clearing;
    private UUID payable;
    private UUID fees;

    @BeforeEach
    void setUp() {
        tenant = createTenant();
        clearing = createAccount(tenant, "assets:psp:razorpay:clearing");
        payable = createAccount(tenant, "liabilities:merchants:rest_42:payable");
        fees = createAccount(tenant, "revenue:fees:platform");
    }

    @Test
    void balancedTransactionCommits() {
        UUID id = post(tenant, leg(clearing, "INR", 50_000), leg(payable, "INR", -45_000), leg(fees, "INR", -5_000));

        assertThat(count("SELECT count(*) FROM postings WHERE transaction_id = ?", id)).isEqualTo(3);
    }

    @Test
    void unbalancedTransactionIsRolledBackAtCommitIncludingItsHeader() {
        assertRejected(() -> inTransaction(() -> {
            UUID id = insertHeader(tenant, "typo", 3, null, null);
            insertPosting(tenant, id, leg(clearing, "INR", 50_000));
            insertPosting(tenant, id, leg(payable, "INR", -45_000));
            insertPosting(tenant, id, leg(fees, "INR", -4_000));   // inserts fine; fails only at COMMIT
        }), "LEDGER_UNBALANCED");

        assertThat(count("SELECT count(*) FROM transactions WHERE tenant_id = ? AND idempotency_key = 'typo'", tenant))
                .isZero();
    }

    @Test
    void mustBalancePerAssetNotJustInTotal() {
        assertRejected(() -> post(tenant, leg(clearing, "INR", 100), leg(payable, "USD", -100)), "LEDGER_UNBALANCED");
    }

    @Test
    void zeroAmountIsRejectedImmediatelyAtInsert() {
        assertRejected(() -> post(tenant, leg(clearing, "INR", 0), leg(payable, "INR", 0)), "postings_amount_check");
    }

    @Test
    void headerWithoutPostingsIsRejected() {
        assertRejected(() -> inTransaction(() -> insertHeader(tenant, 2, null, null)), "LEDGER_POSTING_COUNT");
    }

    @Test
    void postingCountMustMatchHeader() {
        assertRejected(() -> inTransaction(() -> {
            UUID id = insertHeader(tenant, 3, null, null);
            insertPosting(tenant, id, leg(clearing, "INR", 100));
            insertPosting(tenant, id, leg(payable, "INR", -100));
        }), "LEDGER_POSTING_COUNT");
    }

    @Test
    void postingsCannotBeAddedToACommittedTransaction() {
        UUID id = post(tenant, leg(clearing, "INR", 100), leg(payable, "INR", -100));

        // Balanced on its own, so only the posting count catches it.
        assertRejected(() -> inTransaction(() -> {
            insertPosting(tenant, id, leg(clearing, "INR", 1_000));
            insertPosting(tenant, id, leg(fees, "INR", -1_000));
        }), "LEDGER_POSTING_COUNT");
    }

    @Test
    void transactionMustTouchAtLeastTwoAccounts() {
        assertRejected(() -> post(tenant, leg(clearing, "INR", 100), leg(clearing, "INR", -100)),
                "LEDGER_SINGLE_ACCOUNT");
    }

    @Test
    void postingCannotUseAnotherTenantsAccount() {
        UUID otherTenant = createTenant();
        UUID foreignAccount = createAccount(otherTenant, "assets:bank:main");

        assertRejected(() -> post(tenant, leg(clearing, "INR", 100), leg(foreignAccount, "INR", -100)),
                "postings_tenant_id_account_id_fkey");
    }

    @Test
    void idempotencyKeyIsUniquePerTenant() {
        inTransaction(() -> {
            UUID id = insertHeader(tenant, "order-981", 2, null, null);
            insertPosting(tenant, id, leg(clearing, "INR", 100));
            insertPosting(tenant, id, leg(payable, "INR", -100));
        });

        assertRejected(() -> inTransaction(() -> insertHeader(tenant, "order-981", 2, null, null)),
                "transactions_tenant_id_idempotency_key_key");

        UUID otherTenant = createTenant();
        UUID a = createAccount(otherTenant, "assets:bank:main");
        UUID b = createAccount(otherTenant, "equity:owner");
        inTransaction(() -> {
            UUID id = insertHeader(otherTenant, "order-981", 2, null, null);
            insertPosting(otherTenant, id, leg(a, "INR", 100));
            insertPosting(otherTenant, id, leg(b, "INR", -100));
        });
    }

    @Test
    void seqIsGapFreePerTenantEvenAfterARollback() {
        UUID first = post(tenant, leg(clearing, "INR", 100), leg(payable, "INR", -100));
        assertRejected(() -> post(tenant, leg(clearing, "INR", 100), leg(payable, "INR", -99)), "LEDGER_UNBALANCED");
        UUID second = post(tenant, leg(clearing, "INR", 100), leg(payable, "INR", -100));

        assertThat(count("SELECT seq FROM transactions WHERE id = ?", first)).isEqualTo(1);
        assertThat(count("SELECT seq FROM transactions WHERE id = ?", second)).isEqualTo(2);
    }

    @Test
    void databaseOwnsRecordedAtSeqAndCreatedBy() {
        UUID id = inTransaction(() -> {
            UUID txId = jdbc.sql("""
                            INSERT INTO transactions (tenant_id, idempotency_key, request_hash, posting_count,
                                                      seq, recorded_at, created_by)
                            VALUES (?, 'forged', ?, 2, 999, '2000-01-01T00:00:00Z', 'someone-else') RETURNING id""")
                    .params(tenant, randomHash())
                    .query(UUID.class)
                    .single();
            insertPosting(tenant, txId, leg(clearing, "INR", 100));
            insertPosting(tenant, txId, leg(payable, "INR", -100));
            return txId;
        });

        assertThat(count("SELECT seq FROM transactions WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE id = ? AND recorded_at > now() - interval '1 minute'", id))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE id = ? AND created_by <> 'someone-else'", id))
                .isEqualTo(1);
    }

}
