package com.wbank.account.domain;

import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import com.wbank.product.domain.Product;
import com.wbank.product.domain.ProductFamily;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A financial position held by a party under a product: "Alice's USD current account",
 * "Acme's term loan".
 *
 * <p><b>An account has no balance field.</b> Once active it is backed by exactly one
 * ledger account, and the postings on that ledger account are the money. The account
 * holds only what the ledger cannot know: who owns the position, through which customer
 * relationship, under which contract, and whether it may currently be used.
 *
 * <p>The owner is recorded as both the customer relationship and the party behind it;
 * PostgreSQL pins the pair together (composite FK), so ownership cannot drift.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, updatable = false)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "owner_party_id", nullable = false, updatable = false)
    private UUID ownerPartyId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_family", nullable = false, updatable = false)
    private ProductFamily productFamily;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    /** Null while PENDING; set exactly once, on activation. */
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    /** Non-negative. The ledger floor of a deposit position is the negation of this value. */
    @Column(name = "overdraft_limit_minor", nullable = false, updatable = false)
    private long overdraftLimitMinor;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Account() {
        // for JPA
    }

    public static Account open(UUID id, String accountNumber, UUID customerId, UUID ownerPartyId, Product product,
                               CurrencyUnit currency, Money overdraftLimit, Instant now) {
        Objects.requireNonNull(customerId, "an account must have an owning customer relationship");
        Objects.requireNonNull(ownerPartyId, "an account must have an owning party");
        if (!overdraftLimit.currency().code().equals(currency.code())) {
            throw new IllegalArgumentException("Overdraft limit currency must match the account currency");
        }
        if (overdraftLimit.isNegative()) {
            throw new IllegalArgumentException("Overdraft limit cannot be negative: " + overdraftLimit);
        }
        if (overdraftLimit.isPositive() && !product.isAllowsOverdraft()) {
            throw new IllegalArgumentException("Product " + product.getCode() + " does not permit an overdraft");
        }
        Account a = new Account();
        a.id = id;
        a.accountNumber = accountNumber;
        a.customerId = customerId;
        a.ownerPartyId = ownerPartyId;
        a.productCode = product.getCode();
        a.productFamily = product.getFamily();
        a.currencyCode = currency.code();
        a.status = AccountStatus.PENDING;
        a.overdraftLimitMinor = overdraftLimit.minorUnits();
        a.openedAt = now;
        a.createdAt = now;
        a.updatedAt = now;
        return a;
    }

    public void activate(UUID ledgerAccountId, Instant now) {
        transition(AccountStatus.ACTIVE, now);
        this.ledgerAccountId = Objects.requireNonNull(ledgerAccountId);
        this.activatedAt = now;
    }

    public void freeze(Instant now) {
        transition(AccountStatus.FROZEN, now);
    }

    public void unfreeze(Instant now) {
        InvalidStateTransitionException.require(status == AccountStatus.FROZEN, "account", id, status,
                AccountStatus.ACTIVE);
        transition(AccountStatus.ACTIVE, now);
    }

    public void close(Instant now) {
        transition(AccountStatus.CLOSED, now);
        this.closedAt = now;
    }

    private void transition(AccountStatus target, Instant now) {
        InvalidStateTransitionException.require(status.canTransitionTo(target), "account", id, status, target);
        this.status = target;
        this.updatedAt = now;
    }

    /** The authorised ledger floor, in the account's normal-balance direction. */
    public Long ledgerFloorMinor() {
        return productFamily == ProductFamily.DEPOSIT ? -overdraftLimitMinor : 0L;
    }

    public boolean isOperable() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean hasLedgerPosition() {
        return ledgerAccountId != null;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getOwnerPartyId() {
        return ownerPartyId;
    }

    public String getProductCode() {
        return productCode;
    }

    public ProductFamily getProductFamily() {
        return productFamily;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getOverdraftLimitMinor() {
        return overdraftLimitMinor;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getVersion() {
        return version;
    }
}
