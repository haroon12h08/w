package com.wbank.ledger;

import static com.wbank.support.LedgerFixtures.causeChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 10. PostgreSQL enforces the core invariants on its own.
 *
 * <p>Every test here BYPASSES the application and writes SQL directly, as a defective
 * service, a migration script, or an operator with a psql prompt could. {@link RawWriter}
 * writes a journal exactly the way a correct writer must (entry, chained postings, updated
 * projection) and each test introduces exactly one defect, then asserts that the specific
 * constraint responsible rejected it and that nothing was persisted.
 */
class LedgerDatabaseConstraintsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    LedgerFixtures f;

    LedgerAccount cash;
    LedgerAccount customer;

    @BeforeEach
    void accounts() {
        cash = f.account(LedgerAccountType.ASSET, "USD");
        customer = f.customerAccount("USD");
    }

    record Leg(UUID account, String direction, long amount) {}

    /** A deliberately low-level journal writer. Knobs introduce one defect at a time. */
    class RawWriter {
        UUID entryId = UUID.randomUUID();
        String currency = "USD";
        String postingCurrency = "USD";
        Long declaredTotal;          // default: sum of debits
        long balanceAfterSkew = 0;   // corrupts balance_after_minor of the first leg
        boolean updateProjection = true;
        String idempotencyKey;
        String fingerprint;

        void write(Leg... legs) {
            f.tx.executeWithoutResult(s -> {
                long debits = List.of(legs).stream().filter(l -> l.direction.equals("DEBIT")).mapToLong(Leg::amount).sum();
                Timestamp booked = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
                f.jdbc.update("""
                        INSERT INTO journal_entry (id, currency, entry_type, status, description, value_date, booked_at,
                            total_amount_minor, correlation_id, initiated_by, initiator_type, source_operation,
                            idempotency_key, request_fingerprint)
                        VALUES (?, ?, 'ADJUSTMENT', 'POSTED', 'raw sql', CURRENT_DATE, ?, ?, ?, 'psql', 'HUMAN', 'raw',
                            ?, ?)""",
                        entryId, currency, booked, declaredTotal != null ? declaredTotal : debits, UUID.randomUUID(),
                        idempotencyKey, fingerprint);
                int leg = 0;
                for (Leg l : legs) {
                    Map<String, Object> b = f.jdbc.queryForMap("""
                            SELECT b.balance_minor, b.last_sequence, a.normal_balance
                              FROM ledger_account_balance b JOIN ledger_account a ON a.id = b.ledger_account_id
                             WHERE b.ledger_account_id = ? FOR UPDATE OF b""", l.account);
                    long effect = l.direction.equals(b.get("normal_balance")) ? l.amount : -l.amount;
                    long after = (Long) b.get("balance_minor") + effect + (leg == 0 ? balanceAfterSkew : 0);
                    long seq = (Long) b.get("last_sequence") + 1;
                    f.jdbc.update("""
                            INSERT INTO posting (id, journal_entry_id, entry_leg, ledger_account_id, currency, direction,
                                amount_minor, account_sequence, balance_after_minor, booked_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                            UUID.randomUUID(), entryId, leg++, l.account, postingCurrency, l.direction, l.amount,
                            seq, after, booked);
                    if (updateProjection) {
                        f.jdbc.update("""
                                UPDATE ledger_account_balance
                                   SET balance_minor = ?, last_sequence = ?, posting_count = posting_count + 1,
                                       total_debits_minor  = total_debits_minor  + CASE WHEN ? = 'DEBIT'  THEN ? ELSE 0 END,
                                       total_credits_minor = total_credits_minor + CASE WHEN ? = 'CREDIT' THEN ? ELSE 0 END
                                 WHERE ledger_account_id = ?""",
                                after - (leg == 1 ? balanceAfterSkew : 0), seq,
                                l.direction, l.amount, l.direction, l.amount, l.account);
                    }
                }
            });
        }

        boolean persisted() {
            return f.count("SELECT count(*) FROM journal_entry WHERE id = ?", entryId) > 0;
        }
    }

    private String rejection(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).as("the database should have rejected the write").isNotNull();
        return causeChain(t);
    }

    @Test
    @DisplayName("control: a correct raw journal is accepted (so the rejections below are specific)")
    void correctRawJournalIsAccepted() {
        RawWriter w = new RawWriter();
        w.write(new Leg(cash.getId(), "DEBIT", 500), new Leg(customer.getId(), "CREDIT", 500));
        assertThat(w.persisted()).isTrue();
        assertThat(f.projected(customer.getId())).isEqualTo(500);
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    @Test
    void unbalancedJournalIsRejectedAtCommit() {
        RawWriter w = new RawWriter();
        w.declaredTotal = 500L;
        assertThat(rejection(() -> w.write(new Leg(cash.getId(), "DEBIT", 500), new Leg(customer.getId(), "CREDIT", 499))))
                .contains("is unbalanced by 1 minor units");
        assertThat(w.persisted()).isFalse();
        assertThat(f.postingsOn(cash.getId())).isZero();
    }

    @Test
    void journalWithOnePostingOrNoneIsRejectedAtCommit() {
        RawWriter one = new RawWriter();
        assertThat(rejection(() -> one.write(new Leg(cash.getId(), "DEBIT", 500))))
                .contains("at least 2 are required");
        assertThat(one.persisted()).isFalse();

        RawWriter none = new RawWriter();
        none.declaredTotal = 500L;
        assertThat(rejection(none::write)).contains("has 0 posting(s)");
        assertThat(none.persisted()).isFalse();
    }

    @Test
    void declaredTotalMustEqualTheDebits() {
        RawWriter w = new RawWriter();
        w.declaredTotal = 999L;
        assertThat(rejection(() -> w.write(new Leg(cash.getId(), "DEBIT", 500), new Leg(customer.getId(), "CREDIT", 500))))
                .contains("declares 999 but its debits total 500");
    }

    @Test
    void zeroOrNegativeAmountsAreRejected() {
        assertThat(rejection(() -> new RawWriter().write(
                new Leg(cash.getId(), "DEBIT", 0), new Leg(customer.getId(), "CREDIT", 0))))
                .contains("journal_entry_total_amount_positive");
        RawWriter neg = new RawWriter();
        neg.declaredTotal = 5L;
        assertThat(rejection(() -> neg.write(
                new Leg(cash.getId(), "DEBIT", -5), new Leg(customer.getId(), "CREDIT", -5))))
                .contains("posting_amount_positive");
    }

    @Test
    void postingCurrencyMustMatchTheAccountAndTheEntry() {
        RawWriter w = new RawWriter();
        w.currency = "EUR";
        w.postingCurrency = "EUR";
        assertThat(rejection(() -> w.write(new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5))))
                .contains("posting_currency_matches_account");

        RawWriter mixed = new RawWriter();
        mixed.postingCurrency = "EUR";
        assertThat(rejection(() -> mixed.write(new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5))))
                .contains("posting_currency_matches_");
        assertThat(rejection(() -> f.jdbc.update("UPDATE ledger_account SET currency = 'EUR' WHERE id = ?", cash.getId())))
                .contains("identity (code/type/currency) is immutable");
    }

    @Test
    void aForgedRunningBalanceIsRejected() {
        RawWriter w = new RawWriter();
        w.balanceAfterSkew = 1_000_000;
        assertThat(rejection(() -> w.write(new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5))))
                .contains("claims balance_after");
        assertThat(w.persisted()).isFalse();
    }

    @Test
    void postingsThatAreNotReflectedInTheProjectionAreRejected() {
        RawWriter w = new RawWriter();
        w.updateProjection = false;
        assertThat(rejection(() -> w.write(new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5))))
                .contains("is not reflected in ledger_account_balance");
    }

    @Test
    void aBalanceCannotBeSetWithoutPostingsThatJustifyIt() {
        new RawWriter().write(new Leg(cash.getId(), "DEBIT", 5_000), new Leg(customer.getId(), "CREDIT", 5_000));

        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update(
                "UPDATE ledger_account_balance SET balance_minor = 1000000 WHERE ledger_account_id = ?",
                customer.getId()))))
                .contains("does not match its ledger");
        assertThat(rejection(() -> f.jdbc.update(
                "DELETE FROM ledger_account_balance WHERE ledger_account_id = ?", customer.getId())))
                .contains("cannot be deleted");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE ledger_account_balance SET last_sequence = 0, posting_count = 0, balance_minor = 0,"
                        + " total_credits_minor = 0 WHERE ledger_account_id = ?", customer.getId())))
                .contains("may only move forward");
        assertThat(f.projected(customer.getId())).isEqualTo(5_000);
    }

    @Test
    void aCustomerAccountCannotBeOverdrawnEvenBySql() {
        new RawWriter().write(new Leg(cash.getId(), "DEBIT", 100), new Leg(customer.getId(), "CREDIT", 100));
        RawWriter w = new RawWriter();
        assertThat(rejection(() -> w.write(new Leg(customer.getId(), "DEBIT", 101), new Leg(cash.getId(), "CREDIT", 101))))
                .contains("balance_respects_floor");
        assertThat(f.projected(customer.getId())).isEqualTo(100);
    }

    @Test
    void postingToANonActiveAccountIsRejected() {
        f.jdbc.update("UPDATE ledger_account SET status = 'FROZEN' WHERE id = ?", customer.getId());
        assertThat(rejection(() -> new RawWriter().write(
                new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5))))
                .contains("is not ACTIVE");
    }

    @Test
    void postedHistoryCannotBeModifiedOrDeleted() {
        RawWriter w = new RawWriter();
        w.write(new Leg(cash.getId(), "DEBIT", 700), new Leg(customer.getId(), "CREDIT", 700));
        UUID postingId = f.jdbc.queryForObject(
                "SELECT id FROM posting WHERE journal_entry_id = ? AND entry_leg = 0", UUID.class, w.entryId);

        assertThat(rejection(() -> f.jdbc.update("UPDATE posting SET amount_minor = 1 WHERE id = ?", postingId)))
                .contains("append-only; UPDATE is not permitted");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM posting WHERE id = ?", postingId)))
                .contains("append-only; DELETE is not permitted");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE journal_entry SET total_amount_minor = 1 WHERE id = ?", w.entryId)))
                .contains("is immutable");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE journal_entry SET description = 'nothing to see here' WHERE id = ?", w.entryId)))
                .contains("is immutable");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM journal_entry WHERE id = ?", w.entryId)))
                .contains("DELETE is not permitted");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE journal_entry SET status = 'REVERSED', reversed_by_entry_id = ? WHERE id = ?",
                w.entryId, w.entryId)))
                .contains("does not reverse it");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM ledger_account WHERE id = ?", cash.getId())))
                .contains("cannot be deleted");
        assertThat(rejection(() -> f.jdbc.update("UPDATE currency SET minor_unit = 3 WHERE code = 'USD'")))
                .contains("immutable reference data");

        assertThat(f.count("SELECT amount_minor FROM posting WHERE id = ?", postingId)).isEqualTo(700);
    }

    @Test
    void anIdempotencyKeyCanBeUsedOnceAndMustCarryAFingerprint() {
        String key = "raw-" + UUID.randomUUID();
        String fp = "a".repeat(64);
        RawWriter first = new RawWriter();
        first.idempotencyKey = key;
        first.fingerprint = fp;
        first.write(new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5));

        RawWriter dup = new RawWriter();
        dup.idempotencyKey = key;
        dup.fingerprint = fp;
        assertThat(rejection(() -> dup.write(new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5))))
                .contains("journal_entry_idempotency_key_unique");

        RawWriter noFp = new RawWriter();
        noFp.idempotencyKey = "raw-" + UUID.randomUUID();
        assertThat(rejection(() -> noFp.write(new Leg(cash.getId(), "DEBIT", 5), new Leg(customer.getId(), "CREDIT", 5))))
                .contains("journal_entry_fingerprint_with_key");
        assertThat(f.projected(customer.getId())).isEqualTo(5);
    }
}
