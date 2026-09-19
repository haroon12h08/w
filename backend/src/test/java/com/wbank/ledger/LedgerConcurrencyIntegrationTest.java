package com.wbank.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.InsufficientFundsException;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 9. Concurrent posting does not violate ledger invariants.
 *
 * <p>Real threads, real connections, real PostgreSQL row and advisory locks. Each test
 * releases all workers from a single latch so that they genuinely contend.
 */
class LedgerConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    LedgerFixtures f;

    /** Runs {@code tasks} concurrently, released together, and returns their outcomes. */
    private <T> List<Object> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<T> fut : futures) {
                try {
                    outcomes.add(fut.get(60, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("Racing debits never overdraw an account: exactly the affordable number succeed")
    void racingWithdrawalsRespectTheFloor() throws Exception {
        LedgerAccount cash = f.account(LedgerAccountType.ASSET, "USD");
        LedgerAccount customer = f.customerAccount("USD");
        f.move(cash.getId(), customer.getId(), 10_000, "USD", null); // 100.00

        // 50 concurrent withdrawals of 3.00: only 33 are affordable (99.00), leaving 1.00.
        List<Callable<PostedEntry>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> f.move(customer.getId(), cash.getId(), 300, "USD", null));
        }
        List<Object> outcomes = race(tasks);

        long ok = outcomes.stream().filter(o -> o instanceof PostedEntry).count();
        long refused = outcomes.stream().filter(o -> o instanceof InsufficientFundsException).count();
        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOfAny(PostedEntry.class, InsufficientFundsException.class));
        assertThat(ok).isEqualTo(33);
        assertThat(refused).isEqualTo(17);
        assertThat(f.projected(customer.getId())).isEqualTo(100);
        assertThat(f.derived(customer.getId())).isEqualTo(100);
        assertThat(f.projected(cash.getId())).isEqualTo(10_000 - 9_900);
        // No running balance ever recorded below zero.
        assertThat(f.count("SELECT count(*) FROM posting WHERE ledger_account_id = ? AND balance_after_minor < 0",
                customer.getId())).isZero();
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    @Test
    @Timeout(120)
    @DisplayName("Opposite-direction transfers between the same accounts neither deadlock nor lose money")
    void oppositeTransfersDoNotDeadlock() throws Exception {
        LedgerAccount source = f.account(LedgerAccountType.ASSET, "USD");
        LedgerAccount a = f.customerAccount("USD");
        LedgerAccount b = f.customerAccount("USD");
        // Both funded with 1,000.00 via a single 3-leg journal.
        f.post(f.request("USD", null,
                PostingInstruction.debit(source.getId(), f.money(200_000, "USD")),
                PostingInstruction.credit(a.getId(), f.money(100_000, "USD")),
                PostingInstruction.credit(b.getId(), f.money(100_000, "USD"))));

        List<Callable<PostedEntry>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long amt = 1 + (i % 7) * 101;
            boolean aToB = i % 2 == 0;
            // Mix the leg order too, so lock ordering cannot come from the request shape.
            tasks.add(() -> aToB
                    ? f.move(a.getId(), b.getId(), amt, "USD", null)
                    : f.post(f.request("USD", null,
                            PostingInstruction.credit(a.getId(), f.money(amt, "USD")),
                            PostingInstruction.debit(b.getId(), f.money(amt, "USD")))));
        }
        List<Object> outcomes = race(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(PostedEntry.class));
        long aBal = f.projected(a.getId());
        long bBal = f.projected(b.getId());
        assertThat(aBal + bBal).isEqualTo(200_000);
        assertThat(aBal).isEqualTo(f.derived(a.getId()));
        assertThat(bBal).isEqualTo(f.derived(b.getId()));
        assertThat(f.postingsOn(a.getId())).isEqualTo(101);
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    @Test
    @Timeout(120)
    @DisplayName("The same idempotency key submitted concurrently creates exactly one entry")
    void concurrentDuplicatesProduceOneEffect() throws Exception {
        LedgerAccount cash = f.account(LedgerAccountType.ASSET, "USD");
        LedgerAccount customer = f.customerAccount("USD");
        String key = "race-" + UUID.randomUUID();

        AtomicInteger fresh = new AtomicInteger();
        Set<UUID> entryIds = ConcurrentHashMap.newKeySet();
        List<Callable<PostedEntry>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS * 2; i++) {
            tasks.add(() -> {
                // Each thread builds its own request (own correlation id): as real retries would.
                JournalEntryRequest req = new JournalEntryRequest(
                        com.wbank.ledger.domain.JournalEntryType.ADJUSTMENT, "test journal",
                        java.time.LocalDate.of(2026, 9, 19), f.currency("USD"),
                        List.of(PostingInstruction.debit(cash.getId(), f.money(4_200, "USD")),
                                PostingInstruction.credit(customer.getId(), f.money(4_200, "USD"))),
                        key, RequestContext.forOperation("test.race"));
                PostedEntry p = f.post(req);
                entryIds.add(p.entry().getId());
                if (!p.replayed()) {
                    fresh.incrementAndGet();
                }
                return p;
            });
        }
        List<Object> outcomes = race(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(PostedEntry.class));
        assertThat(fresh.get()).isEqualTo(1);
        assertThat(entryIds).hasSize(1);
        assertThat(f.entriesWithKey(key)).isEqualTo(1);
        assertThat(f.projected(customer.getId())).isEqualTo(4_200);
        assertThat(f.postingsOn(customer.getId())).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("Concurrent reversals of one entry produce exactly one reversal")
    void concurrentReversalsProduceOneReversal() throws Exception {
        LedgerAccount cash = f.account(LedgerAccountType.ASSET, "USD");
        LedgerAccount customer = f.customerAccount("USD");
        UUID id = f.move(cash.getId(), customer.getId(), 900, "USD", null).entry().getId();

        List<Callable<PostedEntry>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> f.tx.execute(s -> f.ledger.reverse(id, "race", RequestContext.forOperation("t"))));
        }
        List<Object> outcomes = race(tasks);

        assertThat(outcomes.stream().filter(o -> o instanceof PostedEntry).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof ConflictException).count()).isEqualTo(7);
        assertThat(f.count("SELECT count(*) FROM journal_entry WHERE reverses_entry_id = ?", id)).isEqualTo(1);
        assertThat(f.projected(customer.getId())).isZero();
    }
}
