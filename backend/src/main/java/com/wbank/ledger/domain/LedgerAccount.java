package com.wbank.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A named place in the chart of accounts where value can sit.
 *
 * <p>A ledger account is single-currency by construction. Multi-currency "accounts"
 * do not exist here; a customer holding two currencies holds two accounts. This
 * removes an entire class of ambiguity from every downstream calculation.
 */
@Entity
@Table(name = "ledger_account")
public class LedgerAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false)
    private LedgerAccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, updatable = false)
    private NormalBalance normalBalance;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    private LedgerAccountPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LedgerAccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected LedgerAccount() {
        // for JPA
    }

    public static LedgerAccount open(UUID id,
                                     String code,
                                     String name,
                                     LedgerAccountType accountType,
                                     String currencyCode,
                                     LedgerAccountPurpose purpose,
                                     Instant now) {
        LedgerAccount account = new LedgerAccount();
        account.id = id;
        account.code = code;
        account.name = name;
        account.accountType = accountType;
        // Derived, never supplied by the caller: the normal balance of an account is
        // a property of its type, not an independent choice.
        account.normalBalance = accountType.normalBalance();
        account.currencyCode = currencyCode;
        account.purpose = purpose;
        account.status = LedgerAccountStatus.ACTIVE;
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public void changeStatus(LedgerAccountStatus newStatus, Instant now) {
        if (this.status == LedgerAccountStatus.CLOSED) {
            throw new IllegalStateException("Ledger account " + id + " is closed and cannot change status");
        }
        this.status = newStatus;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public LedgerAccountType getAccountType() {
        return accountType;
    }

    public NormalBalance getNormalBalance() {
        return normalBalance;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public LedgerAccountPurpose getPurpose() {
        return purpose;
    }

    public LedgerAccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
