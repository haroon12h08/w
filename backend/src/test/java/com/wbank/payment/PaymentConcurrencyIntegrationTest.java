package com.wbank.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.wbank.account.domain.Account;
import com.wbank.account.funds.FundsService;
import com.wbank.payment.domain.PaymentStatus;
import com.wbank.platform.error.InsufficientFundsException;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/** 6. Concurrent payment attempts: no double spend, no duplicate effect, no deadlock. */
class PaymentConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired PaymentFixtures p;
    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired FundsService funds;

    private List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> t : tasks) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    return t.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        gate.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> fut : futures) {
            out.add(fut.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return out;
    }

    @Test
    @Timeout(120)
    void racingPaymentsCannotDoubleSpend() throws Exception {
        Account payer = d.activeAccount("USD");
        d.fund(payer.getId(), 10_000, "USD");
        List<Account> payees = List.of(d.activeAccount("USD"), d.activeAccount("USD"), d.activeAccount("USD"));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Account to = payees.get(i % 3);
            tasks.add(() -> p.pay(payer, to, 1_000));
        }
        List<Object> out = race(tasks);
        long settled = out.stream().filter(o -> o instanceof PaymentView v && v.payment().getStatus() == PaymentStatus.SETTLED).count();
        long rejected = out.stream().filter(o -> o instanceof PaymentView v && v.payment().getStatus() == PaymentStatus.REJECTED
                && "INSUFFICIENT_FUNDS".equals(v.payment().getReasonCode())).count();
        assertThat(out).allSatisfy(o -> assertThat(o).isInstanceOf(PaymentView.class));
        assertThat(settled).isEqualTo(10);
        assertThat(rejected).isEqualTo(20);
        assertThat(d.ledgerBalance(payer.getId())).isZero();
        assertThat(payees.stream().mapToLong(a -> d.ledgerBalance(a.getId())).sum()).isEqualTo(10_000);
        assertThat(f.count("SELECT count(*) FROM posting WHERE ledger_account_id = ? AND balance_after_minor < 0",
                payer.getLedgerAccountId())).isZero();
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    @Test
    @Timeout(120)
    void sameKeyConcurrentlyProducesOneInstructionAndOneSettlement() throws Exception {
        Account payer = d.activeAccount("USD");
        Account payee = d.activeAccount("USD");
        d.fund(payer.getId(), 10_000, "USD");
        String key = "race-" + UUID.randomUUID();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> p.pay(payer, payee, 3_000, key, true));
        }
        List<Object> out = race(tasks);
        assertThat(out).allSatisfy(o -> assertThat(o).isInstanceOf(PaymentView.class));
        assertThat(out.stream().map(o -> ((PaymentView) o).payment().getId()).distinct()).hasSize(1);
        assertThat(out.stream().filter(o -> !((PaymentView) o).replayed()).count()).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM payment_instruction WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(d.ledgerBalance(payee.getId())).isEqualTo(3_000);
    }

    @Test
    @Timeout(120)
    void holdsAndWithdrawalsRaceWithoutEverOvercommitting() throws Exception {
        Account payer = d.activeAccount("USD");
        Account payee = d.activeAccount("USD");
        d.fund(payer.getId(), 10_000, "USD");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                tasks.add(() -> p.pay(payer, payee, 1_000, "h-" + UUID.randomUUID(), false)); // hold only
            } else {
                tasks.add(() -> d.deposits.withdrawCash(payer.getId(), d.money(1_000, "USD"), null, null));
            }
        }
        List<Object> out = race(tasks);
        long holds = out.stream().filter(o -> o instanceof PaymentView v && v.payment().getStatus() == PaymentStatus.AUTHORIZED).count();
        long withdrawals = out.stream().filter(o -> o instanceof com.wbank.ledger.PostedEntry).count();
        assertThat(out).allSatisfy(o -> assertThat(o).isInstanceOfAny(PaymentView.class,
                com.wbank.ledger.PostedEntry.class, InsufficientFundsException.class));
        assertThat(holds + withdrawals).isEqualTo(10);
        FundsService.Balances b = funds.balances(payer.getId());
        assertThat(b.ledgerBalanceMinor()).isEqualTo(10_000 - withdrawals * 1_000);
        assertThat(b.reservedMinor()).isEqualTo(holds * 1_000);
        assertThat(b.availableMinor()).isZero();
    }
}
