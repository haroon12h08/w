package com.wbank.obligation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LendingRulesTest {

    private final LocalDate feb15 = LocalDate.of(2026, 2, 15);
    private final LocalDate mar15 = LocalDate.of(2026, 3, 15);

    @Test
    void delinquencyIsMeasuredFromTheOldestUnpaidDueDate() {
        List<Delinquency.Line> s = List.of(
                new Delinquency.Line(1, feb15, 800, 200, 800, 200, 0),   // paid
                new Delinquency.Line(2, mar15, 800, 150, 0, 100, 0),     // partly paid
                new Delinquency.Line(3, LocalDate.of(2026, 4, 15), 800, 100, 0, 0, 0));
        assertThat(Delinquency.evaluate(s, mar15).bucket()).isEqualTo(Delinquency.Bucket.CURRENT); // due today
        Delinquency.Status late = Delinquency.evaluate(s, LocalDate.of(2026, 4, 14));
        assertThat(late.daysPastDue()).isEqualTo(30);
        assertThat(late.bucket()).isEqualTo(Delinquency.Bucket.DPD_30_59);
        assertThat(late.overduePrincipalMinor()).isEqualTo(800);
        assertThat(late.overdueInterestMinor()).isEqualTo(50);
        assertThat(Delinquency.evaluate(s, LocalDate.of(2026, 6, 13)).bucket()).isEqualTo(Delinquency.Bucket.DPD_90_PLUS);
    }

    @Test
    void bucketBoundaries() {
        assertThat(Delinquency.Bucket.of(0)).isEqualTo(Delinquency.Bucket.CURRENT);
        assertThat(Delinquency.Bucket.of(1)).isEqualTo(Delinquency.Bucket.DPD_1_29);
        assertThat(Delinquency.Bucket.of(29)).isEqualTo(Delinquency.Bucket.DPD_1_29);
        assertThat(Delinquency.Bucket.of(30)).isEqualTo(Delinquency.Bucket.DPD_30_59);
        assertThat(Delinquency.Bucket.of(60)).isEqualTo(Delinquency.Bucket.DPD_60_89);
        assertThat(Delinquency.Bucket.of(89)).isEqualTo(Delinquency.Bucket.DPD_60_89);
        assertThat(Delinquency.Bucket.of(90)).isEqualTo(Delinquency.Bucket.DPD_90_PLUS);
    }

    @Test
    void v2PaysOnlyWhatIsDueExceptAnExactEarlySettlement() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<AllocationPolicy.Open> open = List.of(
                new AllocationPolicy.Open(a, 1, true, 800, 200),
                new AllocationPolicy.Open(b, 2, false, 850, 150),
                new AllocationPolicy.Open(c, 3, false, 900, 100));
        assertThat(AllocationPolicy.quoteV2(open)).isEqualTo(new AllocationPolicy.Quote(1_000, 1_000 + 850 + 900));

        AllocationPolicy.V2Result partial = AllocationPolicy.allocateV2(open, 300);
        assertThat(partial.result().allocations()).containsExactly(new AllocationPolicy.Allocation(a, 1, 100, 200));
        assertThat(partial.waivers()).isEmpty();

        assertThatThrownBy(() -> AllocationPolicy.allocateV2(open, 1_001))
                .isInstanceOf(AllocationPolicy.PrepaymentNotSupported.class);
        assertThatThrownBy(() -> AllocationPolicy.allocateV2(open, 2_751)).hasMessageContaining("exceeds");

        AllocationPolicy.V2Result payoff = AllocationPolicy.allocateV2(open, 2_750);
        assertThat(payoff.fullSettlement()).isTrue();
        assertThat(payoff.result().principalMinor()).isEqualTo(800 + 850 + 900);
        assertThat(payoff.result().interestMinor()).isEqualTo(200);
        assertThat(payoff.waivers()).containsExactly(new AllocationPolicy.Waiver(b, 150), new AllocationPolicy.Waiver(c, 100));
    }
}
