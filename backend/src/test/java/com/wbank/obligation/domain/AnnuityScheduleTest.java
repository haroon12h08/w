package com.wbank.obligation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnnuityScheduleTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 31);

    @Test
    void knownAnswer_10000_00_at_12pct_over_12_months() {
        // P = 1,000,000 minor units, r = 1% per month, n = 12.
        // A = P·r/(1-(1+r)^-12) = 88,848.79 -> rounded half-up to 88,849.
        assertThat(AnnuitySchedule.instalment(1_000_000, 1_200, 12)).isEqualTo(88_849);
        List<AnnuitySchedule.Line> s = AnnuitySchedule.generate(1_000_000, 1_200, 12, START);

        // Period 1: interest = 1,000,000 × 1% = 10,000; principal = 88,849 − 10,000 = 78,849.
        assertThat(s.get(0)).isEqualTo(new AnnuitySchedule.Line(1, LocalDate.of(2026, 2, 28), 78_849, 10_000));
        // Period 2: outstanding 921,151 × 1% = 9,211.51 -> half-even 9,212.
        assertThat(s.get(1).interestMinor()).isEqualTo(9_212);
        assertThat(s.get(1).principalMinor()).isEqualTo(88_849 - 9_212);
        assertThat(s).hasSize(12);
        assertThat(s.stream().mapToLong(AnnuitySchedule.Line::principalMinor).sum()).isEqualTo(1_000_000);
        // Every instalment but the last equals A; the last absorbs rounding and is not larger.
        assertThat(s.subList(0, 11)).allSatisfy(l -> assertThat(l.totalMinor()).isEqualTo(88_849));
        assertThat(s.get(11).totalMinor()).isBetween(88_800L, 88_849L);
    }

    @Test
    void dueDatesAreMonthEndClampedFromTheStartAndDoNotDrift() {
        List<AnnuitySchedule.Line> s = AnnuitySchedule.generate(300_000, 500, 3, START);
        assertThat(s).extracting(AnnuitySchedule.Line::dueDate).containsExactly(
                LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
    }

    @Test
    void zeroRateSplitsPrincipalEvenlyWithTheRemainderLast() {
        List<AnnuitySchedule.Line> s = AnnuitySchedule.generate(1_000, 0, 3, START);
        assertThat(s).extracting(AnnuitySchedule.Line::principalMinor).containsExactly(333L, 333L, 334L);
        assertThat(s).allSatisfy(l -> assertThat(l.interestMinor()).isZero());
    }

    @ParameterizedTest
    @CsvSource({"1, 0, 1", "100, 10000, 1", "99999999, 1, 480", "123456789, 725, 360", "5000, 9999, 60", "480, 10000, 480", "250000000, 1999, 360",
            "1000000000000, 499, 240"})
    void invariantsHoldAcrossTheParameterSpace(long principal, int bps, int n) {
        List<AnnuitySchedule.Line> s = AnnuitySchedule.generate(principal, bps, n, START);
        long a = AnnuitySchedule.instalment(principal, bps, n);
        assertThat(s).hasSize(n);
        assertThat(s.stream().mapToLong(AnnuitySchedule.Line::principalMinor).sum()).isEqualTo(principal);
        assertThat(s).allSatisfy(l -> {
            assertThat(l.principalMinor()).isNotNegative();
            assertThat(l.interestMinor()).isNotNegative();
            assertThat(l.totalMinor()).isPositive();
        });
        // All but the final instalment are exactly A.
        assertThat(s.subList(0, n - 1)).allSatisfy(l -> assertThat(l.totalMinor()).isEqualTo(a));
        // The final instalment absorbs rounding. Each period's rounding error is < 1 minor unit
        // and compounds at (1+r), so |final - A| <= ((1+r)^n - 1)/r (or n when r = 0).
        double r = bps / 120_000.0;
        double bound = r == 0 ? n : (Math.pow(1 + r, n) - 1) / r;
        assertThat((double) Math.abs(s.get(n - 1).totalMinor() - a)).isLessThanOrEqualTo(bound);
        // Deterministic: identical inputs, identical schedule.
        assertThat(AnnuitySchedule.generate(principal, bps, n, START)).isEqualTo(s);
    }

    @Test
    void invalidTermsAreRejected() {
        assertThatThrownBy(() -> AnnuitySchedule.generate(0, 500, 12, START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnnuitySchedule.generate(1000, -1, 12, START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnnuitySchedule.generate(1000, 10_001, 12, START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnnuitySchedule.generate(1000, 500, 0, START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnnuitySchedule.generate(1000, 500, 481, START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnnuitySchedule.generate(11, 500, 12, START))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too small");
    }
}
