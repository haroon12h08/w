package com.wbank.payment;

import static com.wbank.support.LedgerFixtures.causeChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.wbank.account.domain.Account;
import com.wbank.payment.domain.PaymentStatus;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** PostgreSQL enforces the payment and available-funds invariants when the application is bypassed. */
class PaymentDatabaseConstraintsIntegrationTest extends PostgresIntegrationTest {

    @Autowired PaymentFixtures p;
    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;

    Account alice;
    Account bob;

    @BeforeEach
    void accounts() {
        alice = d.activeAccount("USD");
        bob = d.activeAccount("USD");
        d.fund(alice.getId(), 1_000, "USD");
    }

    private String rejection(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).as("expected rejection").isNotNull();
        return causeChain(t);
    }

    @Test
    void aHoldCannotExceedAvailableFunds() {
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update("""
                INSERT INTO funds_reservation (id, account_id, ledger_account_id, currency, amount_minor, status,
                    purpose, reference_id, created_at, expires_at)
                VALUES (?, ?, ?, 'USD', 1001, 'ACTIVE', 'RAW', ?, now(), now() + interval '1 day')""",
                UUID.randomUUID(), alice.getId(), alice.getLedgerAccountId(), UUID.randomUUID()))))
                .contains("available funds of ledger account");
    }

    @Test
    void heldMoneyCannotBeSpentEvenByTheRawLedgerPrimitive() {
        p.pay(alice, bob, 800, "h-" + UUID.randomUUID(), false);
        var cash = f.ledger.requireAccountByCode("1000-CASH-USD");
        // 200 is available, 1,000 is on the ledger: a 300 debit passes the ledger floor but not the hold.
        assertThat(rejection(() -> f.move(alice.getLedgerAccountId(), cash.getId(), 300, "USD", null)))
                .contains("available funds of ledger account");
        assertThat(d.ledgerBalance(alice.getId())).isEqualTo(1_000);
    }

    @Test
    void lifecycleAndSettlementCannotBeForged() {
        PaymentView authorized = p.pay(alice, bob, 100, "a-" + UUID.randomUUID(), false);
        UUID id = authorized.payment().getId();
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update(
                "UPDATE payment SET status = 'SETTLED', settled_at = now() WHERE id = ?", id))))
                .contains("without a settlement record");

        // settle through a journal entry that is not this payment's transfer
        UUID otherEntry = f.jdbc.queryForObject(
                "SELECT id FROM journal_entry WHERE entry_type = 'CASH_DEPOSIT' LIMIT 1", UUID.class);
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> {
            f.jdbc.update("UPDATE funds_reservation SET status = 'CONSUMED', resolved_at = now() WHERE id = ?",
                    authorized.payment().getReservationId());
            f.jdbc.update("INSERT INTO payment_settlement (id, payment_id, journal_entry_id, method, settled_at)"
                    + " VALUES (?, ?, ?, 'INTERNAL_BOOK_TRANSFER', now())", UUID.randomUUID(), id, otherEntry);
            f.jdbc.update("UPDATE payment SET status = 'SETTLED', settled_at = now() WHERE id = ?", id);
        }))).contains("does not debit the debtor and credit the creditor");

        PaymentView settled = p.pay(alice, bob, 100);
        UUID sid = settled.payment().getId();
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE payment SET status = 'AUTHORIZED', settled_at = NULL WHERE id = ?", sid)))
                .contains("cannot move from SETTLED to AUTHORIZED");
        assertThat(rejection(() -> f.jdbc.update(
                "INSERT INTO payment_settlement (id, payment_id, journal_entry_id, method, settled_at)"
                        + " VALUES (?, ?, ?, 'INTERNAL_BOOK_TRANSFER', now())",
                UUID.randomUUID(), sid, otherEntry))).contains("settlement_payment_unique");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE payment_instruction SET amount_minor = 1 WHERE id = ?", settled.instruction().getId())))
                .contains("append-only");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM payment_event WHERE payment_id = ?", sid)))
                .contains("append-only");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE funds_reservation SET status = 'ACTIVE', resolved_at = NULL WHERE id = ?",
                settled.payment().getReservationId()))).contains("cannot move from CONSUMED to ACTIVE");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE funds_reservation SET amount_minor = 1 WHERE id = ?", authorized.payment().getReservationId())))
                .contains("immutable");
        assertThat(p.payments.view(sid, false).payment().getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void aTerminalPaymentKeepsItsHoldReleased() {
        PaymentView v = p.pay(alice, bob, 100, "t-" + UUID.randomUUID(), false);
        p.payments.cancel(v.payment().getId(), "no");
        assertThat(rejection(() -> f.jdbc.update("UPDATE payment SET reason_detail = 'x' WHERE id = ?",
                v.payment().getId()))).contains("is terminal");
    }
}
