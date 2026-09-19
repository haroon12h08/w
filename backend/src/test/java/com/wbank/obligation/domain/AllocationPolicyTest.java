package com.wbank.obligation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationPolicyTest {

    private final UUID i1 = UUID.randomUUID();
    private final UUID i2 = UUID.randomUUID();
    private final List<AllocationPolicy.Owed> owed = List.of(
            new AllocationPolicy.Owed(i1, 1, 800, 200),
            new AllocationPolicy.Owed(i2, 2, 900, 100));

    @Test
    void interestBeforePrincipalOldestFirst() {
        AllocationPolicy.Result r = AllocationPolicy.allocateV1(owed, 1_150);
        assertThat(r.policy()).isEqualTo(AllocationPolicy.V1);
        assertThat(r.allocations()).containsExactly(
                new AllocationPolicy.Allocation(i1, 1, 800, 200),
                new AllocationPolicy.Allocation(i2, 2, 50, 100));
        assertThat(r.interestMinor()).isEqualTo(300);
        assertThat(r.principalMinor()).isEqualTo(850);
    }

    @Test
    void partialPaymentCoversInterestFirst() {
        AllocationPolicy.Result r = AllocationPolicy.allocateV1(owed, 150);
        assertThat(r.allocations()).containsExactly(new AllocationPolicy.Allocation(i1, 1, 0, 150));
    }

    @Test
    void overpaymentAndBadInputAreRejected() {
        assertThatThrownBy(() -> AllocationPolicy.allocateV1(owed, 2_001)).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> AllocationPolicy.allocateV1(owed, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AllocationPolicy.allocateV1(List.of(owed.get(1), owed.get(0)), 10))
                .hasMessageContaining("contractual order");
    }
}
