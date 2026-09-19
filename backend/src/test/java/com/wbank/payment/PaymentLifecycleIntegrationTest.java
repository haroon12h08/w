package com.wbank.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wbank.account.domain.Account;
import com.wbank.account.funds.FundsService;
import com.wbank.account.funds.ReservationStatus;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.PostingDirection;
import com.wbank.obligation.LoanService;
import com.wbank.payment.domain.PaymentEvent;
import com.wbank.payment.domain.PaymentStatus;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.InsufficientFundsException;
import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentLifecycleIntegrationTest extends PostgresIntegrationTest {

    @Autowired PaymentFixtures p;
    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired FundsService funds;
    @Autowired LoanService loans;

    Account alice;
    Account bob;

    @BeforeEach
    void accounts() {
        alice = d.activeAccount("USD");
        bob = d.activeAccount("USD");
        d.fund(alice.getId(), 10_000, "USD");
    }

    private long journalEntries() {
        return f.count("SELECT count(*) FROM journal_entry WHERE entry_type = 'PAYMENT_TRANSFER'");
    }

    // 1, 9, 11
    @Test
    void successfulPaymentSettlesThroughExactlyOneBalancedEntry() {
        PaymentView v = p.pay(alice, bob, 2_500);

        assertThat(v.payment().getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(v.reservation().getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(v.settlement()).isNotNull();
        var entry = f.ledger.findEntry(v.settlement().getJournalEntryId()).orElseThrow();
        assertThat(entry.getEntryType()).isEqualTo(JournalEntryType.PAYMENT_TRANSFER);
        assertThat(v.settlementPostings()).hasSize(2);
        assertThat(v.settlementPostings().get(0).getLedgerAccountId()).isEqualTo(alice.getLedgerAccountId());
        assertThat(v.settlementPostings().get(0).getDirection()).isEqualTo(PostingDirection.DEBIT);
        assertThat(v.settlementPostings().get(1).getLedgerAccountId()).isEqualTo(bob.getLedgerAccountId());
        assertThat(v.settlementPostings().get(1).getDirection()).isEqualTo(PostingDirection.CREDIT);
        assertThat(v.settlementPostings()).allSatisfy(x -> assertThat(x.getAmountMinor()).isEqualTo(2_500));
        assertThat(d.ledgerBalance(alice.getId())).isEqualTo(7_500);
        assertThat(d.ledgerBalance(bob.getId())).isEqualTo(2_500);
        assertThat(funds.balances(alice.getId()).availableMinor()).isEqualTo(7_500);
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    // 13
    @Test
    void authorisationReducesAvailableButNotLedgerBalanceUntilSettlement() {
        PaymentView v = p.pay(alice, bob, 4_000, "hold-" + UUID.randomUUID(), false);
        assertThat(v.payment().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        FundsService.Balances b = funds.balances(alice.getId());
        assertThat(b.ledgerBalanceMinor()).isEqualTo(10_000);
        assertThat(b.reservedMinor()).isEqualTo(4_000);
        assertThat(b.availableMinor()).isEqualTo(6_000);
        assertThat(d.ledgerBalance(bob.getId())).isZero();

        // Held money cannot be spent by any other debit path.
        assertThatThrownBy(() -> d.deposits.withdrawCash(alice.getId(), d.money(6_001, "USD"), null, null))
                .isInstanceOf(InsufficientFundsException.class);
        PaymentView second = p.pay(alice, bob, 6_001);
        assertThat(second.payment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(second.payment().getReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");

        p.payments.execute(v.payment().getId());
        b = funds.balances(alice.getId());
        assertThat(b.ledgerBalanceMinor()).isEqualTo(6_000);
        assertThat(b.reservedMinor()).isZero();
        assertThat(b.availableMinor()).isEqualTo(6_000);
    }

    // 2, 7
    @Test
    void invalidAccountStateIsRejectedBeforeAnyLedgerPosting() {
        long before = journalEntries();
        d.accounts.freeze(bob.getId());
        PaymentView v = p.pay(alice, bob, 100);
        assertThat(v.payment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(v.payment().getReasonCode()).isEqualTo("CREDITOR_ACCOUNT_NOT_ACTIVE");
        assertThat(v.reservation()).isNull();
        assertThat(v.settlement()).isNull();
        assertThat(journalEntries()).isEqualTo(before);
        assertThat(funds.balances(alice.getId()).availableMinor()).isEqualTo(10_000);

        var loanPosition = loans.propose(d.accounts.require(alice.getId()).getCustomerId(), alice.getId(),
                d.money(5_000, "USD"), 500, 5);
        loans.approve(loanPosition.getId(), "test approval");
        loans.disburse(loanPosition.getId());
        PaymentView toLoan = p.pay(alice, d.accounts.require(loanPosition.getPositionAccountId()), 100);
        assertThat(toLoan.payment().getReasonCode()).isEqualTo("NOT_A_DEPOSIT_ACCOUNT");
    }

    // 3
    @Test
    void currencyMismatchIsRejected() {
        Account eur = d.activeAccount("EUR");
        PaymentView v = p.payments.submit(new PaymentProcessor.SubmitCommand("ccy-" + UUID.randomUUID(),
                alice.getId(), eur.getId(), d.money(100, "USD"), null), true);
        assertThat(v.payment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(v.payment().getReasonCode()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(d.ledgerBalance(eur.getId())).isZero();
    }

    // 4
    @Test
    void insufficientAvailableFundsIsRejectedAndRecorded() {
        PaymentView v = p.pay(alice, bob, 10_001);
        assertThat(v.payment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(v.payment().getReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        PaymentEvent denial = v.events().get(v.events().size() - 1);
        assertThat(denial.getEventType()).isEqualTo("AUTHORIZATION_DENIED");
        assertThat(denial.getDetails()).contains("\"availableMinor\": 10000", "\"requestedMinor\": 10001");
        assertThat(d.ledgerBalance(alice.getId())).isEqualTo(10_000);
    }

    // 5, 12
    @Test
    void duplicateIdempotencyKeyReplaysWithoutASecondEffect() {
        String key = "dup-" + UUID.randomUUID();
        PaymentView first = p.pay(alice, bob, 1_000, key, true);
        PaymentView retry = p.pay(alice, bob, 1_000, key, true);
        PaymentView retry2 = p.pay(alice, bob, 1_000, key, true);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry2.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(d.ledgerBalance(bob.getId())).isEqualTo(1_000);
        assertThat(f.count("SELECT count(*) FROM payment_instruction WHERE idempotency_key = ?", key)).isEqualTo(1);

        assertThatThrownBy(() -> p.pay(alice, bob, 1_001, key, true)).isInstanceOf(ConflictException.class);
        // re-executing a settled payment is a no-op
        p.payments.execute(first.payment().getId());
        assertThat(d.ledgerBalance(bob.getId())).isEqualTo(1_000);
        assertThat(f.count("SELECT count(*) FROM payment_settlement WHERE payment_id = ?", first.payment().getId()))
                .isEqualTo(1);
    }

    @Test
    void rejectedInstructionsAreAlsoIdempotent() {
        String key = "rej-" + UUID.randomUUID();
        PaymentView r1 = p.pay(alice, bob, 99_999, key, true);
        d.fund(alice.getId(), 200_000, "USD"); // now it would be affordable...
        PaymentView r2 = p.pay(alice, bob, 99_999, key, true);
        // ...but the same instruction is the same decision; it is not re-evaluated.
        assertThat(r2.payment().getId()).isEqualTo(r1.payment().getId());
        assertThat(r2.payment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
    }

    @Test
    void cancellationAndExpiryReleaseTheHoldWithoutFinancialEffect() {
        PaymentView c = p.pay(alice, bob, 3_000, "c-" + UUID.randomUUID(), false);
        p.payments.cancel(c.payment().getId(), "customer changed their mind");
        PaymentView cancelled = p.payments.view(c.payment().getId(), false);
        assertThat(cancelled.payment().getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(cancelled.reservation().getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThatThrownBy(() -> p.payments.execute(c.payment().getId())).isInstanceOf(InvalidStateTransitionException.class);

        PaymentView e = p.pay(alice, bob, 3_000, "e-" + UUID.randomUUID(), false);
        assertThat(p.payments.expireDue(Instant.now())).isZero(); // not yet due
        assertThat(p.payments.expireDue(Instant.now().plus(8, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1);
        assertThat(p.payments.view(e.payment().getId(), false).payment().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThatThrownBy(() -> p.payments.execute(e.payment().getId())).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(d.ledgerBalance(bob.getId())).isZero();
        assertThat(funds.balances(alice.getId()).availableMinor()).isEqualTo(10_000);
    }

    // 8 (business failure at execution time)
    @Test
    void accountFrozenAfterAuthorisationFailsExecutionWithoutFinancialEffect() {
        PaymentView v = p.pay(alice, bob, 1_500, "f-" + UUID.randomUUID(), false);
        d.accounts.freeze(bob.getId());
        PaymentView after = p.payments.execute(v.payment().getId());
        assertThat(after.payment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(after.payment().getReasonCode()).isEqualTo("ACCOUNT_NOT_OPERABLE");
        assertThat(after.reservation().getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(after.settlement()).isNull();
        assertThat(d.ledgerBalance(alice.getId())).isEqualTo(10_000);
        assertThat(funds.balances(alice.getId()).availableMinor()).isEqualTo(10_000);
    }

    // 10
    @Test
    void reversalOfASettledPaymentIsAMirrorEntryAndTheOriginalRemains() {
        PaymentView v = p.pay(alice, bob, 2_000);
        UUID settlementEntry = v.settlement().getJournalEntryId();
        PaymentView r = p.payments.reverse(v.payment().getId(), "duplicate invoice");

        assertThat(r.payment().getStatus()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(r.reversalPostings()).hasSize(2);
        assertThat(r.reversalPostings().get(0).getLedgerAccountId()).isEqualTo(alice.getLedgerAccountId());
        assertThat(r.reversalPostings().get(0).getDirection()).isEqualTo(PostingDirection.CREDIT);
        assertThat(f.ledger.findEntry(r.payment().getReversalEntryId()).orElseThrow().getReversesEntryId())
                .isEqualTo(settlementEntry);
        assertThat(f.count("SELECT count(*) FROM posting WHERE journal_entry_id = ?", settlementEntry)).isEqualTo(2);
        assertThat(d.ledgerBalance(alice.getId())).isEqualTo(10_000);
        assertThat(d.ledgerBalance(bob.getId())).isZero();
        assertThatThrownBy(() -> p.payments.reverse(v.payment().getId(), "again"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void aReversalCannotTakeMoneyTheCreditorNoLongerHasAvailable() {
        PaymentView v = p.pay(alice, bob, 2_000);
        Account carol = d.activeAccount("USD");
        p.pay(bob, carol, 1_500); // bob spends most of it
        assertThatThrownBy(() -> p.payments.reverse(v.payment().getId(), "too late"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(p.payments.view(v.payment().getId(), false).payment().getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(d.ledgerBalance(bob.getId())).isEqualTo(500);
    }

    // 14
    @Test
    void provenanceReconstructsTheWholeStory() {
        PaymentView v = p.pay(alice, bob, 700);
        var i = v.instruction();
        assertThat(i.getDebtorAccountId()).isEqualTo(alice.getId());
        assertThat(i.getCreditorAccountId()).isEqualTo(bob.getId());
        assertThat(i.getAmountMinor()).isEqualTo(700);
        assertThat(i.getCurrencyCode()).isEqualTo("USD");
        assertThat(i.getInitiatedBy()).isNotBlank();
        assertThat(i.getReceivedAt()).isNotNull();
        assertThat(i.getCorrelationId()).isNotNull();
        assertThat(v.events()).extracting(PaymentEvent::getEventType)
                .containsExactly("INSTRUCTION_RECEIVED", "VALIDATION_PASSED", "AUTHORIZED", "SETTLED");
        assertThat(v.events()).extracting(PaymentEvent::getSequenceNo).containsExactly(1, 2, 3, 4);
        assertThat(v.events().get(1).getDetails()).contains("DEBTOR_ACCOUNT_ACTIVE", "CURRENCY_MATCHES_CREDITOR");
        assertThat(v.events().get(2).getDetails()).contains("FUNDS_AVAILABLE", "WITHIN_SINGLE_PAYMENT_LIMIT",
                v.reservation().getId().toString());
        assertThat(v.events().get(3).getDetails()).contains(v.settlement().getJournalEntryId().toString(),
                alice.getLedgerAccountId().toString(), bob.getLedgerAccountId().toString());
        // the ledger entry carries the same provenance
        var entry = f.ledger.findEntry(v.settlement().getJournalEntryId()).orElseThrow();
        assertThat(entry.getIdempotencyKey()).isEqualTo("payment.settlement:" + v.payment().getId());
        assertThat(f.count("SELECT count(*) FROM audit_event WHERE aggregate_id = ?", v.payment().getId())).isEqualTo(2);
    }

    @Test
    void singlePaymentLimitIsAnAuthorisationDecision() {
        d.fund(alice.getId(), 200_000_000L, "USD");
        PaymentView v = p.pay(alice, bob, 100_000_001L); // limit: 1,000,000.00 USD
        assertThat(v.payment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(v.payment().getReasonCode()).isEqualTo("LIMIT_EXCEEDED");
    }
}
