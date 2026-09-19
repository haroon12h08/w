package com.wbank.information;

import com.wbank.party.PartyService;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and retrieves borrower financial observations. Recording only ever appends; the
 * record time is the bank's clock, never a caller's claim.
 */
@Service
public class FinancialInformationService {

    private final FinancialFactRepository facts;
    private final PartyService parties;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public FinancialInformationService(FinancialFactRepository facts, PartyService parties, AuditTrail auditTrail,
                                       Clock clock) {
        this.facts = facts;
        this.parties = parties;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /** What is being recorded. {@code seriesId} null starts a new series (unless it verifies an earlier fact). */
    public record Observation(FinancialFact.Kind kind, String type, UUID seriesId, FinancialFact.Status status,
                              Long amountMinor, String currency, FinancialFact.Frequency frequency, Long outstandingMinor,
                              LocalDate employmentStartDate, LocalDate appliesFrom, FinancialFact.Provenance provenance,
                              String source, String evidenceReference, UUID verifiesFactId, Instant effectiveAt) {}

    public record Visible(UUID partyId, Instant asOf, Instant knownAt, List<FactView> observations,
                          List<FactView> currentPerSeries, int notYetVisible) {}

    @Transactional
    public FinancialFact record(UUID partyId, Observation o) {
        parties.require(partyId);
        OperationContext context = RequestContext.forOperation("financial_information.record");
        Instant now = clock.instant();
        Instant effective = o.effectiveAt() == null ? now : o.effectiveAt();
        if (effective.isAfter(now)) {
            throw new IllegalArgumentException("An observation cannot have become true after it is recorded; use "
                    + "appliesFrom for amounts that start in the future");
        }
        if (o.provenance() == FinancialFact.Provenance.VERIFIED
                && (o.evidenceReference() == null || o.evidenceReference().isBlank())) {
            throw new IllegalArgumentException("A VERIFIED observation requires an evidence reference");
        }
        UUID series = o.seriesId();
        if (o.verifiesFactId() != null) {
            FinancialFact target = facts.findById(o.verifiesFactId())
                    .orElseThrow(() -> NotFoundException.of("financial_fact", o.verifiesFactId()));
            if (!target.getPartyId().equals(partyId)) {
                throw new IllegalArgumentException("A verification must concern the same party");
            }
            if (o.provenance() != FinancialFact.Provenance.VERIFIED) {
                throw new IllegalArgumentException("A verification must be VERIFIED");
            }
            series = series == null ? target.getSeriesId() : series;
        }
        FinancialFact f = facts.saveAndFlush(FinancialFact.of(partyId, series == null ? UUID.randomUUID() : series,
                o.kind(), o.type(), o.status() == null ? FinancialFact.Status.ACTIVE : o.status(), o.amountMinor(),
                o.currency(), o.frequency(), o.outstandingMinor(), o.employmentStartDate(), o.appliesFrom(),
                o.provenance(), o.source(), o.evidenceReference() == null ? null : o.evidenceReference().strip(),
                o.verifiesFactId(), effective, now, context.actor(), context.actorType(), context.correlationId()));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("factId", f.getId().toString());
        audit.put("kind", o.kind().name());
        audit.put("provenance", o.provenance().name());
        auditTrail.record("financial_information.recorded", "party", partyId, context, audit);
        return f;
    }

    @Transactional(readOnly = true)
    public List<FactView> all(UUID partyId) {
        return facts.findByPartyIdOrderBySeqAsc(partyId).stream().map(FinancialFact::view).toList();
    }

    /** What the bank knew about the party's finances at {@code knownAt}, as they stood at {@code asOf}. */
    @Transactional(readOnly = true)
    public Visible visibleAt(UUID partyId, Instant asOf, Instant knownAt) {
        List<FactView> all = all(partyId);
        Instant k = knownAt == null ? asOf : knownAt;
        List<FactView> visible = FactSelection.visible(all, asOf, k);
        return new Visible(partyId, asOf, k, visible, FactSelection.currentPerSeries(all, asOf, k),
                all.size() - visible.size());
    }

    /** "Where did this number come from?": every observation in the fact's series, in recorded order. */
    @Transactional(readOnly = true)
    public List<FactView> provenance(UUID factId) {
        FinancialFact f = facts.findById(factId).orElseThrow(() -> NotFoundException.of("financial_fact", factId));
        return facts.findBySeriesIdOrderBySeqAsc(f.getSeriesId()).stream().map(FinancialFact::view).toList();
    }
}
