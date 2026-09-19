package com.wbank.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Interest of one instalment's period, earned on its due date and booked to the ledger. Append-only. */
@Entity
@Immutable
@Table(name = "interest_accrual")
public class InterestAccrual {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Column(name = "installment_id", nullable = false, updatable = false)
    private UUID installmentId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "accrual_date", nullable = false, updatable = false)
    private LocalDate accrualDate;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected InterestAccrual() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public static InterestAccrual of(Installment i, UUID journalEntryId, Instant now) {
        InterestAccrual a = new InterestAccrual();
        a.id = UUID.randomUUID();
        a.obligationId = i.getObligationId();
        a.installmentId = i.getId();
        a.amountMinor = i.getInterestDueMinor();
        a.accrualDate = i.getDueDate();
        a.journalEntryId = journalEntryId;
        a.recordedAt = now;
        return a;
    }

    public UUID getInstallmentId() {
        return installmentId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public LocalDate getAccrualDate() {
        return accrualDate;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }
}
