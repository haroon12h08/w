package com.wbank.information;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A recorded affordability calculation: version, exact input, output and their hashes. Append-only. */
@Entity
@Immutable
@Table(name = "affordability_assessment")
public class AffordabilityAssessment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "obligation_id", updatable = false)
    private UUID obligationId;

    @Column(name = "purpose", nullable = false, updatable = false)
    private String purpose;

    @Column(name = "calculation_version", nullable = false, updatable = false)
    private String calculationVersion;

    @Column(name = "as_of", nullable = false, updatable = false)
    private Instant asOf;

    @Column(name = "known_at", nullable = false, updatable = false)
    private Instant knownAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String input;

    @Column(name = "input_hash", nullable = false, updatable = false)
    private String inputHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String output;

    @Column(name = "output_hash", nullable = false, updatable = false)
    private String outputHash;

    @Column(name = "completeness", nullable = false, updatable = false)
    private String completeness;

    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    protected AffordabilityAssessment() {
        // for JPA
    }

    static AffordabilityAssessment of(UUID partyId, UUID obligationId, String purpose, Instant asOf, Instant knownAt,
                                      String input, String inputHash, String output, String outputHash,
                                      String completeness, Instant computedAt) {
        AffordabilityAssessment a = new AffordabilityAssessment();
        a.id = UUID.randomUUID();
        a.partyId = partyId;
        a.obligationId = obligationId;
        a.purpose = purpose;
        a.calculationVersion = AffordabilityCalculator.VERSION;
        a.asOf = asOf;
        a.knownAt = knownAt;
        a.input = input;
        a.inputHash = inputHash;
        a.output = output;
        a.outputHash = outputHash;
        a.completeness = completeness;
        a.computedAt = computedAt;
        return a;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public Instant getKnownAt() {
        return knownAt;
    }

    public String getInput() {
        return input;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getOutput() {
        return output;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public String getCompleteness() {
        return completeness;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
