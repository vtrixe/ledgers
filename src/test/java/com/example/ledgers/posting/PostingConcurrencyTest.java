package com.example.ledgers.posting;

import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingLineRequest;
import com.example.ledgers.posting.dto.PostingResult;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** NFR1 and the same-key race: many requests released at the same instant, then the end state is checked. */
class PostingConcurrencyTest extends SchemaTestSupport {

    private static final String BANK = "assets:bank:main";
    private static final String WALLET = "liabilities:wallets:user_7";
    private static final String SALES = "revenue:sales:food";

    @Autowired
    private PostingService postingService;

    private UUID tenantId;
    private UUID walletId;
    private TenantPrincipal principal;

    @BeforeEach
    void setUp() {
        tenantId = createTenant();
        createAccount(tenantId, BANK);
        createAccount(tenantId, SALES);
        walletId = createAccount(tenantId, WALLET);         // allow_negative defaults to false
        principal = new TenantPrincipal(tenantId, UUID.randomUUID(), Scopes.ALL);
    }

    @Test
    void concurrentSpendsNeverOverdrawAProtectedWallet() throws Exception {
        postingService.postTransaction(principal, "top-up", request(line(BANK, "100.00"), line(WALLET, "-100.00")));

        AtomicInteger insufficientFunds = new AtomicInteger();
        List<Throwable> unexpected = runAtOnce(50, i -> () -> {
            try {
                postingService.postTransaction(principal, "spend-" + i,
                        request(line(WALLET, "10.00"), line(SALES, "-10.00")));
            } catch (ApiException e) {
                if (!"ERR_INSUFFICIENT_FUNDS".equals(e.getCode())) {
                    throw e;
                }
                insufficientFunds.incrementAndGet();
            }
            return null;
        });

        assertThat(unexpected).isEmpty();
        assertThat(insufficientFunds.get()).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM transactions WHERE tenant_id = ? AND idempotency_key LIKE 'spend-%'", tenantId))
                .isEqualTo(10);
        assertThat(count("SELECT coalesce(sum(amount), 0) FROM postings WHERE account_id = ? AND asset = 'INR'", walletId))
                .isZero();
    }

    @Test
    void concurrentRetriesWithTheSameKeyPostOnceAndReplayTheRest() throws Exception {
        postingService.postTransaction(principal, "top-up", request(line(BANK, "100.00"), line(WALLET, "-100.00")));

        List<PostingResult> results = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = runAtOnce(10, i -> () -> {
            results.add(postingService.postTransaction(principal, "order-981",
                    request(line(WALLET, "10.00"), line(SALES, "-10.00"))));
            return null;
        });

        assertThat(unexpected).isEmpty();
        assertThat(results).hasSize(10);
        assertThat(results.stream().filter(result -> !result.isReplayed())).hasSize(1);
        assertThat(results.stream().map(result -> result.getTransaction().getId()).distinct()).hasSize(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE tenant_id = ? AND idempotency_key = 'order-981'", tenantId))
                .isEqualTo(1);
    }

    private List<Throwable> runAtOnce(int threads, IntFunction<Callable<Void>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> work = task.apply(i);
            futures.add(pool.submit(() -> {
                start.await();
                return work.call();
            }));
        }
        start.countDown();

        List<Throwable> unexpected = new ArrayList<>();
        for (Future<Void> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                unexpected.add(e.getCause());
            }
        }
        pool.shutdown();
        return unexpected;
    }

    private static PostTransactionRequest request(PostingLineRequest... lines) {
        PostTransactionRequest request = new PostTransactionRequest();
        request.setPostings(List.of(lines));
        return request;
    }

    private static PostingLineRequest line(String account, String amount) {
        return new PostingLineRequest(account, "INR", amount);
    }
}
