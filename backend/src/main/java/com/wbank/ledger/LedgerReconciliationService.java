package com.wbank.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that the derived state of the system still agrees with the ledger.
 *
 * <p>The balance table is a cache. A cache that is never verified is a liability, so
 * the system ships with the means to check it from day one, in production and in tests:
 *
 * <ul>
 *   <li>{@link #conservationOfMoney()} — for every currency, the signed sum of all
 *       postings must be exactly zero. Money is only ever moved, never minted.</li>
 *   <li>{@link #balanceDiscrepancies()} — every balance row must equal the aggregate
 *       of its account's postings.</li>
 *   <li>{@link #entryImbalances()} — every journal entry must balance internally.</li>
 * </ul>
 *
 * <p>These are deliberately written as SQL against the ledger tables rather than via the
 * ORM: a verification that reuses the same code paths it is verifying proves very little.
 */
@Service
public class LedgerReconciliationService {

    private final JdbcTemplate jdbc;

    public LedgerReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Net signed postings per currency. Every value must be 0. */
    public record CurrencyPosition(String currency, long netSignedMinor, long postingCount) {
        public boolean isConserved() {
            return netSignedMinor == 0L;
        }
    }

    /** A balance row that disagrees with the postings it summarises. */
    public record BalanceDiscrepancy(
            UUID ledgerAccountId,
            String accountCode,
            long projectedBalanceMinor,
            long ledgerBalanceMinor,
            long projectedPostingCount,
            long ledgerPostingCount) {}

    /** A journal entry whose debits and credits differ. */
    public record EntryImbalance(UUID journalEntryId, long netSignedMinor, long legCount) {}

    @Transactional(readOnly = true)
    public List<CurrencyPosition> conservationOfMoney() {
        return jdbc.query("""
                SELECT currency,
                       COALESCE(SUM(signed_amount_minor), 0) AS net_signed,
                       COUNT(*)                              AS posting_count
                  FROM posting
                 GROUP BY currency
                 ORDER BY currency
                """,
                (rs, i) -> new CurrencyPosition(
                        rs.getString("currency").trim(),
                        rs.getLong("net_signed"),
                        rs.getLong("posting_count")));
    }

    @Transactional(readOnly = true)
    public List<BalanceDiscrepancy> balanceDiscrepancies() {
        return jdbc.query("""
                WITH ledger AS (
                    SELECT p.ledger_account_id,
                           SUM(p.signed_amount_minor) AS net_debit_positive,
                           COUNT(*)                   AS posting_count
                      FROM posting p
                     GROUP BY p.ledger_account_id
                )
                SELECT a.id                                AS ledger_account_id,
                       a.code                              AS account_code,
                       b.balance_minor                     AS projected_balance,
                       COALESCE(
                           CASE WHEN a.normal_balance = 'DEBIT'
                                THEN l.net_debit_positive
                                ELSE -l.net_debit_positive
                           END, 0)                         AS ledger_balance,
                       b.posting_count                     AS projected_count,
                       COALESCE(l.posting_count, 0)        AS ledger_count
                  FROM ledger_account a
                  JOIN ledger_account_balance b ON b.ledger_account_id = a.id
                  LEFT JOIN ledger l            ON l.ledger_account_id = a.id
                 WHERE b.balance_minor <> COALESCE(
                           CASE WHEN a.normal_balance = 'DEBIT'
                                THEN l.net_debit_positive
                                ELSE -l.net_debit_positive
                           END, 0)
                    OR b.posting_count <> COALESCE(l.posting_count, 0)
                 ORDER BY a.code
                """,
                (rs, i) -> new BalanceDiscrepancy(
                        rs.getObject("ledger_account_id", UUID.class),
                        rs.getString("account_code"),
                        rs.getLong("projected_balance"),
                        rs.getLong("ledger_balance"),
                        rs.getLong("projected_count"),
                        rs.getLong("ledger_count")));
    }

    @Transactional(readOnly = true)
    public List<EntryImbalance> entryImbalances() {
        return jdbc.query("""
                SELECT journal_entry_id,
                       SUM(signed_amount_minor) AS net_signed,
                       COUNT(*)                 AS leg_count
                  FROM posting
                 GROUP BY journal_entry_id
                HAVING SUM(signed_amount_minor) <> 0 OR COUNT(*) < 2
                """,
                (rs, i) -> new EntryImbalance(
                        rs.getObject("journal_entry_id", UUID.class),
                        rs.getLong("net_signed"),
                        rs.getLong("leg_count")));
    }

    /**
     * The balance of one account computed from scratch from its postings, in the
     * account's normal-balance direction. Independent of {@code ledger_account_balance}.
     */
    @Transactional(readOnly = true)
    public long derivedBalanceMinor(UUID ledgerAccountId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN p.direction = a.normal_balance
                                         THEN p.amount_minor ELSE -p.amount_minor END), 0)
                  FROM ledger_account a
                  LEFT JOIN posting p ON p.ledger_account_id = a.id
                 WHERE a.id = ?
                """, Long.class, ledgerAccountId);
        return value == null ? 0L : value;
    }

    /** True when every reconciliation check passes. */
    @Transactional(readOnly = true)
    public boolean isConsistent() {
        return conservationOfMoney().stream().allMatch(CurrencyPosition::isConserved)
                && balanceDiscrepancies().isEmpty()
                && entryImbalances().isEmpty();
    }
}
