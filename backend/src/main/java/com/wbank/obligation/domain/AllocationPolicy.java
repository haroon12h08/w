package com.wbank.obligation.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How a received repayment is split across what is owed. Deterministic and versioned:
 * the version is stored on every repayment, so historical allocations remain explainable
 * after the rule changes.
 *
 * <p><b>{@value #V1}</b>: walk instalments in contractual order (oldest first); within
 * each instalment pay interest before principal; stop when the money runs out. An
 * instalment is never over-paid; money beyond the total owed is refused by the caller.
 * Used by phase-2 (cash-basis) loans; it lets future interest be paid in advance.
 *
 * <p><b>{@value #V2}</b> (accrual-basis loans): only what is <em>due</em> (instalments whose
 * due date has arrived) may be paid, oldest instalment first, interest before principal
 * (there are no fees or penalty interest in this model). The single exception is <b>full
 * early settlement</b>: exactly the payoff amount (everything due, plus the principal of all
 * future instalments) settles the loan, and the interest of periods that have not ended is
 * waived, never charged. Any other amount above what is due is refused: partial
 * prepayment would require re-amortising an immutable schedule, which is out of scope.
 */
public final class AllocationPolicy {

    public static final String V1 = "V1_OLDEST_FIRST_INTEREST_THEN_PRINCIPAL";
    public static final String V2 = "V2_DUE_ONLY_INTEREST_THEN_PRINCIPAL_FULL_PAYOFF";

    /** What remains unpaid on one instalment. */
    public record Owed(UUID installmentId, int sequence, long principalMinor, long interestMinor) {}

    public record Allocation(UUID installmentId, int sequence, long principalMinor, long interestMinor) {}

    public record Result(String policy, List<Allocation> allocations, long principalMinor, long interestMinor) {
        public long totalMinor() {
            return principalMinor + interestMinor;
        }
    }

    /** V2 input: an instalment's unpaid parts and whether its due date has arrived. */
    public record Open(UUID installmentId, int sequence, boolean due, long principalMinor, long interestMinor) {}

    public record Waiver(UUID installmentId, long interestMinor) {}

    /** V2 result: allocations, plus interest waived when the payment is a full early settlement. */
    public record V2Result(Result result, List<Waiver> waivers, boolean fullSettlement) {}

    public record Quote(long dueNowMinor, long payoffMinor) {}

    private AllocationPolicy() {}

    public static Quote quoteV2(List<Open> open) {
        long due = 0;
        long future = 0;
        for (Open o : open) {
            if (o.due()) {
                due += o.principalMinor() + o.interestMinor();
            } else {
                future += o.principalMinor();
            }
        }
        return new Quote(due, due + future);
    }

    public static V2Result allocateV2(List<Open> openInContractualOrder, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A repayment must be positive");
        }
        Quote q = quoteV2(openInContractualOrder);
        boolean payoff = amountMinor == q.payoffMinor() && q.payoffMinor() > q.dueNowMinor();
        if (amountMinor > q.payoffMinor()) {
            throw new IllegalArgumentException("Repayment %d exceeds the early-settlement amount %d"
                    .formatted(amountMinor, q.payoffMinor()));
        }
        if (amountMinor > q.dueNowMinor() && !payoff) {
            throw new PrepaymentNotSupported(amountMinor, q);
        }
        List<Owed> due = openInContractualOrder.stream().filter(Open::due)
                .map(o -> new Owed(o.installmentId(), o.sequence(), o.principalMinor(), o.interestMinor())).toList();
        long toDue = Math.min(amountMinor, q.dueNowMinor());
        List<Allocation> allocations = new ArrayList<>();
        long principal = 0;
        long interest = 0;
        if (toDue > 0) {
            Result r = allocateV1(due, toDue);
            allocations.addAll(r.allocations());
            principal += r.principalMinor();
            interest += r.interestMinor();
        }
        List<Waiver> waivers = new ArrayList<>();
        if (payoff) {
            for (Open o : openInContractualOrder) {
                if (!o.due()) {
                    if (o.principalMinor() > 0) {
                        allocations.add(new Allocation(o.installmentId(), o.sequence(), o.principalMinor(), 0));
                        principal += o.principalMinor();
                    }
                    if (o.interestMinor() > 0) {
                        waivers.add(new Waiver(o.installmentId(), o.interestMinor()));
                    }
                }
            }
        }
        return new V2Result(new Result(V2, List.copyOf(allocations), principal, interest), List.copyOf(waivers),
                payoff);
    }

    /** An amount above what is due that is not the exact early-settlement amount. */
    public static final class PrepaymentNotSupported extends IllegalArgumentException {
        private final Quote quote;

        PrepaymentNotSupported(long amount, Quote q) {
            super("Repayment %d exceeds the %d currently due; partial prepayment is not supported (early settlement amount: %d)"
                    .formatted(amount, q.dueNowMinor(), q.payoffMinor()));
            this.quote = q;
        }

        public Quote quote() {
            return quote;
        }
    }

    public static Result allocateV1(List<Owed> owedInContractualOrder, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A repayment must be positive");
        }
        long remaining = amountMinor;
        long principal = 0;
        long interest = 0;
        List<Allocation> allocations = new ArrayList<>();
        int previous = 0;
        for (Owed owed : owedInContractualOrder) {
            if (owed.sequence() <= previous) {
                throw new IllegalArgumentException("Instalments must be supplied in contractual order");
            }
            previous = owed.sequence();
            if (remaining == 0) {
                break;
            }
            long toInterest = Math.min(remaining, owed.interestMinor());
            remaining -= toInterest;
            long toPrincipal = Math.min(remaining, owed.principalMinor());
            remaining -= toPrincipal;
            if (toInterest + toPrincipal > 0) {
                allocations.add(new Allocation(owed.installmentId(), owed.sequence(), toPrincipal, toInterest));
                principal += toPrincipal;
                interest += toInterest;
            }
        }
        if (remaining > 0) {
            throw new IllegalArgumentException(
                    "Repayment exceeds the total owed by %d minor units".formatted(remaining));
        }
        return new Result(V1, List.copyOf(allocations), principal, interest);
    }
}
