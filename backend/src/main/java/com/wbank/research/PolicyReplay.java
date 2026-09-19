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

/** One replay of a labelled population of historical decisions under an alternative policy. */
@Entity
@Immutable
@Table(name = "research_policy_replay")
public class PolicyReplay {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "alternative_policy_code", nullable = false, updatable = false)
    private String alternativePolicyCode;

    @Column(name = "alternative_policy_version", nullable = false, updatable = false)
    private int alternativePolicyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "population", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String population;

    @Column(name = "decision_count", nullable = false, updatable = false)
    private int decisionCount;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "evaluated_by", nullable = false, updatable = false)
    private String evaluatedBy;

    @Column(name = "input_hash", nullable = false, updatable = false)
    private String inputHash;

    @Column(name = "output_hash", nullable = false, updatable = false)
    private String outputHash;

    protected PolicyReplay() {
        // for JPA
    }

    static PolicyReplay of(UUID id, String altCode, int altVersion, String populationJson, int count,
                           Instant evaluatedAt, String evaluatedBy, String inputHash, String outputHash) {
        PolicyReplay r = new PolicyReplay();
        r.id = id;
        r.alternativePolicyCode = altCode;
        r.alternativePolicyVersion = altVersion;
        r.population = populationJson;
        r.decisionCount = count;
        r.evaluatedAt = evaluatedAt;
        r.evaluatedBy = evaluatedBy;
        r.inputHash = inputHash;
        r.outputHash = outputHash;
        return r;
    }

    public UUID getId() {
        return id;
    }

    public String getAlternativePolicyCode() {
        return alternativePolicyCode;
    }

    public int getAlternativePolicyVersion() {
        return alternativePolicyVersion;
    }

    public String getPopulation() {
        return population;
    }

    public int getDecisionCount() {
        return decisionCount;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public String getEvaluatedBy() {
        return evaluatedBy;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getOutputHash() {
        return outputHash;
    }
}
