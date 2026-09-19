package com.wbank.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One contractual instalment: what was <em>expected</em>, and when. Never updated; what was
 * actually paid is recorded separately as {@link RepaymentAllocation}s.
 */
@Entity
@Immutable
@Table(name = "obligation_installment")
public class Installment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private short sequenceNo;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "principal_due_minor", nullable = false, updatable = false)
    private long principalDueMinor;

    @Column(name = "interest_due_minor", nullable = false, updatable = false)
    private long interestDueMinor;

    protected Installment() {
        // for JPA
    }

    public static Installment of(UUID obligationId, AnnuitySchedule.Line line) {
        Installment i = new Installment();
        i.id = UUID.randomUUID();
        i.obligationId = obligationId;
        i.sequenceNo = (short) line.sequence();
        i.dueDate = line.dueDate();
        i.principalDueMinor = line.principalMinor();
        i.interestDueMinor = line.interestMinor();
        return i;
    }

    public UUID getId() {
        return id;
    }

    public UUID getObligationId() {
        return obligationId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public long getPrincipalDueMinor() {
        return principalDueMinor;
    }

    public long getInterestDueMinor() {
        return interestDueMinor;
    }
}
