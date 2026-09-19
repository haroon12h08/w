package com.wbank.deposit;

import com.wbank.account.AccountService;
import com.wbank.account.domain.Account;
import com.wbank.account.funds.FundsService;
import com.wbank.ledger.LedgerService;
import com.wbank.ledger.PostedEntry;
import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.CurrencyMismatchException;
import com.wbank.platform.money.Money;
import com.wbank.product.domain.ProductFamily;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash movements into and out of the bank through deposit accounts.
 *
 * <p>Each operation is one transaction that (1) checks the account may transact,
 * (2) expresses the movement as a balanced journal, and (3) posts it through the ledger.
 * The deposit module never computes or stores a balance; the ledger's balance floor is
 * what refuses an unaffordable withdrawal.
 */
@Service
public class DepositService {

    private final AccountService accounts;
    private final FundsService funds;
    private final LedgerService ledger;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public DepositService(AccountService accounts, FundsService funds, LedgerService ledger, AuditTrail auditTrail,
                          Clock clock) {
        this.accounts = accounts;
        this.funds = funds;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /** Cash in: debit the bank's cash (asset up), credit the customer's deposit (liability up). */
    @Transactional
    public PostedEntry depositCash(UUID accountId, Money amount, String description, String idempotencyKey) {
        OperationContext context = RequestContext.forOperation("deposit.cash_in");
        Account account = requireDepositAccount(accountId, amount);
        LedgerAccount cash = ledger.requireAccountByCode("1000-CASH-" + account.getCurrencyCode());
        PostedEntry posted = ledger.post(new JournalEntryRequest(
                JournalEntryType.CASH_DEPOSIT,
                description != null ? description : "Cash deposit to account " + account.getAccountNumber(),
                LocalDate.now(clock), amount.currency(),
                List.of(PostingInstruction.debit(cash.getId(), amount),
                        PostingInstruction.credit(account.getLedgerAccountId(), amount)),
                namespaced("deposit.cash_in", idempotencyKey), context));
        audit("deposit.cash_deposited", account, amount, posted, context);
        return posted;
    }

    /** Cash out: debit the customer's deposit (liability down), credit the bank's cash (asset down). */
    @Transactional
    public PostedEntry withdrawCash(UUID accountId, Money amount, String description, String idempotencyKey) {
        OperationContext context = RequestContext.forOperation("deposit.cash_out");
        Account account = requireDepositAccount(accountId, amount);
        funds.requireAvailable(account, amount); // money held for authorised payments is not spendable
        LedgerAccount cash = ledger.requireAccountByCode("1000-CASH-" + account.getCurrencyCode());
        PostedEntry posted = ledger.post(new JournalEntryRequest(
                JournalEntryType.CASH_WITHDRAWAL,
                description != null ? description : "Cash withdrawal from account " + account.getAccountNumber(),
                LocalDate.now(clock), amount.currency(),
                List.of(PostingInstruction.debit(account.getLedgerAccountId(), amount),
                        PostingInstruction.credit(cash.getId(), amount)),
                namespaced("deposit.cash_out", idempotencyKey), context));
        audit("deposit.cash_withdrawn", account, amount, posted, context);
        return posted;
    }

    private Account requireDepositAccount(UUID accountId, Money amount) {
        Account account = accounts.require(accountId);
        if (account.getProductFamily() != ProductFamily.DEPOSIT) {
            throw new BusinessRuleViolationException("deposit.not_a_deposit_account",
                    "Account %s is a %s position".formatted(account.getAccountNumber(), account.getProductFamily()));
        }
        if (!account.isOperable()) {
            throw new BusinessRuleViolationException("account.not_operable",
                    "Account %s is %s and cannot transact".formatted(account.getAccountNumber(), account.getStatus()));
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (!account.getCurrencyCode().equals(amount.currency().code())) {
            throw new CurrencyMismatchException("Account %s is in %s, not %s"
                    .formatted(account.getAccountNumber(), account.getCurrencyCode(), amount.currency().code()));
        }
        return account;
    }

    /** Ledger idempotency keys are global; prefix client keys with the operation they belong to. */
    static String namespaced(String operation, String key) {
        return key == null ? null : operation + ":" + key.strip();
    }

    private void audit(String event, Account account, Money amount, PostedEntry posted, OperationContext context) {
        if (posted.replayed()) {
            return; // the original operation was audited; a replay changes nothing
        }
        auditTrail.record(event, "account", account.getId(), context,
                Map.of("amountMinor", amount.minorUnits(), "currency", amount.currency().code(),
                        "journalEntryId", posted.entry().getId().toString()));
    }
}
