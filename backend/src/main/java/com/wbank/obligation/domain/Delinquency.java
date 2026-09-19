package com.wbank.obligation.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Delinquency derived from observable facts: which instalments were due before
 * {@code asOf} and are still not fully satisfied. Nothing here is a prediction.
 *
 * <p>An instalment is <b>overdue</b> on the day after its due date if anything of it remains
 * unpaid (payment on the due date itself is on time). Days past due (DPD) is measured from
 * the <b>oldest</b> unpaid due date, the industry-standard convention.
 */
public final class Delinquency {

    public enum Bucket {
        CURRENT, DPD_1_29, DPD_30_59, DPD_60_89, DPD_90_PLUS;

        public static Bucket of(long dpd) {
            if (dpd <= 0) {
                return CURRENT;
            }
            if (dpd < 30) {
                return DPD_1_29;
            }
            if (dpd < 60) {
                return DPD_30_59;
            }
            return dpd < 90 ? DPD_60_89 : DPD_90_PLUS;
        }
    }

    /** One instalment's contractual and actual position. */
    public record Line(int sequence, LocalDate dueDate, long principalDue, long interestDue, long principalPaid,
                       long interestPaid, long interestWaived) {
        public long principalUnpaid() {
            return principalDue - principalPaid;
        }

        public long interestUnpaid() {
            return interestDue - interestPaid - interestWaived;
        }

        public boolean satisfied() {
            return principalUnpaid() == 0 && interestUnpaid() == 0;
        }
    }

    public record Status(LocalDate asOf, long daysPastDue, Bucket bucket, long overduePrincipalMinor,
                         long overdueInterestMinor, LocalDate oldestUnpaidDueDate, int overdueInstallments) {}

    public static final long DEFAULT_THRESHOLD_DAYS = 90;

    private Delinquency() {}

    public static Status evaluate(List<Line> schedule, LocalDate asOf) {
        long overduePrincipal = 0;
        long overdueInterest = 0;
        int count = 0;
        LocalDate oldest = null;
        for (Line l : schedule) {
            if (l.dueDate().isBefore(asOf) && !l.satisfied()) {
                overduePrincipal += l.principalUnpaid();
                overdueInterest += l.interestUnpaid();
                count++;
                if (oldest == null || l.dueDate().isBefore(oldest)) {
                    oldest = l.dueDate();
                }
            }
        }
        long dpd = oldest == null ? 0 : ChronoUnit.DAYS.between(oldest, asOf);
        return new Status(asOf, dpd, Bucket.of(dpd), overduePrincipal, overdueInterest, oldest, count);
    }
}
