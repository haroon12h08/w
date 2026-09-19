package com.wbank.information;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.DecisionSnapshots;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.MoneyParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borrower financial information: record observations (append-only), read what was visible at
 * a point in time, trace provenance, and compute/reproduce affordability. Nothing here edits a
 * fact, a snapshot or a decision.
 */
@RestController
@RequestMapping("/api/v1")
public class InformationController {

    private final FinancialInformationService information;
    private final AffordabilityService affordability;
    private final CreditDecisionSnapshotRepository snapshots;
    private final DecisionSnapshots snapshotIntegrity;
    private final CurrencyRegistry currencies;
    private final ObjectMapper json;

    public InformationController(FinancialInformationService information, AffordabilityService affordability,
                                 CreditDecisionSnapshotRepository snapshots, DecisionSnapshots snapshotIntegrity,
                                 CurrencyRegistry currencies, ObjectMapper json) {
        this.information = information;
        this.affordability = affordability;
        this.snapshots = snapshots;
        this.snapshotIntegrity = snapshotIntegrity;
        this.currencies = currencies;
        this.json = json;
    }

    /** Amounts are decimal strings in major units of {@code currency}. {@code effectiveAt} defaults to now. */
    public record RecordRequest(@NotNull FinancialFact.Kind kind, @NotBlank String type, UUID seriesId,
                                FinancialFact.Status status, String amount, String currency,
                                FinancialFact.Frequency frequency, String outstanding, LocalDate employmentStartDate,
                                LocalDate appliesFrom, @NotNull FinancialFact.Provenance provenance,
                                @NotBlank String source, String evidenceReference, UUID verifiesFactId,
                                Instant effectiveAt) {}

    public record ProposalRequest(@NotBlank String currency, @NotBlank String principal, @NotNull Integer annualRateBps,
                                  @NotNull Integer installmentCount, Instant asOf, Instant knownAt) {}

    @PostMapping("/parties/{partyId}/financial-facts")
    public ResponseEntity<FactView> record(@PathVariable UUID partyId, @Valid @RequestBody RecordRequest r) {
        var currency = r.currency() == null ? null : currencies.require(r.currency());
        FinancialFact f = information.record(partyId, new FinancialInformationService.Observation(r.kind(),
                r.type().strip().toUpperCase(), r.seriesId(), r.status(),
                r.amount() == null ? null : MoneyParser.parse(r.amount(), currency).minorUnits(),
                currency == null ? null : currency.code(), r.frequency(),
                r.outstanding() == null ? null : MoneyParser.parse(r.outstanding(), currency).minorUnits(),
                r.employmentStartDate(), r.appliesFrom(), r.provenance(), r.source(), r.evidenceReference(),
                r.verifiesFactId(), r.effectiveAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(information.provenance(f.getId()).stream()
                .filter(v -> v.id().equals(f.getId())).findFirst().orElseThrow());
    }

    /** What the bank knew at {@code knownAt} (default: asOf) about the party's finances as they stood at {@code asOf}. */
    @GetMapping("/parties/{partyId}/financial-facts")
    public FinancialInformationService.Visible visible(@PathVariable UUID partyId, @RequestParam Instant asOf,
                                                       @RequestParam(required = false) Instant knownAt) {
        return information.visibleAt(partyId, asOf, knownAt);
    }

    @GetMapping("/financial-facts/{factId}/provenance")
    public List<FactView> provenance(@PathVariable UUID factId) {
        return information.provenance(factId);
    }

    @PostMapping("/parties/{partyId}/affordability-assessments")
    public ResponseEntity<Map<String, Object>> assess(@PathVariable UUID partyId, @Valid @RequestBody ProposalRequest r) {
        var currency = currencies.require(r.currency());
        AffordabilityAssessment a = affordability.assess(partyId, new AffordabilityService.Proposal(currency.code(),
                MoneyParser.parsePositive(r.principal(), currency).minorUnits(), r.annualRateBps(),
                r.installmentCount()), r.asOf(), r.knownAt(), "ENQUIRY", null, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(render(a));
    }

    @GetMapping("/affordability-assessments/{id}")
    public Map<String, Object> assessment(@PathVariable UUID id) {
        return render(affordability.require(id));
    }

    @GetMapping("/affordability-assessments/{id}/reproduction")
    public AffordabilityService.Reproduction reproduce(@PathVariable UUID id) {
        return affordability.reproduce(id);
    }

    /** The financial-information section of a decision snapshot, exactly as captured (version 2 only). */
    @GetMapping("/history/decisions/{decisionId}/financial-information")
    public Map<String, Object> decisionInformation(@PathVariable UUID decisionId) {
        var s = snapshots.findByDecisionId(decisionId)
                .orElseThrow(() -> new NotFoundException("snapshot.not_found", "Decision " + decisionId + " has no snapshot"));
        JsonNode content = read(s.getContent());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionId", decisionId);
        m.put("snapshotId", s.getId());
        m.put("snapshotSchema", content.path("schema").asText());
        m.put("snapshotVerified", snapshotIntegrity.verify(s));
        m.put("financialInformation", content.has("financialInformation") ? content.get("financialInformation")
                : "NOT_CAPTURED: version-1 snapshot, taken before financial information was part of a decision");
        return m;
    }

    private Map<String, Object> render(AffordabilityAssessment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("partyId", a.getPartyId());
        m.put("purpose", a.getPurpose());
        m.put("calculationVersion", a.getCalculationVersion());
        m.put("asOf", a.getAsOf());
        m.put("knownAt", a.getKnownAt());
        m.put("completeness", a.getCompleteness());
        m.put("inputHash", a.getInputHash());
        m.put("outputHash", a.getOutputHash());
        m.put("input", read(a.getInput()));
        m.put("output", read(a.getOutput()));
        return m;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
