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

    static final Instant T0 = ProductionLikeLendingBook.T0;

    JdbcTemplate jdbc;


    @Test
    void anExistingLendingBookIsBackfilledIntoAFaithfulHistory() throws Exception {
        String schema = "v10_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("9").load().migrate();
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var book = new ProductionLikeLendingBook(jdbc).buildAtV9(tx);
        UUID[] loans = book.loans();
        long principal = ProductionLikeLendingBook.PRINCIPAL;
        long i1Principal = ProductionLikeLendingBook.I1_PRINCIPAL;
        long i1Interest = ProductionLikeLendingBook.I1_INTEREST;
        Instant serviced = ProductionLikeLendingBook.SERVICED;

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
