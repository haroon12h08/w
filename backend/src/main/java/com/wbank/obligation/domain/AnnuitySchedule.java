package com.wbank.obligation.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Contractual repayment schedule of a fixed-rate, monthly, amortising (annuity) loan.
 *
 * <p>All amounts are integer minor units; all intermediate arithmetic is exact
 * {@link BigDecimal} with 34 significant digits, and every rounding is explicit:
 *
 * <ol>
 *   <li><b>Periodic rate</b> r = annualRateBps / 10 000 / 12 (nominal annual rate, monthly
 *       compounding; every month is one equal period regardless of its length).</li>
 *   <li><b>Instalment</b> A = P·r / (1 − (1 + r)^−n), rounded <b>half-up</b> to a whole minor
 *       unit. For r = 0, A = ⌊P / n⌋. (Rounding A <em>up</em> was tried and rejected: the
 *       excess amortises principal early, compounds at (1 + r), and pays short high-rate
 *       loans off before their term.)</li>
 *   <li><b>Interest</b> of period k = outstanding(k−1) · r, rounded <b>half-even</b> to a
 *       minor unit.</li>
 *   <li><b>Principal</b> of period k = A − interest(k); the <b>final</b> period repays exactly
 *       the remaining principal, absorbing all accumulated rounding. Σ principal = P, exactly.
 *       Because rounding error compounds at (1 + r), the final instalment may differ from A by
 *       up to ((1 + r)^n − 1) / r minor units in the worst case (tiny for ordinary loans).</li>
 *   <li><b>Due date</b> of period k = start + k months (month-end clamped, always computed from
 *       the start date so dates never drift, e.g. 31 Jan → 28 Feb → 31 Mar).</li>
 * </ol>
 *
 * Deterministic: the same inputs always produce the same schedule, to the minor unit.
 */
public final class AnnuitySchedule {

    public static final String METHOD = "ANNUITY";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TEN_THOUSAND_TWELVE = BigDecimal.valueOf(120_000);

    public record Line(int sequence, LocalDate dueDate, long principalMinor, long interestMinor) {
        public long totalMinor() {
            return principalMinor + interestMinor;
        }
    }

    private AnnuitySchedule() {}

    public static BigDecimal periodicRate(int annualRateBps) {
        return BigDecimal.valueOf(annualRateBps).divide(TEN_THOUSAND_TWELVE, MC);
    }

    /** The level instalment A, in minor units (rounded up). */
    public static long instalment(long principalMinor, int annualRateBps, int count) {
        validate(principalMinor, annualRateBps, count);
        if (annualRateBps == 0) {
            return principalMinor / count;
        }
        BigDecimal r = periodicRate(annualRateBps);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(count, MC);
        BigDecimal a = BigDecimal.valueOf(principalMinor).multiply(r, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), MC);
        return a.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static List<Line> generate(long principalMinor, int annualRateBps, int count, LocalDate startDate) {
        validate(principalMinor, annualRateBps, count);
        BigDecimal r = periodicRate(annualRateBps);
        long payment = instalment(principalMinor, annualRateBps, count);
        long outstanding = principalMinor;
        List<Line> lines = new ArrayList<>(count);
        for (int k = 1; k <= count; k++) {
            long interest = BigDecimal.valueOf(outstanding).multiply(r, MC)
                    .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
            long principal = k == count ? outstanding : Math.min(Math.max(payment - interest, 0), outstanding);
            if (principal + interest == 0) {
                throw new IllegalArgumentException(("Terms are infeasible after rounding: principal %d is repaid "
                        + "before instalment %d of %d").formatted(principalMinor, k, count));
            }
            outstanding -= principal;
            lines.add(new Line(k, startDate.plusMonths(k), principal, interest));
        }
        return List.copyOf(lines);
    }

    private static void validate(long principalMinor, int annualRateBps, int count) {
        if (principalMinor <= 0) {
            throw new IllegalArgumentException("Principal must be positive");
        }
        if (annualRateBps < 0 || annualRateBps > 10_000) {
            throw new IllegalArgumentException("Annual rate must be between 0 and 10000 basis points");
        }
        if (count < 1 || count > 480) {
            throw new IllegalArgumentException("Instalment count must be between 1 and 480");
        }
        if (principalMinor < count) {
            throw new IllegalArgumentException(
                    "Principal %d is too small to spread over %d instalments".formatted(principalMinor, count));
        }
    }
}
