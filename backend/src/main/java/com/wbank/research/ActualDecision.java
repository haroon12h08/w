package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * What the bank actually decided: read from the immutable banking record (loan_decision,
 * its snapshot). Deliberately a different type from {@link CounterfactualDecision}: an actual
 * decision happened and had consequences; a counterfactual is a computed hypothesis.
 */
public record ActualDecision(UUID decisionId, UUID obligationId, String decision, Instant decidedAt, String decidedBy,
                             String policyCode, Integer policyVersion, UUID snapshotId, String snapshotSha256,
                             JsonNode recordedRuleEvaluation) {}
