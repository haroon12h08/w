package com.wbank.obligation;

import com.wbank.obligation.domain.AllocationPolicy;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.domain.DelinquencyEvent;
import com.wbank.obligation.domain.LoanDecision;
import com.wbank.obligation.domain.LoanTerms;
import com.wbank.obligation.domain.Obligation;
import com.wbank.obligation.domain.Repayment;
import java.time.LocalDate;
import java.util.List;

/**
 * A loan as reported, keeping three views apart:
 * <ul>
 *   <li><b>contractual</b>: the schedule (what was expected);</li>
 *   <li><b>actual</b>: repayments, allocations, waivers (what happened);</li>
 *   <li><b>accounting</b>: the ledger's principal and accrued-interest positions.</li>
 * </ul>
 *
 * @param ledgerOutstandingPrincipalMinor the position's ledger balance (null before disbursement)
 * @param ledgerInterestReceivableMinor   accrued-but-unpaid interest per the ledger (accrual loans)
 * @param accruedInterestMinor            total interest recognised so far (Σ accruals)
 * @param delinquency                     derived as of today (null unless disbursed and not settled)
 * @param quote                           amount due now and early-settlement amount (V2 loans)
 */
public record LoanView(Obligation obligation, LoanTerms terms, List<InstallmentView> schedule,
                       Long ledgerOutstandingPrincipalMinor, Long ledgerInterestReceivableMinor,
                       long accruedInterestMinor, List<Repayment> repayments, Delinquency.Status delinquency,
                       AllocationPolicy.Quote quote, List<LoanDecision> decisions,
                       List<DelinquencyEvent> delinquencyHistory) {

    public record InstallmentView(int sequence, LocalDate dueDate, long principalDueMinor, long interestDueMinor,
                                  long principalPaidMinor, long interestPaidMinor, long interestWaivedMinor,
                                  boolean interestAccrued) {
        public boolean fullyPaid() {
            return principalPaidMinor == principalDueMinor
                    && interestPaidMinor + interestWaivedMinor == interestDueMinor;
        }
    }

    /** Outstanding principal according to the contract: principal minus principal repaid. */
    public long contractualOutstandingPrincipalMinor() {
        return obligation.getPrincipalMinor() - repayments.stream().mapToLong(Repayment::getPrincipalMinor).sum();
    }

    /** Interest earned but not yet paid, according to the contract and repayment history. */
    public long contractualAccruedUnpaidInterestMinor() {
        return accruedInterestMinor - repayments.stream().mapToLong(Repayment::getInterestMinor).sum();
    }

    /** Interest the schedule still expects, including periods not yet ended (not in the ledger). */
    public long scheduledInterestOutstandingMinor() {
        return schedule.stream()
                .mapToLong(i -> i.interestDueMinor() - i.interestPaidMinor() - i.interestWaivedMinor()).sum();
    }
}
