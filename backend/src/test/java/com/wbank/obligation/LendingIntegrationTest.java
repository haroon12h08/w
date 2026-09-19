package com.wbank.obligation;

import static com.wbank.support.LedgerFixtures.causeChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.wbank.account.domain.Account;
import com.wbank.account.domain.AccountStatus;
import com.wbank.customer.domain.Customer;
import com.wbank.ledger.domain.LedgerAccountStatus;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.obligation.domain.AllocationPolicy;
import com.wbank.obligation.domain.AnnuitySchedule;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.domain.InterestRecognition;
import com.wbank.obligation.domain.LoanDecision;
import com.wbank.obligation.domain.Obligation;
import com.wbank.obligation.domain.ObligationStatus;
import com.wbank.party.PartyService;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.CurrencyMismatchException;
import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.MutableClock;
import com.wbank.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The lending domain against PostgreSQL, with a controllable clock. "Today" starts at
 * 2026-01-15; a 12-month loan disbursed then has instalments due on the 15th of each month.
 *
 * <p>Reference loan: 10,000.00 at 12.00% for 12 months. r = 1% per month,
 * A = 88,849 minor units; instalment 1 = 10,000 interest + 78,849 principal.
 */
@Import(MutableClock.Config.class)
class LendingIntegrationTest extends PostgresIntegrationTest {

    static final long P = 1_000_000;
    static final int BPS = 1_200;
    static final int N = 12;
    static final LocalDate START = LocalDate.of(2026, 1, 15);

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired LoanService loans;
    @Autowired LoanServicingService servicing;
    @Autowired MutableClock clock;

    Customer borrower;
    Account settlement;

    @BeforeEach
    void setUp() {
        clock.reset();
        borrower = d.activeCustomer();
        settlement = d.activeAccount(borrower.getId(), "USD");
        d.fund(settlement.getId(), 500_000, "USD"); // own money to pay interest with
    }

    private Obligation propose(long principal, int n) {
        return loans.propose(borrower.getId(), settlement.getId(), f.money(principal, "USD"), BPS, n);
    }

    private Obligation originate(long principal, int n) {
        Obligation o = propose(principal, n);
        loans.approve(o.getId(), "meets policy");
        return loans.disburse(o.getId());
    }

    /** Moves "today" to the given date (same time of day). */
    private void today(LocalDate date) {
        clock.set(date.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC));
    }

    private long interestIncome() {
        return f.projected(f.ledger.requireAccountByCode("4000-INTEREST-INCOME-USD").getId());
    }

    private String rejection(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).as("expected rejection").isNotNull();
        return causeChain(t);
    }

    private List<String> legs(UUID entryId) {
        return f.jdbc.queryForList("SELECT ledger_account_id, direction, amount_minor FROM posting"
                        + " WHERE journal_entry_id = ? ORDER BY entry_leg", entryId).stream()
                .map(m -> m.get("ledger_account_id") + ":" + m.get("direction") + ":" + m.get("amount_minor")).toList();
    }

    private void assertContractMatchesLedger(UUID obligationId) {
        LoanView v = loans.view(obligationId);
        assertThat(v.ledgerOutstandingPrincipalMinor()).isEqualTo(v.contractualOutstandingPrincipalMinor());
        if (v.ledgerInterestReceivableMinor() != null) {
            assertThat(v.ledgerInterestReceivableMinor()).isEqualTo(v.contractualAccruedUnpaidInterestMinor());
        }
    }

    // ------------------------------------------------------------- 1, 2

    @Test
    void applicationRecordsPartiesAndTermsAndApprovalIsARecordedDecision() {
        long entries = f.count("SELECT count(*) FROM journal_entry");
        Obligation o = propose(P, N);
        LoanView v = loans.view(o.getId());
        assertThat(o.getStatus()).isEqualTo(ObligationStatus.PROPOSED);
        assertThat(o.getCreditorPartyId()).isEqualTo(PartyService.INSTITUTION_PARTY_ID);
        assertThat(o.getDebtorPartyId()).isEqualTo(borrower.getPartyId());
        assertThat(v.terms().getAllocationPolicy()).isEqualTo(AllocationPolicy.V2);
        assertThat(v.terms().getInterestRecognition()).isEqualTo(InterestRecognition.ACCRUAL_PERIOD_END);
        assertThat(v.schedule()).isEmpty();
        assertThat(d.accounts.require(o.getPositionAccountId()).getStatus()).isEqualTo(AccountStatus.PENDING);

        loans.approve(o.getId(), "income verified; DTI 22%");
        v = loans.view(o.getId());
        assertThat(v.obligation().getStatus()).isEqualTo(ObligationStatus.APPROVED);
        assertThat(v.decisions()).singleElement().satisfies(dec -> {
            assertThat(dec.getDecision()).isEqualTo(LoanDecision.Kind.APPROVED);
            assertThat(dec.getRationale()).isEqualTo("income verified; DTI 22%");
            assertThat(dec.getDecidedBy()).isNotBlank();
            assertThat(dec.getEvidence()).contains("\"instalmentMinor\": 88849", "\"annualRateBps\": 1200");
        });
        assertThat(f.count("SELECT count(*) FROM journal_entry")).isEqualTo(entries); // no money yet
    }

    @Test
    void invalidTermsAreRejectedAndNothingIsRecorded() {
        long before = f.count("SELECT count(*) FROM obligation");
        assertThatThrownBy(() -> loans.propose(borrower.getId(), settlement.getId(), f.money(P, "USD"), 10_001, N))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loans.propose(borrower.getId(), settlement.getId(), f.money(P, "USD"), -1, N))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loans.propose(borrower.getId(), settlement.getId(), f.money(P, "USD"), BPS, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loans.propose(borrower.getId(), settlement.getId(), f.money(P, "USD"), BPS, 481))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loans.propose(borrower.getId(), settlement.getId(), f.money(0, "USD"), BPS, N))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loans.propose(borrower.getId(), settlement.getId(), f.money(5, "USD"), BPS, N))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too small");
        Account eur = d.activeAccount(borrower.getId(), "EUR");
        assertThatThrownBy(() -> loans.propose(borrower.getId(), eur.getId(), f.money(P, "USD"), BPS, N))
                .isInstanceOf(CurrencyMismatchException.class);
        Account stranger = d.activeAccount("USD");
        assertThatThrownBy(() -> loans.propose(borrower.getId(), stranger.getId(), f.money(P, "USD"), BPS, N))
                .isInstanceOf(BusinessRuleViolationException.class);
        d.customers.suspend(borrower.getId());
        assertThatThrownBy(() -> propose(P, N)).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(f.count("SELECT count(*) FROM obligation")).isEqualTo(before);
    }

    // ------------------------------------------------------------- 3, 4, 6

    @Test
    void disbursementFixesTheScheduleAndPostsPrincipalOnly() {
        Obligation o = originate(P, N);
        LoanView v = loans.view(o.getId());

        List<AnnuitySchedule.Line> expected = AnnuitySchedule.generate(P, BPS, N, START);
        assertThat(v.schedule()).hasSize(N);
        for (int k = 0; k < N; k++) {
            assertThat(v.schedule().get(k).dueDate()).isEqualTo(expected.get(k).dueDate());
            assertThat(v.schedule().get(k).principalDueMinor()).isEqualTo(expected.get(k).principalMinor());
            assertThat(v.schedule().get(k).interestDueMinor()).isEqualTo(expected.get(k).interestMinor());
        }
        assertThat(v.schedule().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(v.obligation().getMaturityDate()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(v.schedule().get(0).interestDueMinor()).isEqualTo(10_000);  // 1,000,000 × 1%
        assertThat(v.schedule().get(1).interestDueMinor()).isEqualTo(9_212);   // 921,151 × 1% = 9,211.51 -> half-even

        Account position = d.accounts.require(o.getPositionAccountId());
        assertThat(legs(o.getDisbursementEntryId())).containsExactly(
                position.getLedgerAccountId() + ":DEBIT:" + P, settlement.getLedgerAccountId() + ":CREDIT:" + P);
        assertThat(v.ledgerOutstandingPrincipalMinor()).isEqualTo(P);
        assertThat(v.ledgerInterestReceivableMinor()).isZero(); // nothing earned on day 0
        assertThat(f.ledger.requireAccount(o.getInterestReceivableLedgerAccountId()).getAccountType())
                .isEqualTo(LedgerAccountType.ASSET);

        long incomeBefore = interestIncome();
        loans.disburse(o.getId()); // retry: no second loan
        assertThat(f.count("SELECT count(*) FROM journal_entry WHERE idempotency_key = ?",
                "loan.disbursement:" + o.getId())).isEqualTo(1);
        assertThat(interestIncome()).isEqualTo(incomeBefore);
    }

    @Test
    void interestIsEarnedAtEachPeriodEndExactlyOnce() {
        Obligation o = originate(P, N);
        long incomeBefore = interestIncome();

        today(LocalDate.of(2026, 2, 14));
        assertThat(loans.service(o.getId(), LocalDate.of(2026, 2, 14)).accrualsPosted()).isZero();
        today(LocalDate.of(2026, 2, 15));
        assertThat(loans.service(o.getId(), LocalDate.of(2026, 2, 15)).accrualsPosted()).isEqualTo(1);
        assertThat(loans.service(o.getId(), LocalDate.of(2026, 2, 15)).accrualsPosted()).isZero(); // idempotent

        LoanView v = loans.view(o.getId());
        assertThat(v.accruedInterestMinor()).isEqualTo(10_000);
        assertThat(v.ledgerInterestReceivableMinor()).isEqualTo(10_000);
        assertThat(interestIncome() - incomeBefore).isEqualTo(10_000);
        UUID accrualEntry = f.jdbc.queryForObject("SELECT journal_entry_id FROM interest_accrual WHERE obligation_id = ?",
                UUID.class, o.getId());
        assertThat(legs(accrualEntry)).containsExactly(
                o.getInterestReceivableLedgerAccountId() + ":DEBIT:10000",
                f.ledger.requireAccountByCode("4000-INTEREST-INCOME-USD").getId() + ":CREDIT:10000");
        assertThat(f.ledger.findEntry(accrualEntry).orElseThrow().getValueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThatThrownBy(() -> loans.service(o.getId(), LocalDate.of(2026, 3, 15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
    }

    // ------------------------------------------------------------- 5, 7, 8

    @Test
    void partialRepaymentPaysDueInterestFirstAndSettlesTheReceivableNotIncome() {
        Obligation o = originate(P, N);
        today(LocalDate.of(2026, 2, 15));
        long incomeBefore = interestIncome();

        LoanService.RepaymentResult r = loans.repay(o.getId(), f.money(30_000, "USD"), "r-" + UUID.randomUUID());
        assertThat(r.repayment().getInterestMinor()).isEqualTo(10_000);
        assertThat(r.repayment().getPrincipalMinor()).isEqualTo(20_000);
        assertThat(r.repayment().getAllocationPolicy()).isEqualTo(AllocationPolicy.V2);

        Account position = d.accounts.require(o.getPositionAccountId());
        assertThat(legs(r.repayment().getJournalEntryId())).containsExactly(
                settlement.getLedgerAccountId() + ":DEBIT:30000",
                position.getLedgerAccountId() + ":CREDIT:20000",
                o.getInterestReceivableLedgerAccountId() + ":CREDIT:10000");
        // Income was recognised by the accrual (earned), not by the repayment (received).
        assertThat(interestIncome() - incomeBefore).isEqualTo(10_000);
        LoanView v = loans.view(o.getId());
        assertThat(v.ledgerInterestReceivableMinor()).isZero();
        assertThat(v.contractualOutstandingPrincipalMinor()).isEqualTo(P - 20_000);
        assertThat(v.schedule().get(0).fullyPaid()).isFalse();
        assertThat(v.quote().dueNowMinor()).isEqualTo(58_849);
        assertContractMatchesLedger(o.getId());
    }

    // ------------------------------------------------------------- 9

    @Test
    void overpaymentAndPartialPrepaymentAreRefusedWithoutEffect() {
        Obligation o = originate(P, N);
        assertThat(rejection(() -> loans.repay(o.getId(), f.money(100, "USD"), null))).contains("Nothing is due");

        today(LocalDate.of(2026, 2, 15));
        LoanView v = loans.view(o.getId());
        long due = v.quote().dueNowMinor();
        long payoff = v.quote().payoffMinor();
        assertThat(due).isEqualTo(88_849);
        assertThat(payoff).isEqualTo(10_000 + P); // instalment-1 interest + all principal

        assertThatThrownBy(() -> loans.repay(o.getId(), f.money(due + 1, "USD"), null))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("partial prepayment");
        assertThatThrownBy(() -> loans.repay(o.getId(), f.money(payoff + 1, "USD"), null))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("exceeds");
        assertThat(f.count("SELECT count(*) FROM obligation_repayment WHERE obligation_id = ?", o.getId())).isZero();
        assertContractMatchesLedger(o.getId());
    }

    // ------------------------------------------------------------- 10, 11

    @Test
    void missedPaymentsBecomeObservableDelinquencyWithHistory() {
        Obligation o = originate(P, N);

        today(LocalDate.of(2026, 2, 15));
        assertThat(loans.service(o.getId(), LocalDate.of(2026, 2, 15)).delinquency().bucket())
                .isEqualTo(Delinquency.Bucket.CURRENT); // due today is not late

        today(LocalDate.of(2026, 2, 16));
        LoanService.ServicingResult day1 = loans.service(o.getId(), LocalDate.of(2026, 2, 16));
        assertThat(day1.delinquency().daysPastDue()).isEqualTo(1);
        assertThat(day1.delinquency().bucket()).isEqualTo(Delinquency.Bucket.DPD_1_29);
        assertThat(day1.delinquency().overdueInterestMinor()).isEqualTo(10_000);
        assertThat(day1.delinquency().overduePrincipalMinor()).isEqualTo(78_849);
        assertThat(day1.bucketChanged()).isTrue();

        today(LocalDate.of(2026, 3, 18));
        // The sweep services the whole book in this shared database; each loan runs in its own
        // transaction, so another test's deliberately corrupted loan cannot block this one.
        var sweep = servicing.run(LocalDate.of(2026, 3, 18));
        assertThat(sweep.loansServiced()).isPositive();
        LoanView v = loans.view(o.getId());
        assertThat(v.delinquency().daysPastDue()).isEqualTo(31);
        assertThat(v.delinquency().bucket()).isEqualTo(Delinquency.Bucket.DPD_30_59);
        assertThat(v.delinquency().overdueInstallments()).isEqualTo(2);
        assertThat(v.accruedInterestMinor()).isEqualTo(10_000 + 9_212);

        // Catch up: paying everything due returns the loan to CURRENT, and history shows the path.
        loans.repay(o.getId(), f.money(v.quote().dueNowMinor(), "USD"), "catch-up-" + UUID.randomUUID());
        v = loans.view(o.getId());
        assertThat(v.delinquency().bucket()).isEqualTo(Delinquency.Bucket.CURRENT);
        assertThat(v.delinquencyHistory()).extracting(e -> e.getFromBucket() + "->" + e.getToBucket())
                .containsExactly("CURRENT->DPD_1_29", "DPD_1_29->DPD_30_59", "DPD_30_59->CURRENT");
        assertContractMatchesLedger(o.getId());
    }

    // ------------------------------------------------------------- 12

    @Test
    void payingEveryInstalmentOnTimeSettlesAndClosesTheLoan() {
        Obligation o = originate(300_000, 3);
        long incomeBefore = interestIncome();
        LoanView v = loans.view(o.getId());
        long totalInterest = v.schedule().stream().mapToLong(LoanView.InstallmentView::interestDueMinor).sum();

        for (LoanView.InstallmentView i : v.schedule()) {
            today(i.dueDate());
            loans.repay(o.getId(), f.money(i.principalDueMinor() + i.interestDueMinor(), "USD"), "k-" + i.sequence() + UUID.randomUUID());
        }
        v = loans.view(o.getId());
        assertThat(v.obligation().getStatus()).isEqualTo(ObligationStatus.SETTLED);
        assertThat(v.schedule()).allMatch(LoanView.InstallmentView::fullyPaid);
        assertThat(v.ledgerOutstandingPrincipalMinor()).isZero();
        assertThat(v.ledgerInterestReceivableMinor()).isZero();
        assertThat(interestIncome() - incomeBefore).isEqualTo(totalInterest);
        assertThat(d.accounts.require(o.getPositionAccountId()).getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(f.ledger.requireAccount(o.getInterestReceivableLedgerAccountId()).getStatus())
                .isEqualTo(LedgerAccountStatus.CLOSED);
        assertThatThrownBy(() -> loans.repay(o.getId(), f.money(1, "USD"), null))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    @Test
    void earlySettlementPaysPrincipalAndEarnedInterestAndWaivesTheRest() {
        Obligation o = originate(P, N);
        today(LocalDate.of(2026, 2, 25)); // instalment 1 is due (and 10 days late); 2..12 are future
        LoanView v = loans.view(o.getId());
        long payoff = v.quote().payoffMinor();
        assertThat(payoff).isEqualTo(P + 10_000);
        long scheduledInterest = v.scheduledInterestOutstandingMinor();
        long incomeBefore = interestIncome();

        LoanService.RepaymentResult r = loans.repay(o.getId(), f.money(payoff, "USD"), "payoff-" + UUID.randomUUID());
        assertThat(r.settledLoan()).isTrue();
        v = loans.view(o.getId());
        assertThat(v.obligation().getStatus()).isEqualTo(ObligationStatus.SETTLED);
        long waived = v.schedule().stream().mapToLong(LoanView.InstallmentView::interestWaivedMinor).sum();
        assertThat(waived).isEqualTo(scheduledInterest - 10_000);
        assertThat(v.schedule().get(0).interestWaivedMinor()).isZero();
        assertThat(v.schedule().subList(1, N)).allSatisfy(i -> {
            assertThat(i.interestAccrued()).isFalse();
            assertThat(i.interestWaivedMinor()).isEqualTo(i.interestDueMinor());
        });
        // Only earned interest ever reached income.
        assertThat(interestIncome() - incomeBefore).isEqualTo(10_000);
        assertThat(v.ledgerInterestReceivableMinor()).isZero();
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    // ------------------------------------------------------------- 13

    @Test
    void invalidTransitionsAndTheDefaultRule() {
        Obligation proposed = propose(P, N);
        assertThatThrownBy(() -> loans.disburse(proposed.getId())).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> loans.repay(proposed.getId(), f.money(1, "USD"), null))
                .isInstanceOf(InvalidStateTransitionException.class);
        loans.approve(proposed.getId(), "ok");
        assertThatThrownBy(() -> loans.approve(proposed.getId(), "again")).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> loans.decline(proposed.getId(), "late")).isInstanceOf(InvalidStateTransitionException.class);
        loans.cancel(proposed.getId());
        assertThat(d.accounts.require(proposed.getPositionAccountId()).getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThatThrownBy(() -> loans.disburse(proposed.getId())).isInstanceOf(InvalidStateTransitionException.class);

        Obligation declined = propose(P, N);
        loans.decline(declined.getId(), "insufficient income");
        assertThat(loans.view(declined.getId()).decisions()).extracting(LoanDecision::getDecision)
                .containsExactly(LoanDecision.Kind.DECLINED);

        Obligation o = originate(P, N);
        assertThatThrownBy(() -> loans.cancel(o.getId())).isInstanceOf(InvalidStateTransitionException.class);
        today(LocalDate.of(2026, 3, 20)); // 33 days past due
        assertThatThrownBy(() -> loans.declareDefault(o.getId(), "no contact"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("33 days past due");
        today(LocalDate.of(2026, 5, 16)); // 90 days past 2026-02-15
        loans.declareDefault(o.getId(), "90 days past due; borrower unresponsive");
        LoanView v = loans.view(o.getId());
        assertThat(v.obligation().getStatus()).isEqualTo(ObligationStatus.DEFAULTED);
        assertThat(v.decisions()).extracting(LoanDecision::getDecision).endsWith(LoanDecision.Kind.DEFAULT_DECLARED);
        assertThat(v.decisions().get(v.decisions().size() - 1).getEvidence()).contains("\"daysPastDue\": 90");
        assertThatThrownBy(() -> loans.declareDefault(o.getId(), "again")).isInstanceOf(InvalidStateTransitionException.class);

        // A defaulted loan still accepts money, and settles when fully satisfied.
        d.fund(settlement.getId(), 1_000_000, "USD");
        loans.repay(o.getId(), f.money(v.quote().payoffMinor(), "USD"), "recovery-" + UUID.randomUUID());
        assertThat(loans.view(o.getId()).obligation().getStatus()).isEqualTo(ObligationStatus.SETTLED);
    }

    // ------------------------------------------------------------- 14, 15

    @Test
    void concurrentRepaymentsNeverExceedWhatIsDue() throws Exception {
        Obligation o = originate(P, N);
        today(LocalDate.of(2026, 2, 15));
        loans.service(o.getId(), LocalDate.of(2026, 2, 15));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                gate.await();
                try {
                    return loans.repay(o.getId(), f.money(10_000, "USD"), "c-" + UUID.randomUUID());
                } catch (RuntimeException e) {
                    return e;
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        for (Future<Object> r : results) {
            Object x = r.get();
            if (x instanceof LoanService.RepaymentResult) {
                ok++;
            } else {
                assertThat(x).isInstanceOf(BusinessRuleViolationException.class);
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(8); // 88,849 due: eight payments of 10,000 fit, the ninth would prepay
        LoanView v = loans.view(o.getId());
        assertThat(v.repayments().stream().mapToLong(x -> x.getAmountMinor()).sum()).isEqualTo(80_000);
        assertThat(v.quote().dueNowMinor()).isEqualTo(8_849);
        assertContractMatchesLedger(o.getId());
    }

    @Test
    void repaymentsAreIdempotent() {
        Obligation o = originate(P, N);
        today(LocalDate.of(2026, 2, 15));
        String key = "idem-" + UUID.randomUUID();
        var first = loans.repay(o.getId(), f.money(5_000, "USD"), key);
        var again = loans.repay(o.getId(), f.money(5_000, "USD"), key);
        assertThat(again.replayed()).isTrue();
        assertThat(again.repayment().getId()).isEqualTo(first.repayment().getId());
        assertThatThrownBy(() -> loans.repay(o.getId(), f.money(5_001, "USD"), key)).isInstanceOf(ConflictException.class);
        assertThat(f.count("SELECT count(*) FROM obligation_repayment WHERE obligation_id = ?", o.getId())).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM journal_entry WHERE entry_type = 'LOAN_REPAYMENT'"
                + " AND idempotency_key = ?", "loan.repayment:" + key)).isEqualTo(1);
    }

    // ------------------------------------------------------------- 16

    @Test
    void aFailedOperationLeavesNeitherLoanNorLedgerState() {
        Obligation o = propose(P, N);
        loans.approve(o.getId(), "ok");
        long entries = f.count("SELECT count(*) FROM journal_entry");
        long ledgerAccounts = f.count("SELECT count(*) FROM ledger_account");

        assertThatThrownBy(() -> f.tx.executeWithoutResult(s -> {
            loans.disburse(o.getId());
            throw new IllegalStateException("crash after the ledger posting, same unit of work");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(loans.view(o.getId()).obligation().getStatus()).isEqualTo(ObligationStatus.APPROVED);
        assertThat(d.accounts.require(o.getPositionAccountId()).getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(f.count("SELECT count(*) FROM obligation_installment WHERE obligation_id = ?", o.getId())).isZero();
        assertThat(f.count("SELECT count(*) FROM journal_entry")).isEqualTo(entries);
        assertThat(f.count("SELECT count(*) FROM ledger_account")).isEqualTo(ledgerAccounts);

        loans.disburse(o.getId());
        today(LocalDate.of(2026, 2, 15));
        long incomeBefore = interestIncome();
        assertThatThrownBy(() -> f.tx.executeWithoutResult(s -> {
            loans.repay(o.getId(), f.money(20_000, "USD"), "k-" + UUID.randomUUID()); // accrues, then pays
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        LoanView v = loans.view(o.getId());
        assertThat(v.repayments()).isEmpty();
        assertThat(v.accruedInterestMinor()).isZero(); // the accrual inside the failed unit is gone too
        assertThat(interestIncome()).isEqualTo(incomeBefore);
        assertThat(v.ledgerOutstandingPrincipalMinor()).isEqualTo(P);
    }

    // ------------------------------------------------------------- 17

    @Test
    void contractualAndLedgerPositionsReconcileAndCannotBeForced() {
        Obligation a = originate(P, N);
        Obligation b = originate(250_000, 6);
        today(LocalDate.of(2026, 3, 15));
        servicing.run(LocalDate.of(2026, 3, 15));
        loans.repay(a.getId(), f.money(50_000, "USD"), null);
        loans.repay(b.getId(), f.money(loans.view(b.getId()).quote().dueNowMinor(), "USD"), null);

        for (Obligation o : List.of(a, b)) {
            assertContractMatchesLedger(o.getId());
        }
        // Σ accrual records == Σ accrual journals, loan by loan
        for (Obligation o : List.of(a, b)) {
            assertThat(f.count("SELECT coalesce(sum(p.amount_minor), 0) FROM interest_accrual x"
                    + " JOIN posting p ON p.journal_entry_id = x.journal_entry_id AND p.direction = 'DEBIT'"
                    + " WHERE x.obligation_id = ?", o.getId()))
                    .isEqualTo(loans.view(o.getId()).accruedInterestMinor());
        }
        assertThat(f.reconciliation.isConsistent()).isTrue();

        // An accrual record without its ledger effect is refused at COMMIT.
        UUID inst = f.jdbc.queryForObject("SELECT id FROM obligation_installment WHERE obligation_id = ? AND sequence_no = 3",
                UUID.class, a.getId());
        long interest3 = f.count("SELECT interest_due_minor FROM obligation_installment WHERE id = ?", inst);
        UUID anyEntry = a.getDisbursementEntryId();
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update("""
                INSERT INTO interest_accrual (id, obligation_id, installment_id, amount_minor, accrual_date,
                    journal_entry_id, recorded_at) VALUES (?, ?, ?, ?, DATE '2026-04-15', ?, now())""",
                UUID.randomUUID(), a.getId(), inst, interest3, anyEntry))))
                .contains("disagrees with its ledger receivable");
        // Interest cannot be accrued before its period ends.
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update("""
                INSERT INTO interest_accrual (id, obligation_id, installment_id, amount_minor, accrual_date,
                    journal_entry_id, recorded_at) VALUES (?, ?, ?, ?, DATE '2026-03-01', ?, now())""",
                UUID.randomUUID(), a.getId(), inst, interest3, anyEntry))))
                .contains("inconsistent with its schedule");
        // Moving the receivable through the raw ledger primitive is detected by the next loan operation.
        var equity = f.account(LedgerAccountType.EQUITY, "USD");
        f.post(f.request("USD", null, PostingInstruction.debit(a.getInterestReceivableLedgerAccountId(), f.money(1, "USD")),
                PostingInstruction.credit(equity.getId(), f.money(1, "USD"))));
        assertThatThrownBy(() -> loans.service(a.getId(), LocalDate.of(2026, 3, 15)))
                .hasMessageContaining("interest receivable");
        assertThat(rejection(() -> f.jdbc.update("UPDATE interest_accrual SET amount_minor = 1 WHERE obligation_id = ?",
                b.getId()))).contains("append-only");
        assertThat(rejection(() -> f.jdbc.update("UPDATE obligation SET status = 'APPROVED' WHERE id = ?",
                b.getId()))).contains("cannot move from ACTIVE to APPROVED");
    }

    @Test
    void anOpenLoanBlocksClosingItsAccountsAndTheRelationship() {
        Obligation o = originate(120_000, 3);
        assertThatThrownBy(() -> d.accounts.close(settlement.getId())).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> d.customers.close(borrower.getId())).isInstanceOf(BusinessRuleViolationException.class);
        today(LocalDate.of(2026, 2, 15));
        loans.repay(o.getId(), f.money(loans.view(o.getId()).quote().payoffMinor(), "USD"), null);
        d.deposits.withdrawCash(settlement.getId(), f.money(d.ledgerBalance(settlement.getId()), "USD"), null, null);
        d.accounts.close(settlement.getId());
        d.customers.close(borrower.getId());
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }
}
