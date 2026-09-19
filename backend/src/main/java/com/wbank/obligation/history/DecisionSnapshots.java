package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists decision snapshots and verifies their integrity. */
@Component
public class DecisionSnapshots {

    /** Phase 5-6 snapshots. Never rewritten; they remain valid version-1 snapshots forever. */
    public static final String SCHEMA_V1 = "credit-decision-snapshot/v1";
    /** Phase 7: adds the financialInformation section (facts, provenance, affordability, completeness). */
    public static final String SCHEMA = "credit-decision-snapshot/v2";

    private final CreditDecisionSnapshotRepository snapshots;
    private final ObjectMapper json;

    public DecisionSnapshots(CreditDecisionSnapshotRepository snapshots, ObjectMapper json) {
        this.snapshots = snapshots;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CreditDecisionSnapshot capture(UUID decisionId, UUID obligationId, CreditPolicy policy, Instant decidedAt,
                                          Map<String, Object> content) {
        String canonical = CanonicalJson.canonical(json, json.valueToTree(content));
        return snapshots.save(CreditDecisionSnapshot.of(decisionId, obligationId, policy, decidedAt, canonical));
    }

    /** True if the stored content still hashes to the value recorded at decision time. */
    public boolean verify(CreditDecisionSnapshot s) {
        try {
            JsonNode stored = json.readTree(s.getContent());
            return CanonicalJson.sha256(CanonicalJson.canonical(json, stored)).equals(s.getContentSha256());
        } catch (Exception e) {
            return false;
        }
    }
}
