package com.wbank.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** The part of one repayment applied to one instalment. Append-only. */
@Entity
@Immutable
@Table(name = "repayment_allocation")
public class RepaymentAllocation {

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "repayment_id", nullable = false, updatable = false)
        private UUID repaymentId;

        @Column(name = "installment_id", nullable = false, updatable = false)
        private UUID installmentId;

        protected Key() {
        }

        public Key(UUID repaymentId, UUID installmentId) {
            this.repaymentId = repaymentId;
            this.installmentId = installmentId;
        }

        public UUID getRepaymentId() {
            return repaymentId;
        }

        public UUID getInstallmentId() {
            return installmentId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.repaymentId.equals(repaymentId) && k.installmentId.equals(installmentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repaymentId, installmentId);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "principal_minor", nullable = false, updatable = false)
    private long principalMinor;

    @Column(name = "interest_minor", nullable = false, updatable = false)
    private long interestMinor;

    protected RepaymentAllocation() {
        // for JPA
    }

    public static RepaymentAllocation of(UUID repaymentId, AllocationPolicy.Allocation a) {
        RepaymentAllocation r = new RepaymentAllocation();
        r.id = new Key(repaymentId, a.installmentId());
        r.principalMinor = a.principalMinor();
        r.interestMinor = a.interestMinor();
        return r;
    }

    public Key getId() {
        return id;
    }

    public long getPrincipalMinor() {
        return principalMinor;
    }

    public long getInterestMinor() {
        return interestMinor;
    }
}
