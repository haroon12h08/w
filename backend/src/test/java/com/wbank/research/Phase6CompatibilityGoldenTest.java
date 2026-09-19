package com.wbank.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Golden vectors captured by running THIS computation on the Phase 6 code (commit a9527be),
 * before any Phase 7 change: a fixed version-1 decision snapshot evaluated under
 * LENDING_CREDIT_POLICY v1. Phase 7 must reproduce the exact same counterfactual input and
 * output hashes: old snapshots do not silently gain information, and old experiments do not
 * silently change.
 */
class Phase6CompatibilityGoldenTest extends PostgresIntegrationTest {

    static final String GOLDEN_INPUT_HASH = "7d44c7642d0749e998cf70a334ad62c55bccbaf5c5ef0936a6a0132c1d198588";
    static final String GOLDEN_OUTPUT_HASH = "5abc17ca1bf8635faa0fd5b9cd8749dca3a0f11465390b55fc07de34e8a071da";

    static final String V1_SNAPSHOT = """
            {"schema":"credit-decision-snapshot/v1",
             "subject":{"partyId":"00000000-0000-0000-0000-00000000aaaa","customerId":"00000000-0000-0000-0000-00000000bbbb",
                        "customerStatus":"ACTIVE"},
             "application":{"currency":"USD","principalMinor":300000,"installmentCount":3,"annualRateBps":1200,
                            "settlementAccountId":"00000000-0000-0000-0000-00000000cccc"},
             "accounts":[{"accountId":"00000000-0000-0000-0000-00000000cccc","availableMinor":1000000}],
             "existingObligations":[]}""";

    @Autowired CounterfactualEvaluator evaluator;
    @Autowired SnapshotDecisionFacts snapshotFacts;
    @Autowired CreditPolicyService policies;
    @Autowired ObjectMapper json;

    CounterfactualDecision evaluateV1Snapshot() throws Exception {
        JsonNode snapshot = json.readTree(V1_SNAPSHOT);
        SourceDecision src = new SourceDecision(UUID.fromString("00000000-0000-0000-0000-00000000dddd"),
                UUID.fromString("00000000-0000-0000-0000-00000000eeee"), "APPROVED",
                Instant.parse("2025-06-01T10:00:00Z"), 2, CreditPolicy.LENDING, 1,
                UUID.fromString("00000000-0000-0000-0000-00000000ffff"), "0".repeat(64), snapshot);
        return evaluator.prepare(src, policies.require(CreditPolicy.LENDING, 1), snapshotFacts, null);
    }

    @Test
    void aVersion1SnapshotEvaluatesExactlyAsInPhase6() throws Exception {
        CounterfactualDecision c = evaluateV1Snapshot();
        assertThat(c.getHypotheticalDecision()).isEqualTo("APPROVED");
        assertThat(c.getInputHash()).isEqualTo(GOLDEN_INPUT_HASH);
        assertThat(c.getOutputHash()).isEqualTo(GOLDEN_OUTPUT_HASH);
    }
}
