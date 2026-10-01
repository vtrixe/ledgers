package com.example.ledgers.posting;

import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingLineRequest;
import com.example.ledgers.posting.dto.StatementResponse;
import com.example.ledgers.schema.SchemaTestSupport;
import com.example.ledgers.shared.ApiException;
import com.example.ledgers.tenancy.Scopes;
import com.example.ledgers.tenancy.TenantPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementTest extends SchemaTestSupport {

    @Autowired
    private PostingService postingService;

    @Autowired
    private BalanceService balanceService;

    private TenantPrincipal principal;
    private UUID bankId;

    @BeforeEach
    void setUp() {
        UUID tenantId = createTenant();
        bankId = createAccount(tenantId, "assets:bank:main");
        createAccount(tenantId, "equity:owner");
        principal = new TenantPrincipal(tenantId, UUID.randomUUID(), Scopes.ALL);
        for (int i = 1; i <= 5; i++) {
            PostTransactionRequest request = new PostTransactionRequest();
            request.setPostings(List.of(
                    new PostingLineRequest("assets:bank:main", "INR", i + ".00"),
                    new PostingLineRequest("equity:owner", "INR", "-" + i + ".00")));
            postingService.postTransaction(principal, "deposit-" + i, request);
        }
    }

    @Test
    void pagesNewestFirstUntilTheLastPage() {
        List<Long> seqs = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            StatementResponse page = balanceService.getStatement(principal, bankId.toString(), cursor, 2);
            page.getEntries().forEach(entry -> seqs.add(entry.getSeq()));
            cursor = page.getNextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seqs).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void entriesCarryTheSignedAmountInTheAssetScale() {
        StatementResponse page = balanceService.getStatement(principal, "assets:bank:main", null, 1);

        assertThat(page.getEntries().getFirst().getAmount()).isEqualTo("5.00");
        assertThat(page.getPath()).isEqualTo("assets:bank:main");
    }

    @Test
    void findsTheAccountByIdOrByPathButNotByAnUnknownPath() {
        StatementResponse byId = balanceService.getStatement(principal, bankId.toString(), null, 10);
        StatementResponse byPath = balanceService.getStatement(principal, "assets:bank:main", null, 10);

        assertThat(byPath.getAccountId()).isEqualTo(byId.getAccountId());
        assertThatThrownBy(() -> balanceService.getStatement(principal, "assets:bank:typo", null, 10))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectsAGarbageCursor() {
        assertThatThrownBy(() -> balanceService.getStatement(principal, bankId.toString(), "not-a-cursor", 2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cursor");
    }
}
