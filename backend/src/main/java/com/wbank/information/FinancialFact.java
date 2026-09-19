package com.wbank.information;

import com.wbank.platform.context.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One observation about a party's financial situation: an income, a recurring obligation,
 * a statement that the recorded obligations are complete, or an employment status.
 *
 * <p>Append-only and bitemporal. {@code effectiveAt} is when the observation became true,
 * {@code recordedAt} when the bank learned it (system-assigned, never earlier than
 * {@code effectiveAt}). {@code appliesFrom} is the business date from which the amount itself
 * applies (a contract signed today may start next month). Later knowledge about the same
 * thing (a new amount, a verification, an ending) is a new observation in the same
 * {@code seriesId}; nothing is ever edited.
 */
@Entity
@Immutable
@Table(name = "borrower_financial_fact")
public class FinancialFact {

    public enum Kind { INCOME, RECURRING_OBLIGATION, OBLIGATION_DISCLOSURE, EMPLOYMENT }

    public enum Provenance { DECLARED, VERIFIED }

    public enum Frequency { MONTHLY, ANNUAL }

    public enum Status { ACTIVE, ENDED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Database-assigned insertion order (tie-break only). */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "series_id", nullable = false, updatable = false)
    private UUID seriesId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_kind", nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "fact_type", nullable = false, updatable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private Status status;

    @Column(name = "amount_minor", updatable = false)
    private Long amountMinor;

    @Column(name = "currency", updatable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", updatable = false)
    private Frequency frequency;

    @Column(name = "outstanding_minor", updatable = false)
    private Long outstandingMinor;

    @Column(name = "employment_start_date", updatable = false)
    private LocalDate employmentStartDate;

    @Column(name = "applies_from", updatable = false)
    private LocalDate appliesFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, updatable = false)
    private Provenance provenance;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "evidence_reference", updatable = false)
    private String evidenceReference;

    @Column(name = "verifies_fact_id", updatable = false)
    private UUID verifiesFactId;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private String recordedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "recorder_type", nullable = false, updatable = false)
    private ActorType recorderType;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    protected FinancialFact() {
        // for JPA
    }

    static FinancialFact of(UUID partyId, UUID seriesId, Kind kind, String type, Status status, Long amountMinor,
                            String currency, Frequency frequency, Long outstandingMinor, LocalDate employmentStartDate,
                            LocalDate appliesFrom, Provenance provenance, String source, String evidenceReference,
                            UUID verifiesFactId, Instant effectiveAt, Instant recordedAt, String recordedBy,
                            ActorType recorderType, UUID correlationId) {
        FinancialFact f = new FinancialFact();
        f.id = UUID.randomUUID();
        f.partyId = partyId;
        f.seriesId = seriesId;
        f.kind = kind;
        f.type = type;
        f.status = status;
        f.amountMinor = amountMinor;
        f.currency = currency;
        f.frequency = frequency;
        f.outstandingMinor = outstandingMinor;
        f.employmentStartDate = employmentStartDate;
        f.appliesFrom = appliesFrom;
        f.provenance = provenance;
        f.source = source;
        f.evidenceReference = evidenceReference;
        f.verifiesFactId = verifiesFactId;
        f.effectiveAt = effectiveAt;
        f.recordedAt = recordedAt;
        f.recordedBy = recordedBy;
        f.recorderType = recorderType;
        f.correlationId = correlationId;
        return f;
    }

    public FactView view() {
        return new FactView(id, seq == null ? 0 : seq, seriesId, kind.name(), type, status.name(), amountMinor, currency,
                frequency == null ? null : frequency.name(), outstandingMinor, employmentStartDate, appliesFrom,
                provenance.name(), source, evidenceReference, verifiesFactId, effectiveAt, recordedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public UUID getSeriesId() {
        return seriesId;
    }

    public Kind getKind() {
        return kind;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Provenance getProvenance() {
        return provenance;
    }
}
