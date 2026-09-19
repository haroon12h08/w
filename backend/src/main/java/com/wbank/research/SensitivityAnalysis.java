package com.wbank.research;

import com.wbank.research.OutcomeResearchService.KnowledgeBasis;
import com.wbank.research.OutcomeResearchService.Member;
import com.wbank.research.OutcomeResearchService.Membership;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How descriptive outcome results depend on the research definition: horizon, knowledge
 * cutoff, population and grouping. Pure: every result is a function of resolved
 * configurations (immutable memberships and definitions) and the evaluator; nothing here
 * reads a clock, a table or current loan state.
 *
 * <p>Each decision is evaluated by {@link OutcomeEvaluator#evaluate} (the Phase 8 semantics,
 * unchanged); this class only varies the inputs and accounts for what changed. Nothing here
 * predicts, ranks, tests hypotheses or estimates causal effects.
 */
public final class SensitivityAnalysis {

    public static final String VERSION = "SENSITIVITY_V1";

    /** One research configuration with every parameter resolved to an explicit value. */
    public record Resolved(Membership membership, OutcomeEvaluator.Definition definition, KnowledgeBasis basis,
                           Instant knownAt, String dimension) {

        Instant knownAtFor(Member m) {
            return basis == KnowledgeBasis.AS_KNOWN_AT_DECISION ? m.subject().decidedAt() : knownAt;
        }

        String knowledgeLabel() {
            return basis == KnowledgeBasis.AS_KNOWN_AT_DECISION ? "each decision's own time" : knownAt.toString();
        }
    }

    /** One decision evaluated under one configuration. */
    public record Row(Member member, OutcomeEvaluator.Result result, String knownEvent) {
        String id() {
            return member.subject().decisionId().toString();
        }

        boolean approved() {
            return "APPROVED".equals(member.subject().decision());
        }

        String label() {
            return result.status() + (result.value() == null ? "" : ":" + result.value());
        }
    }

    public record Cutoff(String label, Instant knownAt) {}

    private final OutcomeEvaluator evaluator;

    public SensitivityAnalysis(OutcomeEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    // =================================================================== evaluation

    /** Every member of the population, evaluated. Censored and declined decisions stay in it. */
    public List<Row> evaluate(Resolved r) {
        List<Row> rows = new ArrayList<>();
        for (Member m : r.membership().members()) {
            Instant k = r.knownAtFor(m);
            rows.add(new Row(m, evaluator.evaluate(r.definition(), m.subject(), k),
                    evaluator.knownEvent(r.definition(), m.subject(), k)));
        }
        return rows;
    }

    static OutcomeEvaluator.Definition withHorizon(OutcomeEvaluator.Definition d, int horizonDays) {
        return new OutcomeEvaluator.Definition(d.code(), d.version(), d.event(), d.thresholdDaysPastDue(), horizonDays);
    }

    // ============================================================ population views

    /** Counts, the observed proportion (Phase 8 definition) and the censoring share. */
    public Map<String, Object> totals(List<Row> rows) {
        OutcomeResearchService.Stats st = new OutcomeResearchService.Stats();
        int known = 0;
        int knownWhileCensored = 0;
        for (Row row : rows) {
            st.add(row.approved(), row.result());
            known += row.knownEvent() != null ? 1 : 0;
            knownWhileCensored += row.knownEvent() != null && "CENSORED".equals(row.result().status()) ? 1 : 0;
        }
        Map<String, Object> m = new LinkedHashMap<>(st.json());
        m.put("censoredShare", fraction(st.censored, st.approved,
                "approved decisions: every approved decision is potentially observable once its window closes "
                        + "(NOT_APPLICABLE can only be known after that)"));
        m.put("eventsKnownWithinWindow", known);
        m.put("eventsKnownWhileCensored", knownWhileCensored);
        m.put("eventsKnownNote", "a qualifying event already recorded inside an open window; shown so no known event "
                + "is hidden, but NOT counted in any proportion (that would bias it toward early events)");
        return m;
    }

    /**
     * The approval-selection boundary. The observed statistic is defined only over
     * approved + observed; everything else is shown so it cannot be overlooked.
     */
    public Map<String, Object> selection(List<Row> rows) {
        int all = rows.size();
        int approved = 0;
        int observed = 0;
        int censored = 0;
        int notApplicable = 0;
        int declined = 0;
        int declinedNotUnknown = 0;
        for (Row row : rows) {
            if (!row.approved()) {
                declined++;
                declinedNotUnknown += "UNKNOWN".equals(row.result().status()) ? 0 : 1;
                continue;
            }
            approved++;
            switch (row.result().status()) {
                case "OBSERVED" -> observed++;
                case "CENSORED" -> censored++;
                case "NOT_APPLICABLE" -> notApplicable++;
                default -> { }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allDecisions", all);
        m.put("approved", approved);
        m.put("approvedObservable", observed);
        m.put("approvedCensored", censored);
        m.put("approvedNotApplicable", notApplicable);
        m.put("declined", declined);
        m.put("declinedOutcome", "UNKNOWN: never observed, never estimated, never extrapolated from approved borrowers");
        m.put("observedStatisticDenominator", "approvedObservable");
        m.put("accountingHolds", all == approved + declined && approved == observed + censored + notApplicable
                && declinedNotUnknown == 0);
        return m;
    }

    /** Category x decision x observation status / value. Alphabetical; a description, not a ranking. */
    public Map<String, Object> crossTab(List<Row> rows, String dimension) {
        Map<String, Map<String, Map<String, Integer>>> t = new TreeMap<>();
        for (Row row : rows) {
            String category = String.valueOf(row.member().informationState().get(dimension));
            t.computeIfAbsent(category, x -> new TreeMap<>())
                    .computeIfAbsent(row.member().subject().decision(), x -> new TreeMap<>())
                    .merge(row.label(), 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dimension", dimension);
        m.put("order", "alphabetical; not a ranking");
        m.put("cells", t);
        return m;
    }

    public Map<String, Object> population(Resolved r) {
        List<Row> rows = evaluate(r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totals", totals(rows));
        m.put("selection", selection(rows));
        Map<String, Object> tabs = new LinkedHashMap<>();
        for (String d : OutcomeResearchService.DIMENSIONS) {
            tabs.put(d, crossTab(rows, d));
        }
        m.put("crossTabulations", tabs);
        m.put("rows", rowsJson(rows, r.dimension()));
        return m;
    }

    // ============================================================= horizon sensitivity

    /**
     * The same population and knowledge cutoff under increasing horizons. With a FIXED cutoff,
     * lengthening the horizon asks a longer question: an outcome can go from CENSORED to
     * CENSORED, from OBSERVED:NOT_OCCURRED to OBSERVED:OCCURRED (a later event now counts) or to
     * CENSORED (the longer window is not yet complete), but never from CENSORED to OBSERVED, and a
     * known event never disappears. Observability grows with the knowledge cutoff, not the horizon.
     */
    public Map<String, Object> horizons(Resolved base, List<Integer> horizonsDays) {
        requireIncreasing(horizonsDays.stream().map(Integer::longValue).toList(), "horizons");
        List<Map<String, Object>> steps = new ArrayList<>();
        Invariants inv = new Invariants();
        List<Row> prev = null;
        for (int h : horizonsDays) {
            Resolved r = new Resolved(base.membership(), withHorizon(base.definition(), h), base.basis(), base.knownAt(),
                    base.dimension());
            List<Row> rows = evaluate(r);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("horizonDays", h);
            step.put("totals", totals(rows));
            step.put("selection", selection(rows));
            if (prev != null) {
                Map<String, List<String>> tr = transitions(prev, rows);
                step.put("transitionsFromPreviousHorizon", tr);
                step.put("newlyObservable", tr.getOrDefault("CENSORED->OBSERVED:OCCURRED", List.of()).size()
                        + tr.getOrDefault("CENSORED->OBSERVED:NOT_OCCURRED", List.of()).size());
                step.put("remainingCensored", count(rows, "CENSORED"));
                step.put("becameCensored", tr.entrySet().stream().filter(e -> e.getKey().endsWith("->CENSORED"))
                        .mapToInt(e -> e.getValue().size()).sum());
                inv.check(prev, rows, true);
            }
            inv.declined(rows);
            steps.add(step);
            prev = rows;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("varied", "horizonDays");
        m.put("fixed", Map.of("knowledge", base.knowledgeLabel(), "definition", base.definition().key(),
                "event", base.definition().event().name()));
        m.put("steps", steps);
        m.put("invariants", inv.json(true));
        m.put("reading", "A changing proportion across horizons reflects which outcomes the question includes and "
                + "which windows are complete; it is not evidence of changing borrower risk.");
        return m;
    }

    // ======================================================= knowledge-cutoff sensitivity

    /** The same population, definition and horizon under increasing, explicit knowledge cutoffs. */
    public Map<String, Object> knowledge(Resolved base, List<Cutoff> cutoffs, boolean includeDecisionTime) {
        requireIncreasing(cutoffs.stream().map(c -> micros(Instant.EPOCH, c.knownAt())).toList(), "knowledge cutoffs");
        List<Map<String, Object>> steps = new ArrayList<>();
        Invariants inv = new Invariants();
        List<Row> prev = null;
        List<Resolved> configs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (includeDecisionTime) {
            configs.add(new Resolved(base.membership(), base.definition(), KnowledgeBasis.AS_KNOWN_AT_DECISION, null,
                    base.dimension()));
            labels.add("DECISION_TIME");
        }
        for (Cutoff c : cutoffs) {
            configs.add(new Resolved(base.membership(), base.definition(), KnowledgeBasis.AS_KNOWN_AT, c.knownAt(),
                    base.dimension()));
            labels.add(c.label());
        }
        for (int i = 0; i < configs.size(); i++) {
            List<Row> rows = evaluate(configs.get(i));
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("label", labels.get(i));
            step.put("knownAt", configs.get(i).knowledgeLabel());
            step.put("totals", totals(rows));
            if (prev != null) {
                step.put("changesFromPreviousCutoff", transitions(prev, rows));
                inv.check(prev, rows, false);
            }
            inv.declined(rows);
            steps.add(step);
            prev = rows;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("varied", "knowledge cutoff");
        m.put("fixed", Map.of("definition", base.definition().key(), "horizonDays", base.definition().horizonDays()));
        m.put("steps", steps);
        m.put("invariants", inv.json(false));
        m.put("reading", "A later cutoff only reveals events recorded later; the events themselves, the decisions and "
                + "their snapshots are the same under every cutoff.");
        return m;
    }

    // ============================================================= configuration comparison

    /**
     * Configuration A to configuration B, one parameter at a time, in a fixed order:
     * population, knowledge cutoff, horizon, outcome definition, information grouping. Every
     * decision whose result changes is attributed to the first step at which it changes; the
     * last step reproduces B exactly (checked). The order is a convention: a different order
     * can attribute an interacting change to a different parameter, but never loses one.
     */
    public Map<String, Object> compare(Resolved a, Resolved b) {
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Row> rowsA = evaluate(a);
        List<Row> rowsB = evaluate(b);
        List<Row> prev = rowsA;

        // 1. population membership
        Resolved s1 = new Resolved(b.membership(), a.definition(), a.basis(), a.knownAt(), a.dimension());
        List<Row> r1 = evaluate(s1);
        Map<String, Object> membership = new LinkedHashMap<>();
        Set<String> inA = byId(prev).keySet();
        Set<String> inB = byId(r1).keySet();
        membership.put("removed", membershipChanges(inA, inB, b.membership()));
        membership.put("added", membershipChanges(inB, inA, a.membership()));
        steps.add(step("POPULATION", a.membership().cohort().code() + " v" + a.membership().cohort().version(),
                b.membership().cohort().code() + " v" + b.membership().cohort().version(), prev, r1, membership));
        prev = r1;

        // 2. knowledge cutoff
        Resolved s2 = new Resolved(b.membership(), a.definition(), b.basis(), b.knownAt(), a.dimension());
        List<Row> r2 = evaluate(s2);
        steps.add(step("KNOWLEDGE_CUTOFF", a.knowledgeLabel(), b.knowledgeLabel(), prev, r2, null));
        prev = r2;

        // 3. horizon (of A's definition)
        Resolved s3 = new Resolved(b.membership(), withHorizon(a.definition(), b.definition().horizonDays()), b.basis(),
                b.knownAt(), a.dimension());
        List<Row> r3 = evaluate(s3);
        steps.add(step("HORIZON", a.definition().horizonDays() + " days", b.definition().horizonDays() + " days", prev, r3,
                null));
        prev = r3;

        // 4. outcome definition (event, threshold)
        List<Row> r4 = evaluate(b);
        steps.add(step("OUTCOME_DEFINITION", a.definition().key() + " " + a.definition().event(),
                b.definition().key() + " " + b.definition().event(), prev, r4, null));

        // 5. information grouping: moves decisions between groups; no outcome can change
        Map<String, Object> grouping = new LinkedHashMap<>();
        grouping.put("parameter", "INFORMATION_GROUPING");
        grouping.put("from", a.dimension());
        grouping.put("to", b.dimension());
        List<Map<String, Object>> regrouped = new ArrayList<>();
        for (Row row : r4) {
            Object from = row.member().informationState().get(a.dimension());
            Object to = row.member().informationState().get(b.dimension());
            if (!a.dimension().equals(b.dimension())) {
                regrouped.add(Map.of("decisionId", row.id(), "from", String.valueOf(from), "to", String.valueOf(to)));
            }
        }
        grouping.put("regrouped", regrouped);
        grouping.put("outcomeChanges", 0);
        steps.add(grouping);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", describe(a));
        m.put("b", describe(b));
        m.put("totalsA", totals(rowsA));
        m.put("totalsB", totals(rowsB));
        m.put("selectionA", selection(rowsA));
        m.put("selectionB", selection(rowsB));
        m.put("decomposition", steps);
        m.put("unattributedDifferences", unattributed(rowsA, rowsB, steps));
        m.put("attributionOrder", List.of("POPULATION", "KNOWLEDGE_CUTOFF", "HORIZON", "OUTCOME_DEFINITION",
                "INFORMATION_GROUPING"));
        m.put("reading", "Differences are explained as changes in the dataset (who is included, what is known, what "
                + "the question asks), not in borrower behaviour. A difference between two descriptive proportions is not "
                + "a statistical finding.");
        return m;
    }

    /**
     * Decisions whose A and B results differ (or that are in only one population) but that no
     * step accounts for. Empty when the decomposition is complete.
     */
    @SuppressWarnings("unchecked")
    private static List<String> unattributed(List<Row> rowsA, List<Row> rowsB, List<Map<String, Object>> steps) {
        Set<String> attributed = new LinkedHashSet<>();
        for (Map<String, Object> st : steps) {
            Object changes = st.get("outcomeChanges");
            if (changes instanceof Map<?, ?> c) {
                c.values().forEach(ids -> attributed.addAll((List<String>) ids));
            }
            if (st.get("membership") instanceof Map<?, ?> mem) {
                for (Object side : mem.values()) {
                    ((List<Map<String, Object>>) side).forEach(x -> attributed.add((String) x.get("decisionId")));
                }
            }
        }
        Map<String, Row> a = byId(rowsA);
        Map<String, Row> b = byId(rowsB);
        Set<String> all = new LinkedHashSet<>(a.keySet());
        all.addAll(b.keySet());
        List<String> out = new ArrayList<>();
        for (String id : all) {
            boolean differs = !a.containsKey(id) || !b.containsKey(id) || !a.get(id).label().equals(b.get(id).label());
            if (differs && !attributed.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private Map<String, Object> step(String parameter, String from, String to, List<Row> before, List<Row> after,
                                     Map<String, Object> membership) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("parameter", parameter);
        s.put("from", from);
        s.put("to", to);
        s.put("changed", !from.equals(to));
        if (membership != null) {
            s.put("membership", membership);
        }
        s.put("outcomeChanges", transitions(before, after));
        s.put("totalsAfter", totals(after));
        return s;
    }

    private static List<Map<String, Object>> membershipChanges(Set<String> from, Set<String> to, Membership other) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : from) {
            if (to.contains(id)) {
                continue;
            }
            String reason = other.excluded().stream().filter(x -> id.equals(x.get("decisionId")))
                    .map(x -> "EXCLUSION_RULE: " + x.get("reason")).findFirst()
                    .orElse("POPULATION_DEFINITION: decision window, included decision kinds or cohort knowledge cutoff");
            out.add(Map.of("decisionId", id, "reason", reason));
        }
        return out;
    }

    // ================================================================ boundary analysis

    /**
     * Events within {@code window} of the three boundaries that decide observability: the
     * decision time and the outcome cutoff (compared with effective time) and the knowledge
     * cutoff (compared with recorded time), plus decisions whose outcome cutoff lies within
     * {@code window} of the knowledge cutoff. Events recorded after the knowledge cutoff are
     * listed (so the reader can see what a slightly later cutoff would add) and marked as not
     * part of the result.
     */
    public Map<String, Object> boundaries(Resolved r, Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("The boundary window must be positive");
        }
        long w = micros(window);
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> flips = new ArrayList<>();
        for (Member m : r.membership().members()) {
            OutcomeEvaluator.Subject s = m.subject();
            Instant k = r.knownAtFor(m);
            Instant cutoff = evaluator.outcomeCutoff(s.decidedAt(), r.definition(), k); // the evaluator's own rule
            if ("APPROVED".equals(s.decision()) && Math.abs(micros(s.decidedAt(), cutoff) - micros(s.decidedAt(), k)) <= w) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("decisionId", s.decisionId().toString());
                f.put("outcomeCutoff", cutoff.toString());
                f.put("knownAt", k.toString());
                f.put("outcomeCutoffMinusKnownAtMicros", micros(k, cutoff));
                f.put("meaning", "moving the knowledge cutoff across the outcome cutoff switches CENSORED/OBSERVED");
                flips.add(f);
            }
            for (var e : s.history()) {
                if (e.loanSeq() <= s.decisionSeq()) {
                    continue;
                }
                addNear(events, s, e, "DECISION_TIME", s.decidedAt(), e.effectiveAt(), w, k, cutoff);
                addNear(events, s, e, "OUTCOME_CUTOFF", cutoff, e.effectiveAt(), w, k, cutoff);
                if (r.basis() == KnowledgeBasis.AS_KNOWN_AT) {
                    addNear(events, s, e, "KNOWLEDGE_CUTOFF", k, e.recordedAt(), w, k, cutoff);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windowMicros", w);
        m.put("window", window.toString());
        m.put("comparison", Map.of("DECISION_TIME", "event effective_at", "OUTCOME_CUTOFF", "event effective_at",
                "KNOWLEDGE_CUTOFF", "event recorded_at"));
        m.put("precision", "microseconds (PostgreSQL timestamptz); inclusive boundaries: effective_at <= outcome cutoff, "
                + "recorded_at <= knowledge cutoff");
        m.put("events", events);
        m.put("decisionsNearObservabilityFlip", flips);
        m.put("purpose", "exposes where small timestamp differences change observability; not a predictive feature");
        return m;
    }

    private static void addNear(List<Map<String, Object>> out, OutcomeEvaluator.Subject s,
                                com.wbank.obligation.history.LoanHistoryFold.Event e, String boundary, Instant at,
                                Instant compared, long w, Instant knownAt, Instant cutoff) {
        long offset = micros(at, compared);
        if (Math.abs(offset) > w) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionId", s.decisionId().toString());
        m.put("boundary", boundary);
        m.put("boundaryAt", at.toString());
        m.put("eventId", e.id().toString());
        m.put("eventType", e.type().name());
        m.put("effectiveAt", e.effectiveAt().toString());
        m.put("recordedAt", e.recordedAt().toString());
        m.put("offsetMicros", offset);
        m.put("side", offset < 0 ? "BEFORE" : offset == 0 ? "AT" : "AFTER");
        m.put("knownAtCutoff", !e.recordedAt().isAfter(knownAt));
        m.put("withinOutcomeWindow", e.effectiveAt().isAfter(s.decidedAt()) && !e.effectiveAt().isAfter(cutoff));
        out.add(m);
    }

    // ==================================================================== helpers

    /** Decisions whose status/value differs between two evaluations of the same population. */
    static Map<String, List<String>> transitions(List<Row> before, List<Row> after) {
        Map<String, Row> b = byId(before);
        Map<String, List<String>> out = new TreeMap<>();
        for (Row row : after) {
            Row old = b.get(row.id());
            if (old != null && !old.label().equals(row.label())) {
                out.computeIfAbsent(old.label() + "->" + row.label(), x -> new ArrayList<>()).add(row.id());
            }
        }
        return out;
    }

    private static Map<String, Row> byId(List<Row> rows) {
        Map<String, Row> m = new LinkedHashMap<>();
        rows.forEach(r -> m.put(r.id(), r));
        return m;
    }

    private static int count(List<Row> rows, String status) {
        return (int) rows.stream().filter(r -> status.equals(r.result().status())).count();
    }

    private static List<Map<String, Object>> rowsJson(List<Row> rows, String dimension) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decisionId", r.id());
            m.put("decision", r.member().subject().decision());
            m.put("group", String.valueOf(r.member().informationState().get(dimension)));
            m.put("status", r.result().status());
            m.put("value", r.result().value());
            m.put("outcomeCutoff", r.result().outcomeCutoff().toString());
            m.put("knownAt", r.result().knownAt().toString());
            m.put("eventKnownWithinWindow", r.knownEvent() != null);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> describe(Resolved r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cohort", r.membership().cohort().code() + " v" + r.membership().cohort().version());
        m.put("membershipHash", r.membership().membershipHash());
        m.put("definition", r.definition().key());
        m.put("event", r.definition().event().name());
        m.put("horizonDays", r.definition().horizonDays());
        m.put("knowledge", r.knowledgeLabel());
        m.put("informationGrouping", r.dimension());
        return m;
    }

    static Map<String, Object> fraction(int numerator, int denominator, String denominatorIs) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("numerator", numerator);
        p.put("denominator", denominator);
        p.put("denominatorIs", denominatorIs);
        p.put("fraction", numerator + "/" + denominator);
        p.put("value", denominator == 0 ? null
                : new BigDecimal(numerator).divide(new BigDecimal(denominator), 4, RoundingMode.HALF_EVEN).toPlainString());
        return p;
    }

    private static long micros(Duration d) {
        return d.toNanos() / 1000;
    }

    private static long micros(Instant from, Instant to) {
        return ChronoUnit.MICROS.between(from, to);
    }

    private static void requireIncreasing(List<Long> values, String what) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("At least one value is needed for " + what);
        }
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) <= values.get(i - 1)) {
                throw new IllegalArgumentException("The " + what + " must be strictly increasing");
            }
        }
    }

    /** The research invariants, checked on every pair of consecutive evaluations. */
    static final class Invariants {
        final Set<String> knownEventDisappeared = new LinkedHashSet<>();
        final Set<String> occurredBecameNotOccurred = new LinkedHashSet<>();
        final Set<String> censoredBecameObserved = new LinkedHashSet<>();
        final Set<String> observedBecameCensored = new LinkedHashSet<>();
        final Set<String> declinedNotUnknown = new LinkedHashSet<>();

        void check(List<Row> before, List<Row> after, boolean horizonGrew) {
            Map<String, Row> b = byId(before);
            for (Row row : after) {
                Row old = b.get(row.id());
                if (old == null) {
                    continue;
                }
                if (old.knownEvent() != null && row.knownEvent() == null) {
                    knownEventDisappeared.add(row.id());
                }
                if ("OBSERVED:OCCURRED".equals(old.label()) && "OBSERVED:NOT_OCCURRED".equals(row.label())) {
                    occurredBecameNotOccurred.add(row.id());
                }
                if (horizonGrew && "CENSORED".equals(old.result().status()) && "OBSERVED".equals(row.result().status())) {
                    censoredBecameObserved.add(row.id());
                }
                if (!horizonGrew && "OBSERVED".equals(old.result().status()) && "CENSORED".equals(row.result().status())) {
                    observedBecameCensored.add(row.id());
                }
            }
        }

        void declined(List<Row> rows) {
            rows.stream().filter(r -> !r.approved() && !"UNKNOWN".equals(r.result().status()))
                    .forEach(r -> declinedNotUnknown.add(r.id()));
        }

        Map<String, Object> json(boolean horizon) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knownEventNeverDisappears", verdict(knownEventDisappeared));
            m.put("occurredNeverBecomesNotOccurred", verdict(occurredBecameNotOccurred));
            if (horizon) {
                m.put("fixedCutoffLongerHorizonNeverCompletesAWindow", verdict(censoredBecameObserved));
            } else {
                m.put("laterCutoffNeverReopensAWindow", verdict(observedBecameCensored));
            }
            m.put("declinedAlwaysUnknown", verdict(declinedNotUnknown));
            return m;
        }

        private static Map<String, Object> verdict(Set<String> violations) {
            return Map.of("holds", violations.isEmpty(), "violations", List.copyOf(violations));
        }
    }
}
