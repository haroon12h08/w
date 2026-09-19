package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.HistoricalLoanState;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import com.wbank.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V10 backfills history from existing lending facts. Empty databases cannot show whether that
 * works, so this builds a production-like V9 lending book in an isolated schema (an accruing,
 * partly repaid loan that was briefly delinquent; a declined application; a cancelled one),
 * with fully consistent ledger chains, then upgrades and checks the reconstructed history.
 */
class V10LendingHistoryMigrationTest extends PostgresIntegrationTest {

    static final Instant T0 = Instant.parse("2025-01-10T09:00:00Z");

    JdbcTemplate jdbc;

    /** Posts a balanced journal the way the ledger would: chained postings and updated projections. */
    private UUID post(String type, LocalDate valueDate, Instant booked, Object[]... legs) {
        UUID entry = UUID.randomUUID();
        long debits = 0;
        for (Object[] l : legs) {
            if ("DEBIT".equals(l[1])) {
                debits += (Long) l[2];
            }
        }
        jdbc.update("""
                INSERT INTO journal_entry (id, currency, entry_type, status, description, value_date, booked_at,
                    total_amount_minor, correlation_id, initiated_by, initiator_type, source_operation)
                VALUES (?, 'USD', ?, 'POSTED', 'legacy', ?, ?, ?, ?, 'legacy', 'SYSTEM', 'legacy')""",
                entry, type, valueDate, Timestamp.from(booked), debits, UUID.randomUUID());
        int leg = 0;
        for (Object[] l : legs) {
            Map<String, Object> b = jdbc.queryForMap("""
                    SELECT b.balance_minor, b.last_sequence, a.normal_balance FROM ledger_account_balance b
                      JOIN ledger_account a ON a.id = b.ledger_account_id WHERE b.ledger_account_id = ?""", l[0]);
            long amount = (Long) l[2];
            long after = (Long) b.get("balance_minor") + (l[1].equals(b.get("normal_balance")) ? amount : -amount);
            long seq = (Long) b.get("last_sequence") + 1;
            jdbc.update("""
                    INSERT INTO posting (id, journal_entry_id, entry_leg, ledger_account_id, currency, direction,
                        amount_minor, account_sequence, balance_after_minor, booked_at)
                    VALUES (?, ?, ?, ?, 'USD', ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), entry, leg++, l[0], l[1], amount, seq, after, Timestamp.from(booked));
            jdbc.update("""
                    UPDATE ledger_account_balance SET balance_minor = ?, last_sequence = ?, posting_count = posting_count + 1,
                        total_debits_minor = total_debits_minor + CASE WHEN ? = 'DEBIT' THEN ? ELSE 0 END,
                        total_credits_minor = total_credits_minor + CASE WHEN ? = 'CREDIT' THEN ? ELSE 0 END
                     WHERE ledger_account_id = ?""", after, seq, l[1], amount, l[1], amount, l[0]);
        }
        return entry;
    }

    private static Object[] leg(UUID ledger, String dir, long amount) {
        return new Object[] {ledger, dir, amount};
    }

    private UUID ledgerAccount(String type, String normal, String purpose) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ledger_account (id, code, name, account_type, normal_balance, currency, purpose, status)
                VALUES (?, ?, 'legacy', ?, ?, 'USD', ?, 'ACTIVE')""", id, "L-" + id, type, normal, purpose);
        jdbc.update("INSERT INTO ledger_account_balance (ledger_account_id, currency, min_balance_minor) VALUES (?, 'USD', 0)", id);
        return id;
    }

    private UUID account(UUID customer, UUID party, String product, String family, UUID ledger, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account (id, account_number, customer_id, owner_party_id, product_code, product_family,
                    currency, status, overdraft_limit_minor, opened_at) VALUES (?, ?, ?, ?, ?, ?, 'USD', 'PENDING', 0, ?)""",
                id, number, customer, party, product, family, Timestamp.from(T0));
        if (ledger != null) {
            jdbc.update("UPDATE account SET status = 'ACTIVE', ledger_account_id = ?, activated_at = ? WHERE id = ?",
                    ledger, Timestamp.from(T0), id);
        }
        return id;
    }

    private UUID obligation(String number, UUID party, UUID position, UUID settlement, long principal, int bps, int n,
                            Instant proposed) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO obligation (id, obligation_number, obligation_type, status, creditor_party_id, debtor_party_id,
                    currency, principal_minor, position_account_id, settlement_account_id, proposed_at)
                VALUES (?, ?, 'LOAN', 'PROPOSED', md5('wbank.institution')::uuid, ?, 'USD', ?, ?, ?, ?)""",
                id, number, party, principal, position, settlement, Timestamp.from(proposed));
        jdbc.update("""
                INSERT INTO loan_terms (obligation_id, annual_rate_bps, installment_count, repayment_frequency,
                    amortization_method, allocation_policy, interest_recognition)
                VALUES (?, ?, ?, 'MONTHLY', 'ANNUITY', 'V2_DUE_ONLY_INTEREST_THEN_PRINCIPAL_FULL_PAYOFF', 'ACCRUAL_PERIOD_END')""",
                id, bps, n);
        return id;
    }

    private void decision(UUID obligation, String kind, Instant at) {
        jdbc.update("""
                INSERT INTO loan_decision (id, obligation_id, decision, decided_by, decider_type, correlation_id,
                    decided_at, rationale, evidence) VALUES (?, ?, ?, 'officer-1', 'HUMAN', ?, ?, 'legacy decision', '{}')""",
                UUID.randomUUID(), obligation, kind, UUID.randomUUID(), Timestamp.from(at));
    }

    @Test
    void anExistingLendingBookIsBackfilledIntoAFaithfulHistory() throws Exception {
        String schema = "v10_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("9").load().migrate();
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        UUID party = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID[] loans = new UUID[3];
        long principal = 200_000;
        long i1Principal = 99_500;
        long i1Interest = 1_000;   // 200,000 × 6%/12
        long i2Principal = 100_500;
        long i2Interest = 503;
        LocalDate start = LocalDate.of(2025, 1, 10);
        Instant disbursed = T0.plusSeconds(7_200);
        Instant serviced = Instant.parse("2025-02-11T06:00:00Z");  // accrual of instalment 1 + 1 DPD observed
        Instant repaid = Instant.parse("2025-02-12T10:00:00Z");

        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO party (id, party_type, legal_name, country_code) VALUES (?, 'PERSON', 'Legacy Book', 'GB')", party);
            jdbc.update("INSERT INTO person (party_id) VALUES (?)", party);
            jdbc.update("INSERT INTO customer (id, customer_number, party_id, status, onboarded_at) VALUES (?, 'CV10', ?, 'PENDING', ?)",
                    customer, party, Timestamp.from(T0));
            jdbc.update("UPDATE customer SET status = 'ACTIVE', activated_at = ? WHERE id = ?", Timestamp.from(T0), customer);
            UUID depositLedger = ledgerAccount("LIABILITY", "CREDIT", "CUSTOMER_DEPOSIT");
            UUID deposit = account(customer, party, "CURRENT", "DEPOSIT", depositLedger, "AV10-D");
            UUID cash = jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = '1000-CASH-USD'", UUID.class);
            UUID income = jdbc.queryForObject("SELECT id FROM ledger_account WHERE code = '4000-INTEREST-INCOME-USD'", UUID.class);
            post("CASH_DEPOSIT", start, T0, leg(cash, "DEBIT", 50_000), leg(depositLedger, "CREDIT", 50_000));

            // L1: approved, disbursed, accrued, briefly delinquent, instalment 1 repaid, still ACTIVE
            UUID posLedger = ledgerAccount("ASSET", "DEBIT", "LOAN_RECEIVABLE");
            UUID position = account(customer, party, "TERM_LOAN", "LOAN", null, "AV10-L1");
            loans[0] = obligation("LV10-1", party, position, deposit, principal, 600, 2, T0.plusSeconds(60));
            jdbc.update("UPDATE obligation SET status = 'APPROVED', approved_at = ? WHERE id = ?",
                    Timestamp.from(T0.plusSeconds(3_600)), loans[0]);
            decision(loans[0], "APPROVED", T0.plusSeconds(3_600));
            jdbc.update("UPDATE account SET status = 'ACTIVE', ledger_account_id = ?, activated_at = ? WHERE id = ?",
                    posLedger, Timestamp.from(disbursed), position);
            UUID receivable = ledgerAccount("ASSET", "DEBIT", "LOAN_INTEREST_RECEIVABLE");
            UUID disb = post("LOAN_DISBURSEMENT", start, disbursed, leg(posLedger, "DEBIT", principal),
                    leg(depositLedger, "CREDIT", principal));
            UUID inst1 = UUID.randomUUID();
            UUID inst2 = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO obligation_installment (id, obligation_id, sequence_no, due_date, principal_due_minor, interest_due_minor)
                    VALUES (?, ?, 1, ?, ?, ?), (?, ?, 2, ?, ?, ?)""",
                    inst1, loans[0], start.plusMonths(1), i1Principal, i1Interest,
                    inst2, loans[0], start.plusMonths(2), i2Principal, i2Interest);
            jdbc.update("""
                    UPDATE obligation SET status = 'ACTIVE', start_date = ?, maturity_date = ?, disbursement_entry_id = ?,
                        activated_at = ?, interest_receivable_ledger_account_id = ? WHERE id = ?""",
                    start, start.plusMonths(2), disb, Timestamp.from(disbursed), receivable, loans[0]);

            UUID accrualEntry = post("INTEREST_ACCRUAL", start.plusMonths(1), serviced,
                    leg(receivable, "DEBIT", i1Interest), leg(income, "CREDIT", i1Interest));
            jdbc.update("""
                    INSERT INTO interest_accrual (id, obligation_id, installment_id, amount_minor, accrual_date, journal_entry_id, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), loans[0], inst1, i1Interest, start.plusMonths(1),
                    accrualEntry, Timestamp.from(serviced));
            jdbc.update("""
                    INSERT INTO delinquency_event (id, obligation_id, as_of, from_bucket, to_bucket, days_past_due,
                        overdue_principal_minor, overdue_interest_minor, oldest_unpaid_due_date, recorded_at)
                    VALUES (?, ?, DATE '2025-02-11', 'CURRENT', 'DPD_1_29', 1, ?, ?, DATE '2025-02-10', ?)""",
                    UUID.randomUUID(), loans[0], i1Principal, i1Interest, Timestamp.from(serviced));

            UUID repayEntry = post("LOAN_REPAYMENT", LocalDate.of(2025, 2, 12), repaid,
                    leg(depositLedger, "DEBIT", i1Principal + i1Interest), leg(posLedger, "CREDIT", i1Principal),
                    leg(receivable, "CREDIT", i1Interest));
            UUID repayment = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO obligation_repayment (id, obligation_id, currency, journal_entry_id, idempotency_key, amount_minor,
                        principal_minor, interest_minor, allocation_policy, received_at)
                    VALUES (?, ?, 'USD', ?, NULL, ?, ?, ?, 'V2_DUE_ONLY_INTEREST_THEN_PRINCIPAL_FULL_PAYOFF', ?)""",
                    repayment, loans[0], repayEntry, i1Principal + i1Interest, i1Principal, i1Interest, Timestamp.from(repaid));
            jdbc.update("INSERT INTO repayment_allocation (repayment_id, installment_id, principal_minor, interest_minor) VALUES (?, ?, ?, ?)",
                    repayment, inst1, i1Principal, i1Interest);
            jdbc.update("""
                    INSERT INTO delinquency_event (id, obligation_id, as_of, from_bucket, to_bucket, days_past_due,
                        overdue_principal_minor, overdue_interest_minor, oldest_unpaid_due_date, recorded_at)
                    VALUES (?, ?, DATE '2025-02-12', 'DPD_1_29', 'CURRENT', 0, 0, 0, NULL, ?)""",
                    UUID.randomUUID(), loans[0], Timestamp.from(repaid));

            // L2: declined; L3: cancelled
            loans[1] = obligation("LV10-2", party, account(customer, party, "TERM_LOAN", "LOAN", null, "AV10-L2"), deposit,
                    5_000_000, 900, 12, T0.plusSeconds(120));
            jdbc.update("UPDATE obligation SET status = 'DECLINED', declined_at = ? WHERE id = ?",
                    Timestamp.from(T0.plusSeconds(4_000)), loans[1]);
            decision(loans[1], "DECLINED", T0.plusSeconds(4_000));
            loans[2] = obligation("LV10-3", party, account(customer, party, "TERM_LOAN", "LOAN", null, "AV10-L3"), deposit,
                    10_000, 500, 1, T0.plusSeconds(180));
            jdbc.update("UPDATE obligation SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                    Timestamp.from(T0.plusSeconds(5_000)), loans[2]);
        });

        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").load().migrate();

        ObjectMapper json = new ObjectMapper();
        List<LoanHistoryFold.Event> l1 = jdbc.query("""
                SELECT id, loan_seq, global_seq, event_type, effective_at, recorded_at, payload::text, corrects_event_id, actor
                  FROM lending_event WHERE obligation_id = ? ORDER BY loan_seq""", (rs, i) -> {
            try {
                return new LoanHistoryFold.Event(rs.getObject(1, UUID.class), rs.getInt(2), rs.getLong(3),
                        LendingEventType.valueOf(rs.getString(4)), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6).toInstant(), json.readTree(rs.getString(7)), rs.getObject(8, UUID.class),
                        rs.getString(9));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, loans[0]);

        assertThat(l1).extracting(LoanHistoryFold.Event::type).containsExactly(
                LendingEventType.APPLICATION_RECEIVED, LendingEventType.CREDIT_DECISION,
                LendingEventType.LOAN_DISBURSED, LendingEventType.SCHEDULE_ESTABLISHED,
                LendingEventType.INTEREST_ACCRUED, LendingEventType.DELINQUENCY_CHANGED,
                LendingEventType.REPAYMENT_RECEIVED, LendingEventType.DELINQUENCY_CHANGED);
        assertThat(l1).extracting(LoanHistoryFold.Event::loanSeq).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        // Original times survive: the accrual is effective on the due date but recorded at servicing time.
        assertThat(l1.get(4).effectiveAt()).isEqualTo(Instant.parse("2025-02-10T00:00:00Z"));
        assertThat(l1.get(4).recordedAt()).isEqualTo(serviced);
        assertThat(jdbc.queryForList("SELECT DISTINCT origin FROM lending_event", String.class)).containsExactly("BACKFILL_V10");

        // The migrated history reconstructs the book at any point in time.
        HistoricalLoanState beforeRepayment = LoanHistoryFold.fold(loans[0], l1, serviced, serviced);
        assertThat(beforeRepayment.status()).isEqualTo("ACTIVE");
        assertThat(beforeRepayment.repayments()).isEmpty();
        assertThat(beforeRepayment.interestAccruedMinor()).isEqualTo(i1Interest);
        assertThat(beforeRepayment.derivedDelinquency().daysPastDue()).isEqualTo(1);
        assertThat(beforeRepayment.lastObservedDelinquency().bucket()).isEqualTo("DPD_1_29");
        HistoricalLoanState now = LoanHistoryFold.fold(loans[0], l1, Instant.parse("2025-03-01T00:00:00Z"),
                Instant.parse("2025-03-01T00:00:00Z"));
        assertThat(now.outstandingPrincipalMinor()).isEqualTo(principal - i1Principal)
                .isEqualTo(jdbc.queryForObject("""
                        SELECT b.balance_minor FROM ledger_account_balance b JOIN account a ON a.ledger_account_id = b.ledger_account_id
                         JOIN obligation o ON o.position_account_id = a.id WHERE o.id = ?""", Long.class, loans[0]));
        assertThat(now.accruedUnpaidInterestMinor()).isZero();
        assertThat(now.schedule().get(0).principalPaidMinor()).isEqualTo(i1Principal);
        assertThat(now.decisions()).singleElement().satisfies(dec -> {
            assertThat(dec.decision()).isEqualTo("APPROVED");
            assertThat(dec.policyVersion()).isNull();  // legacy: no policy version was recorded
            assertThat(dec.snapshotId()).isNull();     // legacy: no snapshot is fabricated
        });
        HistoricalLoanState beforeApproval = LoanHistoryFold.fold(loans[0], l1, T0.plusSeconds(3_599), T0.plusSeconds(3_599));
        assertThat(beforeApproval.status()).isEqualTo("PROPOSED");

        assertThat(jdbc.queryForList("SELECT event_type FROM lending_event WHERE obligation_id = ? ORDER BY loan_seq",
                String.class, loans[1])).containsExactly("APPLICATION_RECEIVED", "CREDIT_DECISION");
        assertThat(jdbc.queryForList("SELECT event_type FROM lending_event WHERE obligation_id = ? ORDER BY loan_seq",
                String.class, loans[2])).containsExactly("APPLICATION_RECEIVED", "APPLICATION_CANCELLED");

        // Every obligation's status agrees with its migrated history, and the new rules are live.
        for (UUID id : loans) {
            jdbc.queryForList("SELECT wbank_check_status_matches_history(?)", id);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan_decision WHERE policy_version IS NOT NULL", Long.class)).isZero();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO delinquency_event (id, obligation_id, as_of, from_bucket, to_bucket, days_past_due,
                    overdue_principal_minor, overdue_interest_minor, recorded_at)
                VALUES (?, ?, DATE '2025-03-01', 'CURRENT', 'DPD_1_29', 1, 0, 0, now())""", UUID.randomUUID(), loans[0])))
                .hasStackTraceContaining("without its lending_event");
    }
}
