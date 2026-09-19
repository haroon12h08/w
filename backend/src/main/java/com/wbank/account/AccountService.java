package com.wbank.account;

import com.wbank.account.domain.Account;
import com.wbank.account.domain.AccountStatus;
import com.wbank.customer.CustomerService;
import com.wbank.customer.domain.Customer;
import com.wbank.ledger.LedgerService;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountBalance;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import com.wbank.platform.persistence.SequenceNumbers;
import com.wbank.product.ProductService;
import com.wbank.product.domain.Product;
import com.wbank.product.domain.ProductFamily;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle of financial positions, and the only place where an account and its ledger
 * account change together.
 *
 * <p>Every transition that affects whether money may move is applied to both the account
 * and its ledger account in the same transaction (PostgreSQL re-checks the pairing at
 * commit). That is what makes "a closed account cannot silently resume activity" true
 * for every write path, including the ledger's own API.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerService customers;
    private final ProductService products;
    private final LedgerService ledger;
    private final CurrencyRegistry currencies;
    private final SequenceNumbers sequenceNumbers;
    private final AuditTrail auditTrail;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountService(AccountRepository accounts, CustomerService customers, ProductService products,
                          LedgerService ledger, CurrencyRegistry currencies, SequenceNumbers sequenceNumbers,
                          AuditTrail auditTrail, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.customers = customers;
        this.products = products;
        this.ledger = ledger;
        this.currencies = currencies;
        this.sequenceNumbers = sequenceNumbers;
        this.auditTrail = auditTrail;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A position as the bank reports it: the contract plus what the ledger says it is worth. */
    public record AccountPosition(Account account, Money ledgerBalance) {}

    /** Opens a DEPOSIT account (PENDING). Loan positions are opened by the lending module. */
    @Transactional
    public Account openDepositAccount(UUID customerId, String productCode, CurrencyUnit currency, Money overdraftLimit) {
        Product product = products.require(productCode);
        if (product.getFamily() != ProductFamily.DEPOSIT) {
            throw new BusinessRuleViolationException("account.product_family",
                    "Product %s is a %s product; its positions are opened by that product's module"
                            .formatted(product.getCode(), product.getFamily()));
        }
        return openPosition(customerId, product, currency, overdraftLimit);
    }

    /**
     * Opens a position of any family inside the caller's transaction. The caller (e.g. the
     * lending module) owns the business operation that justifies the position.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Account openPosition(UUID customerId, Product product, CurrencyUnit currency, Money overdraftLimit) {
        OperationContext context = RequestContext.forOperation("account.open");
        Customer customer = customers.requireForUpdate(customerId);
        if (!customer.canStartNewBusiness()) {
            throw new BusinessRuleViolationException("customer.not_operable",
                    "Customer %s is %s and cannot open accounts".formatted(customerId, customer.getStatus()));
        }
        if (!product.isOnSale()) {
            throw new BusinessRuleViolationException("product.not_on_sale",
                    "Product %s is %s".formatted(product.getCode(), product.getStatus()));
        }
        Account account = Account.open(UUID.randomUUID(), sequenceNumbers.nextAccountNumber(), customer.getId(),
                customer.getPartyId(), product, currency, overdraftLimit, clock.instant());
        accounts.save(account);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountNumber", account.getAccountNumber());
        payload.put("customerId", customerId.toString());
        payload.put("ownerPartyId", customer.getPartyId().toString());
        payload.put("productCode", product.getCode());
        payload.put("currency", currency.code());
        payload.put("overdraftLimitMinor", overdraftLimit.minorUnits());
        auditTrail.record("account.opened", "account", account.getId(), context, payload);
        return account;
    }

    /** PENDING -> ACTIVE: creates the ledger position. From here on the account can hold money. */
    @Transactional
    public Account activate(UUID accountId) {
        return activateInternal(accountId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account activateInternal(UUID accountId) {
        OperationContext context = RequestContext.forOperation("account.activate");
        Account account = lock(accountId);
        Customer customer = customers.requireForUpdate(account.getCustomerId());
        if (!customer.canStartNewBusiness()) {
            throw new BusinessRuleViolationException("customer.not_operable",
                    "Customer %s is %s and cannot activate accounts".formatted(customer.getId(), customer.getStatus()));
        }
        InvalidStateTransitionException.require(account.getStatus() == AccountStatus.PENDING, "account",
                accountId, account.getStatus(), AccountStatus.ACTIVE);
        ProductFamily family = account.getProductFamily();
        String prefix = family == ProductFamily.DEPOSIT ? "2000-DEP-" : "1300-LOAN-";
        LedgerAccount ledgerAccount = ledger.openAccount(
                prefix + account.getAccountNumber(),
                account.getProductCode() + " " + account.getAccountNumber() + " (" + account.getCurrencyCode() + ")",
                family.ledgerType(),
                currencies.require(account.getCurrencyCode()),
                family.ledgerPurpose(),
                account.ledgerFloorMinor());
        account.activate(ledgerAccount.getId(), clock.instant());
        accounts.save(account);
        auditTrail.record("account.activated", "account", accountId, context,
                Map.of("ledgerAccountId", ledgerAccount.getId().toString(),
                        "ledgerAccountCode", ledgerAccount.getCode()));
        return account;
    }

    @Transactional
    public Account freeze(UUID accountId) {
        OperationContext context = RequestContext.forOperation("account.freeze");
        Account account = lock(accountId);
        account.freeze(clock.instant());
        ledger.changeAccountStatus(account.getLedgerAccountId(), AccountStatus.FROZEN.ledgerStatus());
        accounts.save(account);
        auditTrail.record("account.frozen", "account", accountId, context, Map.of());
        return account;
    }

    @Transactional
    public Account unfreeze(UUID accountId) {
        OperationContext context = RequestContext.forOperation("account.unfreeze");
        Account account = lock(accountId);
        account.unfreeze(clock.instant());
        ledger.changeAccountStatus(account.getLedgerAccountId(), AccountStatus.ACTIVE.ledgerStatus());
        accounts.save(account);
        auditTrail.record("account.unfrozen", "account", accountId, context, Map.of());
        return account;
    }

    /**
     * Closes a position. Requires a zero ledger balance (checked under the ledger's balance
     * lock, so no posting can race it) and no open obligation using the account.
     */
    @Transactional
    public Account close(UUID accountId) {
        return closeInternal(accountId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account closeInternal(UUID accountId) {
        OperationContext context = RequestContext.forOperation("account.close");
        Account account = lock(accountId);
        AccountStatus from = account.getStatus();
        account.close(clock.instant()); // validates the transition before touching the ledger
        Long openObligations = jdbc.queryForObject("""
                SELECT count(*) FROM obligation
                 WHERE (position_account_id = ? OR settlement_account_id = ?) AND status IN ('PROPOSED', 'APPROVED', 'ACTIVE', 'DEFAULTED')
                """, Long.class, accountId, accountId);
        if (openObligations != null && openObligations > 0) {
            throw new BusinessRuleViolationException("account.open_obligation",
                    "Account %s is used by %d open obligation(s)".formatted(account.getAccountNumber(), openObligations));
        }
        if (account.hasLedgerPosition()) {
            ledger.changeAccountStatus(account.getLedgerAccountId(), AccountStatus.CLOSED.ledgerStatus());
        }
        accounts.save(account);
        auditTrail.record("account.closed", "account", accountId, context, Map.of("from", from.name()));
        return account;
    }

    @Transactional(readOnly = true)
    public Account require(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> NotFoundException.of("account", accountId));
    }

    @Transactional(readOnly = true)
    public Account requireByNumber(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber).orElseThrow(() -> new NotFoundException(
                "account.not_found", "Account " + accountNumber + " does not exist"));
    }

    @Transactional(readOnly = true)
    public List<Account> listByCustomer(UUID customerId) {
        return accounts.findByCustomerIdOrderByOpenedAtAsc(customerId);
    }

    /** The account with its ledger-derived balance. There is no other source for the number. */
    @Transactional(readOnly = true)
    public AccountPosition position(UUID accountId) {
        Account account = require(accountId);
        CurrencyUnit currency = currencies.require(account.getCurrencyCode());
        if (!account.hasLedgerPosition()) {
            return new AccountPosition(account, Money.zero(currency));
        }
        LedgerAccountBalance balance = ledger.requireBalance(account.getLedgerAccountId());
        return new AccountPosition(account, balance.balance(currency));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account lock(UUID accountId) {
        return accounts.findByIdForUpdate(accountId).orElseThrow(() -> NotFoundException.of("account", accountId));
    }
}
