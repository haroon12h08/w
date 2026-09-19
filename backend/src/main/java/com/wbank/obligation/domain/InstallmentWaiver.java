package com.wbank.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Scheduled interest that will never be charged, because the loan was settled before the
 * period ended. Recorded so that "expected" (schedule) = "paid" + "waived" remains an
 * identity, not a gap. No ledger effect: waived interest was never accrued. Append-only.
 */
@Entity
@Immutable
@Table(name = "installment_waiver")
public class InstallmentWaiver {

    public static final String EARLY_SETTLEMENT = "EARLY_SETTLEMENT";

    @Id
    @Column(name = "installment_id", nullable = false, updatable = false)
    private UUID installmentId;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Column(name = "repayment_id", nullable = false, updatable = false)
    private UUID repaymentId;

    @Column(name = "interest_waived_minor", nullable = false, updatable = false)
    private long interestWaivedMinor;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "waived_at", nullable = false, updatable = false)
    private Instant waivedAt;

    protected InstallmentWaiver() {
        // for JPA
    }

    public static InstallmentWaiver earlySettlement(UUID obligationId, UUID repaymentId, AllocationPolicy.Waiver w,
                                                    Instant now) {
        InstallmentWaiver x = new InstallmentWaiver();
        x.installmentId = w.installmentId();
        x.obligationId = obligationId;
        x.repaymentId = repaymentId;
        x.interestWaivedMinor = w.interestMinor();
        x.reason = EARLY_SETTLEMENT;
        x.waivedAt = now;
        return x;
    }

    public UUID getInstallmentId() {
        return installmentId;
    }

    public long getInterestWaivedMinor() {
        return interestWaivedMinor;
    }
}
