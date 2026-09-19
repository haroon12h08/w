package com.wbank.obligation.history;

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
 * What the bank knew, and how it reasoned, at the moment it made a credit decision.
 * Captured from live state in the decision's own transaction, hashed, and never changed.
 */
@Entity
@Immutable
@Table(name = "credit_decision_snapshot")
public class CreditDecisionSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Column(name = "policy_code", nullable = false, updatable = false)
    private String policyCode;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String content;

    @Column(name = "content_sha256", nullable = false, updatable = false)
    private String contentSha256;

    protected CreditDecisionSnapshot() {
        // for JPA
    }

    static CreditDecisionSnapshot of(UUID decisionId, UUID obligationId, CreditPolicy policy, Instant decidedAt,
                                     String canonicalContent) {
        CreditDecisionSnapshot s = new CreditDecisionSnapshot();
        s.id = UUID.randomUUID();
        s.decisionId = decisionId;
        s.obligationId = obligationId;
        s.policyCode = policy.getPolicyCode();
        s.policyVersion = policy.getVersion();
        s.decidedAt = decidedAt;
        s.content = canonicalContent;
        s.contentSha256 = CanonicalJson.sha256(canonicalContent);
        return s;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public UUID getObligationId() {
        return obligationId;
    }

    public String getPolicyCode() {
        return policyCode;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getContent() {
        return content;
    }

    public String getContentSha256() {
        return contentSha256;
    }
}
