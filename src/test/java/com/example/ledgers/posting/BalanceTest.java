package com.example.ledgers.posting;

import com.example.ledgers.posting.dto.AssetBalance;
import com.example.ledgers.posting.dto.BalanceResponse;
import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingLineRequest;
import com.example.ledgers.posting.dto.TransactionResponse;
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
 * FR4 with the late settlement file: a ₹500 capture three days ago, then a settlement file that arrives now but is
 * effective two days ago (backdated).
 */
class BalanceTest extends SchemaTestSupport {

    private static final String CLEARING = "assets:psp:razorpay:clearing";
    private static final String PAYABLE = "liabilities:merchants:rest_42:payable";
    private static final String BANK = "assets:bank:main";
    private static final String FEES = "expenses:psp:razorpay";

    @Autowired
    private PostingService postingService;

    @Autowired
    private BalanceService balanceService;

    private TenantPrincipal principal;
    private OffsetDateTime now;
    private OffsetDateTime knownBeforeSettlement;

    @BeforeEach
    void setUp() {
        UUID tenantId = createTenant();
        createAccount(tenantId, CLEARING);
        createAccount(tenantId, PAYABLE);
        createAccount(tenantId, BANK);
        createAccount(tenantId, FEES);
        principal = new TenantPrincipal(tenantId, UUID.randomUUID(), Scopes.ALL);
        now = OffsetDateTime.now(ZoneOffset.UTC);

        TransactionResponse capture = post("capture", now.minusDays(3),
                line(CLEARING, "500.00"), line(PAYABLE, "-500.00"));
        knownBeforeSettlement = OffsetDateTime.ofInstant(capture.getRecordedAt(), ZoneOffset.UTC);

        post("settlement", now.minusDays(2),
                line(BANK, "490.00"), line(FEES, "10.00"), line(CLEARING, "-500.00"));
    }

    @Test
    void clearingNetsToZeroOnceTheSettlementIsPosted() {
        assertThat(inr(balanceService.getAccountBalance(principal, CLEARING, null, null))).isEqualTo("0.00");
    }

    @Test
    void asOfExcludesPostingsEffectiveLater() {
        BalanceResponse beforeSettlement = balanceService.getAccountBalance(principal, CLEARING, now.minusDays(2).minusHours(12), null);

        assertThat(inr(beforeSettlement)).isEqualTo("500.00");
    }

    @Test
    void knownAtReproducesWhatWasReportedBeforeTheLateFileArrived() {
        BalanceResponse asReported = balanceService.getAccountBalance(principal, CLEARING, now, knownBeforeSettlement);

        assertThat(inr(asReported)).isEqualTo("500.00");
    }

    @Test
    void liabilityBalanceUsesTheCreditNormalSign() {
        AssetBalance payable = balanceService.getAccountBalance(principal, PAYABLE, null, null).getBalances().getFirst();

        assertThat(payable.getRawMinorUnits()).isEqualTo(-50_000);
        assertThat(payable.getBalance()).isEqualTo("500.00");
    }

    @Test
    void rollupSumsOnlyTheAccountsUnderThePrefix() {
        assertThat(inr(balanceService.getRollupBalance(principal, "assets:", null, null))).isEqualTo("490.00");
        assertThat(inr(balanceService.getRollupBalance(principal, "expenses:", null, null))).isEqualTo("10.00");
        assertThat(balanceService.getRollupBalance(principal, "revenue:", null, null).getBalances()).isEmpty();
    }

    @Test
    void rollupPrefixMustStartWithAnAccountType() {
        assertThatThrownBy(() -> balanceService.getRollupBalance(principal, "psp:", null, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_INVALID_PREFIX");
    }

    private TransactionResponse post(String idempotencyKey, OffsetDateTime effectiveAt, PostingLineRequest... lines) {
        PostTransactionRequest request = new PostTransactionRequest();
        request.setEffectiveAt(effectiveAt);
        request.setPostings(List.of(lines));
        return postingService.postTransaction(principal, idempotencyKey, request).getTransaction();
    }

    private static PostingLineRequest line(String account, String amount) {
        return new PostingLineRequest(account, "INR", amount);
    }

    private static String inr(BalanceResponse response) {
        return response.getBalances().stream()
                .filter(balance -> balance.getAsset().equals("INR"))
                .findFirst()
                .map(AssetBalance::getBalance)
                .orElseThrow();
    }
}
