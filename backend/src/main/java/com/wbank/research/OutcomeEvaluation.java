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
 * A separate, later step: a replay's counterfactual decisions set against the outcomes that
 * were observed (only for historically approved applications), as known at a stated instant.
 */
@Entity
@Immutable
@Table(name = "research_outcome_evaluation")
public class OutcomeEvaluation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "replay_id", nullable = false, updatable = false)
    private UUID replayId;

    @Column(name = "outcome_horizon", nullable = false, updatable = false)
    private String outcomeHorizon;

    @Column(name = "outcome_known_at", nullable = false, updatable = false)
    private Instant outcomeKnownAt;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "evaluated_by", nullable = false, updatable = false)
    private String evaluatedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String report;

    @Column(name = "report_sha256", nullable = false, updatable = false)
    private String reportSha256;

    protected OutcomeEvaluation() {
        // for JPA
    }

    static OutcomeEvaluation of(UUID replayId, String horizon, Instant outcomeKnownAt, Instant evaluatedAt,
                                String evaluatedBy, String reportJson, String reportSha256) {
        OutcomeEvaluation e = new OutcomeEvaluation();
        e.id = UUID.randomUUID();
        e.replayId = replayId;
        e.outcomeHorizon = horizon;
        e.outcomeKnownAt = outcomeKnownAt;
        e.evaluatedAt = evaluatedAt;
        e.evaluatedBy = evaluatedBy;
        e.report = reportJson;
        e.reportSha256 = reportSha256;
        return e;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReplayId() {
        return replayId;
    }

    public String getOutcomeHorizon() {
        return outcomeHorizon;
    }

    public Instant getOutcomeKnownAt() {
        return outcomeKnownAt;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public String getReport() {
        return report;
    }

    public String getReportSha256() {
        return reportSha256;
    }
}
