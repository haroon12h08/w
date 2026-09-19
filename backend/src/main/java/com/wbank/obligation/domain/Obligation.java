package com.wbank.obligation.domain;

import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.platform.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A contractual promise by a debtor party to pay a creditor party.
 *
 * <p>An obligation is not an account and has no balance. It records the <em>contract</em>:
 * who owes whom, in what currency, how much principal, under which terms, and where it
 * stands in its lifecycle. How much is still owed is derived: from the schedule and the
 * repayment history (contractually), and from the position account's ledger balance
 * (financially). PostgreSQL requires the two to agree at every commit.
 */
@Entity
@Table(name = "obligation")
public class Obligation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "obligation_number", nullable = false, updatable = false)
    private String obligationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "obligation_type", nullable = false, updatable = false)
    private ObligationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ObligationStatus status;

    @Column(name = "creditor_party_id", nullable = false, updatable = false)
    private UUID creditorPartyId;

    @Column(name = "debtor_party_id", nullable = false, updatable = false)
    private UUID debtorPartyId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Column(name = "principal_minor", nullable = false, updatable = false)
    private long principalMinor;

    @Column(name = "position_account_id", nullable = false, updatable = false)
    private UUID positionAccountId;

    @Column(name = "settlement_account_id", nullable = false, updatable = false)
    private UUID settlementAccountId;

    @Column(name = "proposed_at", nullable = false, updatable = false)
    private Instant proposedAt;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "disbursement_entry_id")
    private UUID disbursementEntryId;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "defaulted_at")
    private Instant defaultedAt;

    /** Accrual-basis loans only: the ledger account holding interest earned but not yet paid. */
    @Column(name = "interest_receivable_ledger_account_id")
    private UUID interestReceivableLedgerAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Obligation() {
        // for JPA
    }

    public static Obligation propose(UUID id, String number, ObligationType type, UUID creditorPartyId,
                                     UUID debtorPartyId, Money principal, UUID positionAccountId,
                                     UUID settlementAccountId, Instant now) {
        Objects.requireNonNull(creditorPartyId, "an obligation must name its creditor");
        Objects.requireNonNull(debtorPartyId, "an obligation must name its debtor");
        if (creditorPartyId.equals(debtorPartyId)) {
            throw new IllegalArgumentException("A party cannot owe itself");
        }
        if (!principal.isPositive()) {
            throw new IllegalArgumentException("Principal must be positive: " + principal);
        }
        Obligation o = new Obligation();
        o.id = id;
        o.obligationNumber = number;
        o.type = type;
        o.status = ObligationStatus.PROPOSED;
        o.creditorPartyId = creditorPartyId;
        o.debtorPartyId = debtorPartyId;
        o.currencyCode = principal.currency().code();
        o.principalMinor = principal.minorUnits();
        o.positionAccountId = Objects.requireNonNull(positionAccountId);
        o.settlementAccountId = Objects.requireNonNull(settlementAccountId);
        o.proposedAt = now;
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    public void approve(Instant now) {
        transition(ObligationStatus.APPROVED, now);
        this.approvedAt = now;
    }

    public void decline(Instant now) {
        transition(ObligationStatus.DECLINED, now);
        this.declinedAt = now;
    }

    public void declareDefault(Instant now) {
        transition(ObligationStatus.DEFAULTED, now);
        this.defaultedAt = now;
    }

    public void activate(LocalDate startDate, LocalDate maturityDate, UUID disbursementEntryId,
                         UUID interestReceivableLedgerAccountId, Instant now) {
        transition(ObligationStatus.ACTIVE, now);
        this.interestReceivableLedgerAccountId = interestReceivableLedgerAccountId;
        this.startDate = startDate;
        this.maturityDate = maturityDate;
        this.disbursementEntryId = Objects.requireNonNull(disbursementEntryId);
        this.activatedAt = now;
    }

    public void settle(Instant now) {
        transition(ObligationStatus.SETTLED, now);
        this.settledAt = now;
    }

    public void cancel(Instant now) {
        transition(ObligationStatus.CANCELLED, now);
        this.cancelledAt = now;
    }

    private void transition(ObligationStatus target, Instant now) {
        InvalidStateTransitionException.require(status.canTransitionTo(target), "obligation", id, status, target);
        this.status = target;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getObligationNumber() {
        return obligationNumber;
    }

    public ObligationType getType() {
        return type;
    }

    public ObligationStatus getStatus() {
        return status;
    }

    public UUID getCreditorPartyId() {
        return creditorPartyId;
    }

    public UUID getDebtorPartyId() {
        return debtorPartyId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public long getPrincipalMinor() {
        return principalMinor;
    }

    public UUID getPositionAccountId() {
        return positionAccountId;
    }

    public UUID getSettlementAccountId() {
        return settlementAccountId;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getMaturityDate() {
        return maturityDate;
    }

    public UUID getDisbursementEntryId() {
        return disbursementEntryId;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getDeclinedAt() {
        return declinedAt;
    }

    public Instant getDefaultedAt() {
        return defaultedAt;
    }

    public UUID getInterestReceivableLedgerAccountId() {
        return interestReceivableLedgerAccountId;
    }
}
