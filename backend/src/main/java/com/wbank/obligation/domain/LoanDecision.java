package com.wbank.obligation.domain;

import com.wbank.platform.context.ActorType;
import com.wbank.platform.context.OperationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A credit decision taken about a loan (approve, decline, declare default): who decided,
 * when, why, and on what evidence. Append-only. These are the labelled outcomes a later
 * decision-evaluation layer will learn from, so the evidence captured is what was known
 * <em>at decision time</em>.
 */
@Entity
@Immutable
@Table(name = "loan_decision")
public class LoanDecision {

    public enum Kind { APPROVED, DECLINED, DEFAULT_DECLARED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false)
    private Kind decision;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private String decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "decider_type", nullable = false, updatable = false)
    private ActorType deciderType;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @Column(name = "rationale", nullable = false, updatable = false)
    private String rationale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String evidence;

    /** The credit policy version in force when the decision was made (null only before V10). */
    @Column(name = "policy_code", updatable = false)
    private String policyCode;

    @Column(name = "policy_version", updatable = false)
    private Integer policyVersion;

    protected LoanDecision() {
        // for JPA
    }

    public static LoanDecision record(UUID obligationId, Kind kind, String rationale, String evidenceJson,
                                      String policyCode, int policyVersion, OperationContext context, Instant now) {
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("A credit decision requires a rationale");
        }
        LoanDecision d = new LoanDecision();
        d.id = UUID.randomUUID();
        d.obligationId = obligationId;
        d.decision = kind;
        d.decidedBy = context.actor();
        d.deciderType = context.actorType();
        d.correlationId = context.correlationId();
        d.decidedAt = now;
        d.rationale = rationale.strip();
        d.evidence = evidenceJson;
        d.policyCode = policyCode;
        d.policyVersion = policyVersion;
        return d;
    }

    public UUID getId() {
        return id;
    }

    public UUID getObligationId() {
        return obligationId;
    }

    public String getPolicyCode() {
        return policyCode;
    }

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Kind getDecision() {
        return decision;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public ActorType getDeciderType() {
        return deciderType;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getRationale() {
        return rationale;
    }

    public String getEvidence() {
        return evidence;
    }
}
