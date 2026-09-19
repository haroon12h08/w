package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A historical credit decision as the research engine sees it: only immutable records
 * (the decision row, its history event and its verified snapshot). {@code snapshot} is null
 * for decisions made before snapshots existed; such decisions are not evaluable.
 */
public record SourceDecision(UUID decisionId, UUID obligationId, String actualDecision, Instant decidedAt,
                             int decisionLoanSeq, String actualPolicyCode, Integer actualPolicyVersion,
                             UUID snapshotId, String snapshotSha256, JsonNode snapshot) {}
