package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.wbank.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression test: V9 first failed on a database that already held an ACTIVE loan
 * ("cannot ALTER TABLE obligation because it has pending trigger events"). Empty test
 * databases cannot reveal that, so this builds a real, internally consistent pre-V9 loan
 * in an isolated schema at V8, then upgrades.
 */
class V9LegacyLoanMigrationTest extends PostgresIntegrationTest {

    @Test
    void aDisbursedCashBasisLoanSurvivesTheUpgradeUnchanged() {
        String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("8").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        UUID party = UUID.randomUUID(), customer = UUID.randomUUID();
        UUID deposit = UUID.randomUUID(), depositLedger = UUID.randomUUID();
        UUID loanAcct = UUID.randomUUID(), loanLedger = UUID.randomUUID();
        UUID obligation = UUID.randomUUID(), entry = UUID.randomUUID();
        long p = 100_000;
        Timestamp booked = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        LocalDate start = LocalDate.of(2026, 1, 15);

        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO party (id, party_type, legal_name, country_code) VALUES (?, 'PERSON', 'Legacy Borrower', 'GB')", party);
            jdbc.update("INSERT INTO person (party_id) VALUES (?)", party);
            jdbc.update("INSERT INTO customer (id, customer_number, party_id, status, onboarded_at) VALUES (?, 'CL1', ?, 'PENDING', now())", customer, party);
            jdbc.update("UPDATE customer SET status = 'ACTIVE', activated_at = now() WHERE id = ?", customer);
            for (Object[] a : new Object[][] {{deposit, depositLedger, "CURRENT", "DEPOSIT", "LIABILITY", "CREDIT", "CUSTOMER_DEPOSIT", "AL1"},
                                              {loanAcct, loanLedger, "TERM_LOAN", "LOAN", "ASSET", "DEBIT", "LOAN_RECEIVABLE", "AL2"}}) {
                jdbc.update("""
                        INSERT INTO account (id, account_number, customer_id, owner_party_id, product_code, product_family,
                            currency, status, overdraft_limit_minor, opened_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'USD', 'PENDING', 0, now())""", a[0], a[7], customer, party, a[2], a[3]);
                jdbc.update("""
                        INSERT INTO ledger_account (id, code, name, account_type, normal_balance, currency, purpose, status)
                        VALUES (?, ?, 'legacy', ?, ?, 'USD', ?, 'ACTIVE')""", a[1], "L-" + a[1], a[4], a[5], a[6]);
                jdbc.update("INSERT INTO ledger_account_balance (ledger_account_id, currency, min_balance_minor) VALUES (?, 'USD', 0)", a[1]);
                jdbc.update("UPDATE account SET status = 'ACTIVE', ledger_account_id = ?, activated_at = now() WHERE id = ?", a[1], a[0]);
            }
            jdbc.update("""
                    INSERT INTO obligation (id, obligation_number, obligation_type, status, creditor_party_id, debtor_party_id,
                        currency, principal_minor, position_account_id, settlement_account_id, proposed_at)
                    VALUES (?, 'LL1', 'LOAN', 'PROPOSED', md5('wbank.institution')::uuid, ?, 'USD', ?, ?, ?, now())""",
                    obligation, party, p, loanAcct, deposit);
            jdbc.update("""
                    INSERT INTO loan_terms (obligation_id, annual_rate_bps, installment_count, repayment_frequency, amortization_method)
                    VALUES (?, 600, 2, 'MONTHLY', 'ANNUITY')""", obligation);
            jdbc.update("""
                    INSERT INTO journal_entry (id, currency, entry_type, status, description, value_date, booked_at,
                        total_amount_minor, correlation_id, initiated_by, initiator_type, source_operation)
                    VALUES (?, 'USD', 'LOAN_DISBURSEMENT', 'POSTED', 'legacy', ?, ?, ?, ?, 'legacy', 'SYSTEM', 'legacy')""",
                    entry, start, booked, p, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO posting (id, journal_entry_id, entry_leg, ledger_account_id, currency, direction, amount_minor,
                        account_sequence, balance_after_minor, booked_at) VALUES
                        (?, ?, 0, ?, 'USD', 'DEBIT',  ?, 1, ?, ?), (?, ?, 1, ?, 'USD', 'CREDIT', ?, 1, ?, ?)""",
                    UUID.randomUUID(), entry, loanLedger, p, p, booked, UUID.randomUUID(), entry, depositLedger, p, p, booked);
            jdbc.update("UPDATE ledger_account_balance SET balance_minor = ?, total_debits_minor = ?, posting_count = 1, last_sequence = 1 WHERE ledger_account_id = ?", p, p, loanLedger);
            jdbc.update("UPDATE ledger_account_balance SET balance_minor = ?, total_credits_minor = ?, posting_count = 1, last_sequence = 1 WHERE ledger_account_id = ?", p, p, depositLedger);
            jdbc.update("""
                    INSERT INTO obligation_installment (id, obligation_id, sequence_no, due_date, principal_due_minor, interest_due_minor)
                    VALUES (?, ?, 1, ?, 49875, 500), (?, ?, 2, ?, 50125, 251)""",
                    UUID.randomUUID(), obligation, start.plusMonths(1), UUID.randomUUID(), obligation, start.plusMonths(2));
            jdbc.update("""
                    UPDATE obligation SET status = 'ACTIVE', start_date = ?, maturity_date = ?, disbursement_entry_id = ?,
                        activated_at = now() WHERE id = ?""", start, start.plusMonths(2), entry, obligation);
        });

        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").load().migrate();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT o.status, o.approved_at = o.activated_at AS approved_backfilled, o.interest_receivable_ledger_account_id,
                       t.allocation_policy, t.interest_recognition
                  FROM obligation o JOIN loan_terms t ON t.obligation_id = o.id WHERE o.id = ?""", obligation);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("approved_backfilled", true)
                .containsEntry("allocation_policy", "V1_OLDEST_FIRST_INTEREST_THEN_PRINCIPAL")
                .containsEntry("interest_recognition", "CASH");
        assertThat(row.get("interest_receivable_ledger_account_id")).isNull();
        // The V9 consistency rules accept the legacy loan as it stands.
        jdbc.queryForList("SELECT wbank_check_obligation(?)", obligation);
    }
}
