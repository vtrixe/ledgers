package com.example.ledgers.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A reversal is an exact mirror of the original, at most once, never of another reversal. */
class ReversalTest extends SchemaTestSupport {

    private UUID tenant;
    private UUID clearing;
    private UUID payable;
    private UUID fees;
    private UUID original;

    @BeforeEach
    void setUp() {
        tenant = createTenant();
        clearing = createAccount(tenant, "assets:psp:razorpay:clearing");
        payable = createAccount(tenant, "liabilities:merchants:rest_42:payable");
        fees = createAccount(tenant, "revenue:fees:platform");
        original = post(tenant, leg(clearing, "INR", 50_000), leg(payable, "INR", -45_000), leg(fees, "INR", -5_000));
    }

    @Test
    void exactMirrorReversesTheOriginal() {
        post(tenant, null, original,
                leg(clearing, "INR", -50_000), leg(payable, "INR", 45_000), leg(fees, "INR", 5_000));

        // Every account is back to zero.
        assertThat(count("""
                SELECT count(*) FROM (SELECT account_id FROM postings WHERE tenant_id = ?
                                      GROUP BY account_id, asset HAVING sum(amount) <> 0) nonzero""", tenant))
                .isZero();
    }

    @Test
    void aTransactionCanOnlyBeReversedOnce() {
        post(tenant, null, original,
                leg(clearing, "INR", -50_000), leg(payable, "INR", 45_000), leg(fees, "INR", 5_000));

        assertRejected(() -> post(tenant, null, original,
                        leg(clearing, "INR", -50_000), leg(payable, "INR", 45_000), leg(fees, "INR", 5_000)),
                "transactions_reverses_transaction_id_key");
    }

    @Test
    void partialRefundIsNotAReversal() {
        // Balanced, but not the mirror: a ₹200 refund must be posted as an ordinary transaction.
        assertRejected(() -> post(tenant, null, original, leg(clearing, "INR", -20_000), leg(payable, "INR", 20_000)),
                "LEDGER_INVALID_REVERSAL");
    }

    @Test
    void aReversalCannotItselfBeReversed() {
        UUID reversal = post(tenant, null, original,
                leg(clearing, "INR", -50_000), leg(payable, "INR", 45_000), leg(fees, "INR", 5_000));

        assertRejected(() -> post(tenant, null, reversal,
                        leg(clearing, "INR", 50_000), leg(payable, "INR", -45_000), leg(fees, "INR", -5_000)),
                "LEDGER_INVALID_REVERSAL");
    }

    @Test
    void cannotReverseAnotherTenantsTransaction() {
        UUID otherTenant = createTenant();
        UUID a = createAccount(otherTenant, "assets:bank:main");
        UUID b = createAccount(otherTenant, "equity:owner");

        assertRejected(() -> post(otherTenant, null, original, leg(a, "INR", 100), leg(b, "INR", -100)),
                "transactions_tenant_id_reverses_transaction_id_fkey");
    }

}
