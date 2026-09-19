package com.wbank.information;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.domain.AnnuitySchedule;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.HistoricalLoanState;
import com.wbank.obligation.history.PointInTimeService;
import com.wbank.platform.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds affordability inputs from what was known at a point in time, runs the pure
 * calculation, and records the result with hashes so it can be reproduced exactly.
 *
 * <p>Inputs come from exactly two sources, both bitemporal: the financial fact store (at
 * asOf/knownAt) and the lending history (the party's own loans with this bank, folded at
 * asOf/knownAt). Current mutable state is never read.
 */
@Service
public class AffordabilityService {

    private final FinancialInformationService information;
    private final PointInTimeService pit;
    private final AffordabilityAssessmentRepository assessments;
    private final ObjectMapper json;
    private final Clock clock;
    private final AffordabilityCalculator calculator = new AffordabilityCalculator();

    public AffordabilityService(FinancialInformationService information, PointInTimeService pit,
                                AffordabilityAssessmentRepository assessments, ObjectMapper json, Clock clock) {
        this.information = information;
        this.pit = pit;
        this.assessments = assessments;
        this.json = json;
        this.clock = clock;
    }

    /** The loan being assessed. */
    public record Proposal(String currency, long principalMinor, int annualRateBps, int installmentCount) {
        public long monthlyPaymentMinor() {
            return AnnuitySchedule.instalment(principalMinor, annualRateBps, installmentCount);
        }
    }

    public record Computed(AffordabilityCalculator.Input input, String inputJson, String inputHash,
                           Map<String, Object> output, String outputJson, String outputHash,
                           List<FactView> visibleObservations) {
        @SuppressWarnings("unchecked")
        public String completeness() {
            return (String) ((Map<String, Object>) output.get("completeness")).get("status");
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> basis(String name) {
            return (Map<String, Object>) ((Map<String, Object>) output.get("bases")).get(name);
        }
    }

    public record Reproduction(UUID assessmentId, String storedInputHash, String recomputedInputHash,
                               String storedOutputHash, String recomputedOutputHash, boolean identical) {}

    /** Computes (without recording) at (asOf, knownAt), excluding {@code excludeObligationId} from internal obligations. */
    @Transactional(readOnly = true)
    public Computed compute(UUID partyId, Proposal proposal, Instant asOf, Instant knownAt, UUID excludeObligationId) {
        FinancialInformationService.Visible visible = information.visibleAt(partyId, asOf, knownAt);
        List<AffordabilityCalculator.InternalObligation> internal = new ArrayList<>();
        for (HistoricalLoanState loan : pit.borrowerAt(partyId, asOf, visible.knownAt())) {
            if (loan.obligationId().equals(excludeObligationId)) {
                continue;
            }
            if ("ACTIVE".equals(loan.status()) || "DEFAULTED".equals(loan.status())) {
                if (!proposal.currency().equals(loan.currency())) {
                    continue; // another currency: not convertible; reported via the loan's own record
                }
                internal.add(new AffordabilityCalculator.InternalObligation(loan.obligationId(), loan.obligationNumber(),
                        loan.status(), AnnuitySchedule.instalment(loan.principalMinor(), loan.annualRateBps(),
                        loan.installmentCount())));
            }
        }
        AffordabilityCalculator.Input input = new AffordabilityCalculator.Input(AffordabilityCalculator.VERSION, asOf,
                visible.knownAt(), proposal.currency(), proposal.monthlyPaymentMinor(), visible.currentPerSeries(),
                List.copyOf(internal));
        return run(input, visible.observations());
    }

    /** Computes and records an assessment. */
    @Transactional
    public AffordabilityAssessment assess(UUID partyId, Proposal proposal, Instant asOf, Instant knownAt, String purpose,
                                          UUID obligationId, UUID excludeObligationId) {
        Instant now = clock.instant();
        Instant a = asOf == null ? now : asOf;
        Instant k = knownAt == null ? (a.isAfter(now) ? now : a) : knownAt;
        if (k.isAfter(now)) {
            throw new IllegalArgumentException("Knowledge cannot be taken from the future: knownAt " + k + " > now");
        }
        Computed c = compute(partyId, proposal, a, k, excludeObligationId);
        return record(partyId, obligationId, purpose, c);
    }

    public AffordabilityAssessment record(UUID partyId, UUID obligationId, String purpose, Computed c) {
        return assessments.save(AffordabilityAssessment.of(partyId, obligationId, purpose, c.input().asOf(),
                c.input().knownAt(), c.inputJson(), c.inputHash(), c.outputJson(), c.outputHash(), c.completeness(),
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public AffordabilityAssessment require(UUID id) {
        return assessments.findById(id).orElseThrow(() -> NotFoundException.of("affordability_assessment", id));
    }

    /** Recomputes an assessment from its stored input alone and compares hashes. */
    @Transactional(readOnly = true)
    public Reproduction reproduce(UUID id) {
        AffordabilityAssessment a = require(id);
        try {
            AffordabilityCalculator.Input input = json.readValue(a.getInput(), AffordabilityCalculator.Input.class);
            Computed c = run(input, List.of());
            return new Reproduction(id, a.getInputHash(), c.inputHash(), a.getOutputHash(), c.outputHash(),
                    a.getInputHash().equals(c.inputHash()) && a.getOutputHash().equals(c.outputHash()));
        } catch (Exception e) {
            throw new IllegalStateException("Stored assessment input is unreadable", e);
        }
    }

    /** Runs {@code calculator} (a subclass in mutation tests) on an input; hashes as {@link #run}. */
    public Computed run(AffordabilityCalculator calc, AffordabilityCalculator.Input input, List<FactView> visible) {
        String inputJson = CanonicalJson.canonical(json, json.valueToTree(input));
        String inputHash = CanonicalJson.sha256(inputJson);
        Map<String, Object> output = calc.compute(input);
        Map<String, Object> hashed = new LinkedHashMap<>(output);
        hashed.put("inputHash", inputHash);
        String outputJson = CanonicalJson.canonical(json, json.valueToTree(hashed));
        return new Computed(input, inputJson, inputHash, output, outputJson, CanonicalJson.sha256(outputJson), visible);
    }

    private Computed run(AffordabilityCalculator.Input input, List<FactView> visible) {
        return run(calculator, input, visible);
    }

    public JsonNode tree(Object o) {
        return json.valueToTree(o);
    }
}
