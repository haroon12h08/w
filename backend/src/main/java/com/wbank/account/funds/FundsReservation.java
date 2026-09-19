package com.wbank.account.funds;

import com.wbank.platform.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A hold: money in an account that has been promised to something (an authorised payment)
 * but has not yet left. It is <b>not</b> an accounting entry; the ledger balance is
 * unchanged until settlement. It reduces what the owner may still spend.
 */
@Entity
@Table(name = "funds_reservation")
public class FundsReservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "ledger_account_id", nullable = false, updatable = false)
    private UUID ledgerAccountId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    @Column(name = "purpose", nullable = false, updatable = false)
    private String purpose;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected FundsReservation() {
        // for JPA
    }

    static FundsReservation hold(UUID accountId, UUID ledgerAccountId, String currency, long amountMinor,
                                 String purpose, UUID referenceId, Instant now, Instant expiresAt) {
        FundsReservation r = new FundsReservation();
        r.id = UUID.randomUUID();
        r.accountId = accountId;
        r.ledgerAccountId = ledgerAccountId;
        r.currencyCode = currency;
        r.amountMinor = amountMinor;
        r.status = ReservationStatus.ACTIVE;
        r.purpose = purpose;
        r.referenceId = referenceId;
        r.createdAt = now;
        r.expiresAt = expiresAt;
        return r;
    }

    void resolve(ReservationStatus target, Instant now) {
        InvalidStateTransitionException.require(status == ReservationStatus.ACTIVE && target != ReservationStatus.ACTIVE,
                "funds_reservation", id, status, target);
        this.status = target;
        this.resolvedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getPurpose() {
        return purpose;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
