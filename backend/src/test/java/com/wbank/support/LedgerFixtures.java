package com.wbank.support;

import com.wbank.ledger.LedgerReconciliationService;
import com.wbank.ledger.LedgerService;
import com.wbank.ledger.PostedEntry;
import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountPurpose;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test helpers that go through the real service layer and COMMIT.
 *
 * <p>Tests of financial invariants must not run inside a rolled-back test transaction:
 * PostgreSQL's deferred constraint triggers only fire at COMMIT, so a rolled-back test
 * would never observe them. Every helper here runs its own committed transaction.
 */
@Component
public class LedgerFixtures {

    public final TransactionTemplate tx;
    public final LedgerService ledger;
    public final LedgerReconciliationService reconciliation;
    public final JdbcTemplate jdbc;
    private final CurrencyRegistry currencies;

    public LedgerFixtures(PlatformTransactionManager txManager, LedgerService ledger,
                          LedgerReconciliationService reconciliation, JdbcTemplate jdbc,
                          CurrencyRegistry currencies) {
        this.tx = new TransactionTemplate(txManager);
        this.ledger = ledger;
        this.reconciliation = reconciliation;
        this.jdbc = jdbc;
        this.currencies = currencies;
    }

    public CurrencyUnit currency(String code) {
        return currencies.require(code);
    }

    public Money money(long minor, String currency) {
        return Money.ofMinorUnits(minor, currency(currency));
    }

    /** An internal account with no balance floor. */
    public LedgerAccount account(LedgerAccountType type, String currency) {
        return open(type, currency, LedgerAccountPurpose.INTERNAL_SUSPENSE, null);
    }

    /** A customer deposit liability that may not go below zero. */
    public LedgerAccount customerAccount(String currency) {
        return open(LedgerAccountType.LIABILITY, currency, LedgerAccountPurpose.CUSTOMER_DEPOSIT, 0L);
    }

    public LedgerAccount open(LedgerAccountType type, String currency, LedgerAccountPurpose purpose, Long floor) {
        String code = "T-" + type.name().charAt(0) + "-" + UUID.randomUUID();
        return tx.execute(s -> ledger.openAccount(code, "Test " + type, type, currency(currency), purpose, floor));
    }

    public JournalEntryRequest request(String currency, String key, PostingInstruction... legs) {
        return new JournalEntryRequest(JournalEntryType.ADJUSTMENT, "test journal", LocalDate.of(2026, 9, 19),
                currency(currency), List.of(legs), key, RequestContext.forOperation("test.post"));
    }

    public PostedEntry post(JournalEntryRequest request) {
        return tx.execute(s -> ledger.post(request));
    }

    /** Simple two-leg transfer: debit {@code debit}, credit {@code credit}. */
    public PostedEntry move(UUID debit, UUID credit, long minor, String currency, String key) {
        Money amount = money(minor, currency);
        return post(request(currency, key,
                PostingInstruction.debit(debit, amount), PostingInstruction.credit(credit, amount)));
    }

    /** Projection balance, read fresh from the database (not from any ORM cache). */
    public long projected(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT balance_minor FROM ledger_account_balance WHERE ledger_account_id = ?", Long.class, accountId);
    }

    public long derived(UUID accountId) {
        return reconciliation.derivedBalanceMinor(accountId);
    }

    public long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    public long postingsOn(UUID accountId) {
        return count("SELECT count(*) FROM posting WHERE ledger_account_id = ?", accountId);
    }

    public long entriesWithKey(String key) {
        return count("SELECT count(*) FROM journal_entry WHERE idempotency_key = ?", key);
    }

    /** All messages in a cause chain, so a test can assert WHICH constraint fired. */
    public static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }
}
