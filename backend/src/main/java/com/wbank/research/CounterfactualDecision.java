package com.wbank.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What an alternative policy WOULD have decided for one historical decision, computed from
 * that decision's snapshot alone. An experiment result, immutable, stored in a research
 * table; never a banking event and never a change to the actual decision.
 *
 * <p>{@code input} is the complete, self-contained evaluation input (decision-time facts and
 * the compiled alternative rules); {@code inputHash}/{@code outputHash} let anyone recompute
 * the result from that input and prove it identical.
 */
@Entity
@Immutable
@Table(name = "research_counterfactual_decision")
public class CounterfactualDecision {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "replay_id", updatable = false)
    private UUID replayId;

    @Column(name = "source_decision_id", nullable = false, updatable = false)
    private UUID sourceDecisionId;

    @Column(name = "source_snapshot_id", updatable = false)
    private UUID sourceSnapshotId;

    @Column(name = "source_snapshot_sha256", updatable = false)
    private String sourceSnapshotSha256;

    @Column(name = "actual_decision", nullable = false, updatable = false)
    private String actualDecision;

    @Column(name = "actual_policy_code", updatable = false)
    private String actualPolicyCode;

    @Column(name = "actual_policy_version", updatable = false)
    private Integer actualPolicyVersion;

    @Column(name = "alternative_policy_code", nullable = false, updatable = false)
    private String alternativePolicyCode;

    @Column(name = "alternative_policy_version", nullable = false, updatable = false)
    private int alternativePolicyVersion;

    @Column(name = "original_decided_at", nullable = false, updatable = false)
    private Instant originalDecidedAt;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "evaluated_by", nullable = false, updatable = false)
    private String evaluatedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String input;

    @Column(name = "input_hash", nullable = false, updatable = false)
    private String inputHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_results", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String ruleResults;

    @Column(name = "policy_result", nullable = false, updatable = false)
    private String policyResult;

    @Column(name = "control_policy_result", updatable = false)
    private String controlPolicyResult;

    @Column(name = "hypothetical_decision", nullable = false, updatable = false)
    private String hypotheticalDecision;

    @Column(name = "output_hash", nullable = false, updatable = false)
    private String outputHash;

    @Column(name = "context_check", nullable = false, updatable = false)
    private String contextCheck;

    protected CounterfactualDecision() {
        // for JPA
    }

    static CounterfactualDecision of(UUID replayId, SourceDecision src, String altCode, int altVersion,
                                     Instant evaluatedAt, String evaluatedBy, String inputJson, String inputHash,
                                     String ruleResultsJson, String policyResult, String controlResult,
                                     String hypotheticalDecision, String outputHash, String contextCheck) {
        CounterfactualDecision c = new CounterfactualDecision();
        c.id = UUID.randomUUID();
        c.replayId = replayId;
        c.sourceDecisionId = src.decisionId();
        c.sourceSnapshotId = src.snapshotId();
        c.sourceSnapshotSha256 = src.snapshotSha256();
        c.actualDecision = src.actualDecision();
        c.actualPolicyCode = src.actualPolicyCode();
        c.actualPolicyVersion = src.actualPolicyVersion();
        c.alternativePolicyCode = altCode;
        c.alternativePolicyVersion = altVersion;
        c.originalDecidedAt = src.decidedAt();
        c.evaluatedAt = evaluatedAt;
        c.evaluatedBy = evaluatedBy;
        c.input = inputJson;
        c.inputHash = inputHash;
        c.ruleResults = ruleResultsJson;
        c.policyResult = policyResult;
        c.controlPolicyResult = controlResult;
        c.hypotheticalDecision = hypotheticalDecision;
        c.outputHash = outputHash;
        c.contextCheck = contextCheck;
        return c;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReplayId() {
        return replayId;
    }

    public UUID getSourceDecisionId() {
        return sourceDecisionId;
    }

    public UUID getSourceSnapshotId() {
        return sourceSnapshotId;
    }

    public String getSourceSnapshotSha256() {
        return sourceSnapshotSha256;
    }

    public String getActualDecision() {
        return actualDecision;
    }

    public String getActualPolicyCode() {
        return actualPolicyCode;
    }

    public Integer getActualPolicyVersion() {
        return actualPolicyVersion;
    }

    public String getAlternativePolicyCode() {
        return alternativePolicyCode;
    }

    public int getAlternativePolicyVersion() {
        return alternativePolicyVersion;
    }

    public Instant getOriginalDecidedAt() {
        return originalDecidedAt;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public String getEvaluatedBy() {
        return evaluatedBy;
    }

    public String getInput() {
        return input;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getRuleResults() {
        return ruleResults;
    }

    public String getPolicyResult() {
        return policyResult;
    }

    public String getControlPolicyResult() {
        return controlPolicyResult;
    }

    public String getHypotheticalDecision() {
        return hypotheticalDecision;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public String getContextCheck() {
        return contextCheck;
    }
}
