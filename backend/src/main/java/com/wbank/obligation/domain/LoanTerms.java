package com.wbank.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** The loan-specific monetary terms of an obligation. Immutable (also enforced by trigger). */
@Entity
@Immutable
@Table(name = "loan_terms")
public class LoanTerms {

    public static final String MONTHLY = "MONTHLY";

    @Id
    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    /** Nominal annual rate in basis points (1 bp = 0.01%). Integer: no binary fractions. */
    @Column(name = "annual_rate_bps", nullable = false)
    private int annualRateBps;

    @Column(name = "installment_count", nullable = false)
    private short installmentCount;

    @Column(name = "repayment_frequency", nullable = false)
    private String repaymentFrequency;

    @Column(name = "amortization_method", nullable = false)
    private String amortizationMethod;

    /** The allocation rule this loan is serviced under, fixed at origination. */
    @Column(name = "allocation_policy", nullable = false)
    private String allocationPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_recognition", nullable = false)
    private InterestRecognition interestRecognition;

    protected LoanTerms() {
        // for JPA
    }

    public static LoanTerms of(UUID obligationId, int annualRateBps, int installmentCount) {
        if (annualRateBps < 0 || annualRateBps > 10_000) {
            throw new IllegalArgumentException("Annual rate must be between 0 and 10000 basis points");
        }
        if (installmentCount < 1 || installmentCount > 480) {
            throw new IllegalArgumentException("Instalment count must be between 1 and 480");
        }
        LoanTerms t = new LoanTerms();
        t.obligationId = obligationId;
        t.annualRateBps = annualRateBps;
        t.installmentCount = (short) installmentCount;
        t.repaymentFrequency = MONTHLY;
        t.amortizationMethod = AnnuitySchedule.METHOD;
        t.allocationPolicy = AllocationPolicy.V2;
        t.interestRecognition = InterestRecognition.ACCRUAL_PERIOD_END;
        return t;
    }

    public UUID getObligationId() {
        return obligationId;
    }

    public int getAnnualRateBps() {
        return annualRateBps;
    }

    public int getInstallmentCount() {
        return installmentCount;
    }

    public String getRepaymentFrequency() {
        return repaymentFrequency;
    }

    public String getAmortizationMethod() {
        return amortizationMethod;
    }

    public String getAllocationPolicy() {
        return allocationPolicy;
    }

    public InterestRecognition getInterestRecognition() {
        return interestRecognition;
    }

    public boolean isAccrualBasis() {
        return interestRecognition == InterestRecognition.ACCRUAL_PERIOD_END;
    }
}
