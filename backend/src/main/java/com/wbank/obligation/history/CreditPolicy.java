package com.wbank.obligation.history;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One version of the bank's credit rules. Versions are append-only and never retroactive:
 * a decision always cites the version that was in force when it was made, and that
 * version's text can never change afterwards.
 */
@Entity
@Immutable
@Table(name = "credit_policy")
public class CreditPolicy {

    public static final String LENDING = "LENDING_CREDIT_POLICY";

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "policy_code", nullable = false, updatable = false)
        private String policyCode;

        @Column(name = "version", nullable = false, updatable = false)
        private int version;

        protected Key() {
        }

        public Key(String policyCode, int version) {
            this.policyCode = policyCode;
            this.version = version;
        }

        public String getPolicyCode() {
            return policyCode;
        }

        public int getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.policyCode.equals(policyCode) && k.version == version;
        }

        @Override
        public int hashCode() {
            return Objects.hash(policyCode, version);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String rules;

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "published_by", nullable = false, updatable = false)
    private String publishedBy;

    protected CreditPolicy() {
        // for JPA
    }

    static CreditPolicy publish(String code, int version, Instant effectiveFrom, String rulesJson, String description,
                                Instant publishedAt, String publishedBy) {
        CreditPolicy p = new CreditPolicy();
        p.id = new Key(code, version);
        p.effectiveFrom = effectiveFrom;
        p.rules = rulesJson;
        p.description = description;
        p.publishedAt = publishedAt;
        p.publishedBy = publishedBy;
        return p;
    }

    public String getPolicyCode() {
        return id.getPolicyCode();
    }

    public int getVersion() {
        return id.getVersion();
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getRules() {
        return rules;
    }

    public String getDescription() {
        return description;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }
}
