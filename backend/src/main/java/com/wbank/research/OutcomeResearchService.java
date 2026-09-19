package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditDecisionSnapshot;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.DecisionSnapshots;
import com.wbank.obligation.history.HistoricalLoanState;
import com.wbank.obligation.history.LendingEvent;
import com.wbank.obligation.history.LendingEventRepository;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import com.wbank.obligation.history.PointInTimeService;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.time.DatabaseTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Research cohorts, outcome definitions and the descriptive information-state / outcome report.
 *
 * <p>Four things are kept apart: the information state at decision time (read only from the
 * decision's hash-verified snapshot), the decision, the outcome observed afterwards (from the
 * immutable lending history, bounded by the horizon and the knowledge cutoff), and whether that
 * outcome is observable at all. Nothing here predicts, scores, ranks, recommends or makes a
 * causal claim, and nothing is written to a banking table.
 */
@Service
public class OutcomeResearchService {

    public static final String REPORT_SCHEMA = "outcome-association-report/v1";
    public static final String NOT_ESTABLISHED = "NOT_ESTABLISHED";
    static final List<String> DIMENSIONS = List.of("completeness", "incomeState", "obligationsState");

    public enum KnowledgeBasis { AS_KNOWN_AT_DECISION, AS_KNOWN_AT, RESEARCH_CURRENT }

    public record CohortDefinition(String code, int version, Instant decidedFrom, Instant decidedTo, Instant knownAt,
                                   List<String> includeDecisions, boolean requireInformationCaptured,
                                   String description) {}

    /** A cohort member: the decision, its loan history, and its decision-time information state. */
    public record Member(OutcomeEvaluator.Subject subject, String snapshotSha256, Map<String, Object> informationState) {}

    public record Membership(CohortDefinition cohort, String definitionHash, List<Member> members,
                             List<Map<String, Object>> excluded, String membershipHash) {}

    /** Where a member's decision-time information state comes from. The correct source is the snapshot. */
    public interface InformationSource {
        Map<String, Object> state(UUID decisionId, UUID partyId, Instant decidedAt, JsonNode snapshot);
    }

    /**
     * A research definition's stored hash (written once, when it was created) and the hash of
     * its content as it is now. They differ only if the append-only table was bypassed.
     */
    public record DefinitionHashes(String kind, String code, int version, String storedHash, String contentHash) {
        public boolean intact() {
            return storedHash.equals(contentHash);
        }
    }

    public record Reproduction(UUID reportId, String storedPopulationHash, String recomputedPopulationHash,
                               String storedOutputHash, String recomputedOutputHash, boolean identical) {}

    private final LendingEventRepository events;
    private final PointInTimeService pit;
    private final CreditDecisionSnapshotRepository snapshots;
    private final DecisionSnapshots snapshotIntegrity;
    private final PolicyReplayService replays;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final OutcomeEvaluator evaluator = new OutcomeEvaluator();

    public OutcomeResearchService(LendingEventRepository events, PointInTimeService pit,
                                  CreditDecisionSnapshotRepository snapshots, DecisionSnapshots snapshotIntegrity,
                                  PolicyReplayService replays,
                                  JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.events = events;
        this.pit = pit;
        this.snapshots = snapshots;
        this.snapshotIntegrity = snapshotIntegrity;
        this.replays = replays;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ================================================================ definitions

    @Transactional
    public Map<String, Object> defineOutcome(String code, OutcomeEvaluator.Event event, Integer thresholdDaysPastDue,
                                             int horizonDays, String description) {
        String c = requireCode(code);
        if (horizonDays < 1) {
            throw new IllegalArgumentException("The horizon must be at least one day");
        }
        boolean delinquency = event == OutcomeEvaluator.Event.DELINQUENCY_DERIVED
                || event == OutcomeEvaluator.Event.DELINQUENCY_BANK_OBSERVED;
        if (delinquency != (thresholdDaysPastDue != null) || (delinquency && thresholdDaysPastDue < 1)) {
            throw new IllegalArgumentException("A delinquency outcome needs a threshold of at least 1 day past due; "
                    + "other outcomes take none");
        }
        int version = nextVersion("research_outcome_definition", c);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", c);
        d.put("version", version);
        d.put("event", event.name());
        d.put("thresholdDaysPastDue", thresholdDaysPastDue);
        d.put("horizonDays", horizonDays);
        d.put("eventSemantics", switch (event) {
            case DEFAULT -> "a DEFAULT_DECLARED event (the lending domain's existing default state)";
            case SETTLEMENT -> "a LOAN_SETTLED event";
            case DELINQUENCY_DERIVED -> "days past due, derived from the agreed schedule and repayments, reaching the threshold";
            case DELINQUENCY_BANK_OBSERVED -> "a DELINQUENCY_CHANGED observation the bank recorded at or above the threshold";
        });
        d.put("effectiveTimeSemantics", "decidedAt < event.effectiveAt <= decidedAt + horizonDays");
        d.put("knowledgeSemantics", "event.recordedAt <= knownAt; observable only if decidedAt + horizonDays <= knownAt");
        d.put("evaluatorVersion", OutcomeEvaluator.VERSION);
        d.put("description", description);
        insertDefinition("research_outcome_definition", c, version, d, now());
        return d;
    }

    @Transactional(readOnly = true)
    public OutcomeEvaluator.Definition outcomeDefinition(String code, int version) {
        JsonNode d = readDefinition("research_outcome_definition", code, version);
        return new OutcomeEvaluator.Definition(code, version, OutcomeEvaluator.Event.valueOf(d.get("event").asText()),
                d.hasNonNull("thresholdDaysPastDue") ? d.get("thresholdDaysPastDue").asInt() : null,
                d.get("horizonDays").asInt());
    }

    @Transactional
    public CohortDefinition defineCohort(String code, Instant decidedFrom, Instant decidedTo, Instant knownAt,
                                         List<String> includeDecisions, boolean requireInformationCaptured,
                                         String description) {
        String c = requireCode(code);
        Instant now = now();
        Instant k = knownAt == null ? now : DatabaseTime.normalize(knownAt);
        if (k.isAfter(now)) {
            throw new IllegalArgumentException("A cohort's knowledge cutoff cannot be in the future: membership "
                    + "must never change after the cohort is defined");
        }
        if (!decidedFrom.isBefore(decidedTo)) {
            throw new IllegalArgumentException("decidedFrom must be before decidedTo");
        }
        List<String> include = includeDecisions == null || includeDecisions.isEmpty()
                ? List.of("APPROVED", "DECLINED") : includeDecisions.stream().map(String::toUpperCase).sorted().toList();
        if (!Set.of("APPROVED", "DECLINED").containsAll(include)) {
            throw new IllegalArgumentException("includeDecisions may contain APPROVED and DECLINED only");
        }
        int version = nextVersion("research_cohort_definition", c);
        CohortDefinition def = new CohortDefinition(c, version, decidedFrom, decidedTo, k, include,
                requireInformationCaptured, description);
        insertDefinition("research_cohort_definition", c, version, cohortJson(def), now);
        return def;
    }

    @Transactional(readOnly = true)
    public CohortDefinition cohortDefinition(String code, int version) {
        JsonNode d = readDefinition("research_cohort_definition", code, version);
        List<String> include = new ArrayList<>();
        d.get("includeDecisions").forEach(x -> include.add(x.asText()));
        return new CohortDefinition(code, version, Instant.parse(d.get("decidedFrom").asText()),
                Instant.parse(d.get("decidedTo").asText()), Instant.parse(d.get("knownAt").asText()), include,
                d.get("requireInformationCaptured").asBoolean(), d.path("description").asText(null));
    }

    // ================================================================ membership

    /** The information state as captured in the decision's own snapshot (never recomputed). */
    public static final InformationSource FROM_SNAPSHOT = (decisionId, partyId, decidedAt, snapshot) -> snapshotState(snapshot);

    @Transactional(readOnly = true)
    public Membership membership(String code, int version) {
        return membership(cohortDefinition(code, version), FROM_SNAPSHOT);
    }

    /**
     * Cohort membership from immutable records only: credit-decision events decided within the
     * window and recorded by the cohort's knowledge cutoff. Current loan state plays no part.
     */
    @Transactional(readOnly = true)
    public Membership membership(CohortDefinition c, InformationSource info) {
        List<Member> members = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        List<LendingEvent> decisions = new ArrayList<>(events.findByTypes(List.of(LendingEventType.CREDIT_DECISION)));
        decisions.sort(Comparator.comparing(LendingEvent::getEffectiveAt).thenComparing(e -> e.getId().toString()));
        for (LendingEvent e : decisions) {
            if (e.getEffectiveAt().isBefore(c.decidedFrom()) || !e.getEffectiveAt().isBefore(c.decidedTo())
                    || e.getRecordedAt().isAfter(c.knownAt())) {
                continue;
            }
            JsonNode p = read(e.getPayload());
            UUID decisionId = UUID.fromString(p.get("decisionId").asText());
            String decision = p.path("decision").asText();
            if (!c.includeDecisions().contains(decision)) {
                excluded.add(Map.of("decisionId", decisionId.toString(), "reason", "DECISION_KIND_NOT_INCLUDED"));
                continue;
            }
            CreditDecisionSnapshot snap = snapshots.findByDecisionId(decisionId).orElse(null);
            if (snap != null && !snapshotIntegrity.verify(snap)) {
                excluded.add(Map.of("decisionId", decisionId.toString(), "reason", "SNAPSHOT_FAILED_INTEGRITY_CHECK"));
                continue;
            }
            JsonNode content = snap == null ? null : read(snap.getContent());
            List<LoanHistoryFold.Event> history = pit.history(e.getObligationId());
            UUID party = content == null ? null : UUID.fromString(content.at("/subject/partyId").asText());
            Map<String, Object> state = info.state(decisionId, party, e.getEffectiveAt(), content);
            if (c.requireInformationCaptured() && !Boolean.TRUE.equals(state.get("captured"))) {
                excluded.add(Map.of("decisionId", decisionId.toString(), "reason", "INFORMATION_NOT_CAPTURED"));
                continue;
            }
            members.add(new Member(new OutcomeEvaluator.Subject(decisionId, e.getObligationId(), decision,
                    e.getEffectiveAt(), e.getLoanSeq(), history), snap == null ? null : snap.getContentSha256(), state));
        }
        return membershipOf(c, members, excluded, json);
    }

    /**
     * A membership and its hashes from already-selected members (pure). Used by {@link #membership}
     * and by migration regressions that rebuild a membership from an isolated schema.
     */
    public static Membership membershipOf(CohortDefinition c, List<Member> members, List<Map<String, Object>> excluded,
                                          ObjectMapper json) {
        List<Map<String, Object>> population = members.stream().map(OutcomeResearchService::memberJson).toList();
        return new Membership(c, CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(cohortJson(c)))),
                List.copyOf(members), List.copyOf(excluded),
                CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(population))));
    }

    /** The information state as captured in a decision snapshot; NOT_CAPTURED for version 1. */
    public static Map<String, Object> snapshotState(JsonNode snapshot) {
        Map<String, Object> m = new LinkedHashMap<>();
        JsonNode info = snapshot == null ? null : snapshot.get("financialInformation");
        m.put("captured", info != null);
        m.put("snapshotSchema", snapshot == null ? "NONE" : snapshot.path("schema").asText());
        if (info == null) {
            for (String d : DIMENSIONS) {
                m.put(d, "NOT_CAPTURED");
            }
            return m;
        }
        JsonNode out = info.get("output");
        m.put("completeness", out.at("/completeness/status").asText());
        m.put("incomeState", out.at("/income/state").asText());
        m.put("obligationsState", out.at("/obligations/state").asText());
        m.put("affordabilityOutputHash", info.path("outputHash").asText());
        return m;
    }

    private static Map<String, Object> memberJson(Member m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("decisionId", m.subject().decisionId().toString());
        r.put("obligationId", m.subject().obligationId().toString());
        r.put("decision", m.subject().decision());
        r.put("decidedAt", m.subject().decidedAt().toString());
        r.put("snapshotSha256", m.snapshotSha256());
        r.put("informationState", m.informationState());
        return r;
    }

    // ============================================================ single decision

    @Transactional(readOnly = true)
    public OutcomeEvaluator.Subject subject(UUID decisionId) {
        for (LendingEvent e : events.findByTypes(List.of(LendingEventType.CREDIT_DECISION))) {
            JsonNode p = read(e.getPayload());
            if (decisionId.toString().equals(p.path("decisionId").asText())) {
                return new OutcomeEvaluator.Subject(decisionId, e.getObligationId(), p.path("decision").asText(),
                        e.getEffectiveAt(), e.getLoanSeq(), pit.history(e.getObligationId()));
            }
        }
        throw NotFoundException.of("credit_decision", decisionId);
    }

    @Transactional(readOnly = true)
    public OutcomeEvaluator.Result observe(UUID decisionId, String definitionCode, int definitionVersion, Instant knownAt) {
        return evaluator.evaluate(outcomeDefinition(definitionCode, definitionVersion), subject(decisionId),
                requireKnowable(knownAt, now()));
    }

    /**
     * The loan's outcome timeline after the decision, as known at {@code knownAt}: decision,
     * disbursement, scheduled due dates, repayments, delinquency observations, default,
     * settlement and corrections. Events recorded later are counted, never shown.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> timeline(UUID decisionId, Instant knownAt, Integer horizonDays) {
        Instant k = requireKnowable(knownAt, now());
        OutcomeEvaluator.Subject s = subject(decisionId);
        Instant cutoff = horizonDays == null ? null : s.decidedAt().plus(java.time.Duration.ofDays(horizonDays));
        List<Map<String, Object>> entries = new ArrayList<>();
        int notYetRecorded = 0;
        for (LoanHistoryFold.Event e : s.history()) {
            if (e.loanSeq() < s.decisionSeq()) {
                continue;
            }
            if (e.recordedAt().isAfter(k)) {
                notYetRecorded++;
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loanSeq", e.loanSeq());
            m.put("type", e.type().name());
            m.put("effectiveAt", e.effectiveAt().toString());
            m.put("recordedAt", e.recordedAt().toString());
            m.put("withinHorizon", cutoff == null ? null : !e.effectiveAt().isAfter(cutoff));
            switch (e.type()) {
                case REPAYMENT_RECEIVED -> m.put("amountMinor", e.payload().path("amountMinor").asLong());
                case DELINQUENCY_CHANGED -> {
                    m.put("daysPastDue", e.payload().path("daysPastDue").asLong());
                    m.put("bucket", e.payload().path("toBucket").asText());
                }
                case EVENT_CORRECTED -> {
                    m.put("correctsEventId", String.valueOf(e.correctsEventId()));
                    m.put("field", e.payload().path("field").asText());
                    m.put("correctedValue", e.payload().get("correctedValue"));
                }
                case CREDIT_DECISION, DEFAULT_DECLARED -> m.put("decision", e.payload().path("decision").asText());
                default -> { }
            }
            entries.add(m);
        }
        HistoricalLoanState state = LoanHistoryFold.fold(s.obligationId(), s.history(), cutoff == null ? k : min(cutoff, k), k);
        List<Map<String, Object>> scheduled = new ArrayList<>();
        for (HistoricalLoanState.Installment i : state.schedule()) {
            scheduled.add(Map.of("sequence", i.sequence(), "dueDate", i.dueDate().toString(),
                    "principalDueMinor", i.principalDueMinor(), "interestDueMinor", i.interestDueMinor()));
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("decisionId", decisionId.toString());
        t.put("decision", s.decision());
        t.put("decidedAt", s.decidedAt().toString());
        t.put("knownAt", k.toString());
        t.put("outcomeCutoff", cutoff == null ? null : cutoff.toString());
        t.put("source", "immutable lending event history (never current loan state)");
        t.put("events", entries);
        t.put("scheduledObligations", scheduled);
        t.put("statusAsKnown", state.status());
        t.put("eventsRecordedAfterKnownAt", notYetRecorded);
        if (!"APPROVED".equals(s.decision())) {
            t.put("outcome", "UNKNOWN: declined; no loan was made and no outcome was observed");
        }
        return t;
    }

    // ================================================================== reports

    @Transactional
    public Map<String, Object> report(String cohortCode, int cohortVersion, List<Map.Entry<String, Integer>> definitions,
                                      KnowledgeBasis basis, Instant knownAt) {
        Instant now = now();
        Instant k = switch (basis) {
            case AS_KNOWN_AT_DECISION -> null;
            case AS_KNOWN_AT -> requireKnowable(knownAt, now);
            case RESEARCH_CURRENT -> now;
        };
        if (basis == KnowledgeBasis.AS_KNOWN_AT && knownAt == null) {
            throw new IllegalArgumentException("AS_KNOWN_AT needs knownAt");
        }
        List<OutcomeEvaluator.Definition> defs = definitions.stream()
                .map(d -> outcomeDefinition(d.getKey(), d.getValue())).toList();
        Membership m = membership(cohortCode, cohortVersion);
        Map<String, Object> report = build(m, defs, basis, k, evaluator);
        String canonical = CanonicalJson.canonical(json, json.valueToTree(report));
        String outputHash = CanonicalJson.sha256(canonical);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO research_outcome_report (id, cohort_code, cohort_version, outcome_definitions, knowledge_basis,
                    known_at, evaluated_at, evaluated_by, population_hash, report, output_hash)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?)""", id, cohortCode, cohortVersion,
                write(defs.stream().map(d -> Map.of("code", d.code(), "version", d.version())).toList()), basis.name(),
                k == null ? null : Timestamp.from(k), Timestamp.from(now),
                RequestContext.forOperation("research.outcome_report").actor(), m.membershipHash(), canonical, outputHash);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("outputHash", outputHash);
        out.put("populationHash", m.membershipHash());
        out.put("evaluatedAt", now.toString());
        out.put("report", report);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> storedReport(UUID id) {
        return jdbc.query("SELECT * FROM research_outcome_report WHERE id = ?", rs -> {
            if (!rs.next()) {
                throw NotFoundException.of("outcome_report", id);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id.toString());
            out.put("cohort", rs.getString("cohort_code") + " v" + rs.getInt("cohort_version"));
            out.put("knowledgeBasis", rs.getString("knowledge_basis"));
            Timestamp k = rs.getTimestamp("known_at");
            out.put("knownAt", k == null ? null : k.toInstant().toString());
            out.put("evaluatedAt", rs.getTimestamp("evaluated_at").toInstant().toString());
            out.put("populationHash", rs.getString("population_hash"));
            out.put("outputHash", rs.getString("output_hash"));
            out.put("report", read(rs.getString("report")));
            return out;
        }, id);
    }

    /** Rebuilds a stored report from its definition (cohort, outcome definitions, knowledge cutoff). */
    @Transactional(readOnly = true)
    public Reproduction reproduce(UUID id) {
        return jdbc.query("SELECT * FROM research_outcome_report WHERE id = ?", rs -> {
            if (!rs.next()) {
                throw NotFoundException.of("outcome_report", id);
            }
            List<OutcomeEvaluator.Definition> defs = new ArrayList<>();
            read(rs.getString("outcome_definitions")).forEach(d -> defs.add(
                    outcomeDefinition(d.get("code").asText(), d.get("version").asInt())));
            Timestamp k = rs.getTimestamp("known_at");
            Membership m = membership(rs.getString("cohort_code"), rs.getInt("cohort_version"));
            String hash = CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(build(m, defs,
                    KnowledgeBasis.valueOf(rs.getString("knowledge_basis")), k == null ? null : k.toInstant(), evaluator))));
            return new Reproduction(id, rs.getString("population_hash"), m.membershipHash(), rs.getString("output_hash"),
                    hash, rs.getString("population_hash").equals(m.membershipHash()) && rs.getString("output_hash").equals(hash));
        }, id);
    }

    /**
     * The same cohort and definitions under the three knowledge bases. Only what is known changes;
     * membership (fixed by the cohort) and every decision snapshot stay exactly as they were.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> knowledgeComparison(String cohortCode, int cohortVersion, String definitionCode,
                                                   int definitionVersion, Instant laterKnownAt) {
        Instant now = now();
        Instant later = requireKnowable(laterKnownAt, now);
        Membership m = membership(cohortCode, cohortVersion);
        List<OutcomeEvaluator.Definition> defs = List.of(outcomeDefinition(definitionCode, definitionVersion));
        Map<String, Object> bases = new LinkedHashMap<>();
        Map<String, List<String>> rowsByBasis = new LinkedHashMap<>();
        for (var entry : List.of(Map.entry(KnowledgeBasis.AS_KNOWN_AT_DECISION, Instant.EPOCH),
                Map.entry(KnowledgeBasis.AS_KNOWN_AT, later), Map.entry(KnowledgeBasis.RESEARCH_CURRENT, now))) {
            Instant k = entry.getKey() == KnowledgeBasis.AS_KNOWN_AT_DECISION ? null : entry.getValue();
            Map<String, Object> r = build(m, defs, entry.getKey(), k, evaluator);
            String name = entry.getKey().name();
            bases.put(name, Map.of("knownAt", k == null ? "each decision's own time" : k.toString(),
                    "totals", ((Map<?, ?>) r.get("totals")).get(defs.get(0).key())));
            List<String> rows = new ArrayList<>();
            for (Object o : (List<?>) r.get("rows")) {
                Map<?, ?> row = (Map<?, ?>) o;
                OutcomeEvaluator.Result res = (OutcomeEvaluator.Result) ((Map<?, ?>) row.get("outcomes")).get(defs.get(0).key());
                rows.add(row.get("decisionId") + "=" + res.status() + "/" + res.value());
            }
            rowsByBasis.put(name, rows);
        }
        boolean snapshotsIntact = m.members().stream().allMatch(x -> x.snapshotSha256() == null
                || snapshots.findByDecisionId(x.subject().decisionId()).map(snapshotIntegrity::verify).orElse(false));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cohort", cohortCode + " v" + cohortVersion);
        out.put("definition", defs.get(0).key());
        out.put("populationHash", m.membershipHash());
        out.put("bases", bases);
        out.put("rowsDifferingDecisionTimeVsLater", differing(rowsByBasis.get("AS_KNOWN_AT_DECISION"), rowsByBasis.get("AS_KNOWN_AT")));
        out.put("rowsDifferingLaterVsCurrent", differing(rowsByBasis.get("AS_KNOWN_AT"), rowsByBasis.get("RESEARCH_CURRENT")));
        out.put("decisionSnapshotsUnchanged", snapshotsIntact);
        return out;
    }

    private static int differing(List<String> a, List<String> b) {
        int n = 0;
        for (int i = 0; i < a.size(); i++) {
            n += a.get(i).equals(b.get(i)) ? 0 : 1;
        }
        return n;
    }

    // ============================================================ report builder

    /**
     * The descriptive report. Pure: a function of the membership, the definitions, the
     * knowledge basis and the evaluator (a mutant in tests). No field depends on the time it runs.
     */
    public Map<String, Object> build(Membership m, List<OutcomeEvaluator.Definition> defs, KnowledgeBasis basis,
                                     Instant knownAt, OutcomeEvaluator eval) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Stats> totals = new LinkedHashMap<>();
        Map<String, Map<String, Group>> groups = new LinkedHashMap<>();
        for (String d : DIMENSIONS) {
            groups.put(d, new TreeMap<>());
        }
        defs.forEach(d -> totals.put(d.key(), new Stats()));
        int approved = 0;
        int declined = 0;
        for (Member member : m.members()) {
            OutcomeEvaluator.Subject s = member.subject();
            boolean isApproved = "APPROVED".equals(s.decision());
            approved += isApproved ? 1 : 0;
            declined += isApproved ? 0 : 1;
            Instant k = basis == KnowledgeBasis.AS_KNOWN_AT_DECISION ? s.decidedAt() : knownAt;
            Map<String, OutcomeEvaluator.Result> outcomes = new LinkedHashMap<>();
            for (OutcomeEvaluator.Definition d : defs) {
                OutcomeEvaluator.Result r = eval.evaluate(d, s, k);
                outcomes.put(d.key(), r);
                totals.get(d.key()).add(isApproved, r);
            }
            for (String dim : DIMENSIONS) {
                Group g = groups.get(dim).computeIfAbsent(String.valueOf(member.informationState().get(dim)),
                        x -> new Group(defs));
                g.add(isApproved, outcomes);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("decisionId", s.decisionId().toString());
            row.put("decision", s.decision());
            row.put("decidedAt", s.decidedAt().toString());
            row.put("snapshotSha256", member.snapshotSha256());
            row.put("informationStateAtDecision", member.informationState());
            row.put("outcomes", outcomes);
            rows.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", REPORT_SCHEMA);
        report.put("evaluatorVersion", OutcomeEvaluator.VERSION);
        Map<String, Object> cohort = new LinkedHashMap<>();
        cohort.put("code", m.cohort().code());
        cohort.put("version", m.cohort().version());
        cohort.put("definition", cohortJson(m.cohort()));
        cohort.put("definitionHash", m.definitionHash());
        cohort.put("members", m.members().size());
        cohort.put("excluded", m.excluded());
        report.put("cohort", cohort);
        report.put("populationHash", m.membershipHash());
        report.put("outcomeDefinitions", defs);
        report.put("decisionTimeBasis", "DECISION_SNAPSHOT: information state exactly as captured in each decision's "
                + "hash-verified snapshot; never recomputed, never updated by later information");
        report.put("knowledgeBasis", Map.of("basis", basis.name(),
                "knownAt", knownAt == null ? "each decision's own decision time" : knownAt.toString()));

        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("applications", m.members().size());
        boundary.put("declined", Map.of("count", declined,
                "outcome", "UNKNOWN: no loan was made; what would have happened was never observed and is not inferred"));
        Map<String, Object> byDef = new LinkedHashMap<>();
        totals.forEach((k, st) -> byDef.put(k, Map.of("windowComplete_observed", st.observed,
                "windowComplete_notApplicable", st.notApplicable, "windowIncomplete_censored", st.censored)));
        boundary.put("approved", Map.of("count", approved, "byDefinition", byDef));
        boundary.put("diagram", List.of(
                "all applications in the cohort (" + m.members().size() + ")",
                "  +-- declined (" + declined + "): future outcome never observed; not inferred",
                "  +-- approved (" + approved + ")",
                "        +-- observation window complete: outcome observable (or NOT_APPLICABLE if never disbursed)",
                "        +-- observation window incomplete: CENSORED; not a negative, not a positive"));
        report.put("populationBoundary", boundary);

        Map<String, Object> totalsJson = new LinkedHashMap<>();
        totals.forEach((k, st) -> totalsJson.put(k, st.json()));
        report.put("totals", totalsJson);
        Map<String, Object> byInfo = new LinkedHashMap<>();
        groups.forEach((dim, g) -> {
            Map<String, Object> cats = new LinkedHashMap<>();
            g.forEach((cat, grp) -> cats.put(cat, grp.json()));
            byInfo.put(dim, cats);
        });
        report.put("byInformationState", byInfo);
        report.put("categoryOrder", "alphabetical; not a ranking");
        report.put("statisticDefinitions", Map.of(
                "observedProportion", "occurred / observed, where observed = approved decisions whose outcome window is "
                        + "complete at the knowledge cutoff and whose loan was disbursed (status OBSERVED). Censored, "
                        + "not-applicable and declined decisions are in neither numerator nor denominator. It is the "
                        + "proportion within this observed group, not a population probability and not a prediction.",
                "censored", "approved decisions whose outcome cutoff (decidedAt + horizon) is after the knowledge cutoff",
                "unknown", "declined decisions: no loan, no outcome",
                "notApplicable", "approved decisions with no disbursement within the horizon",
                "value", "exact fraction and a 4-decimal HALF_EVEN rendering; null when the denominator is 0"));
        report.put("warnings", List.of(
                "SELECTION: outcomes exist only for approved applications, chosen by the lending policy in force. "
                        + "They say nothing about what would have happened to declined applicants.",
                "CENSORING: censored loans are excluded from proportions; counting them as either outcome would be wrong.",
                "ASSOCIATION IS NOT CAUSATION: an observed association between information state and outcomes is not "
                        + "evidence that the information state caused the outcome.",
                "COUNTERFACTUALS: outcomes observed under the actual policy cannot be assigned to hypothetical decisions "
                        + "produced by an alternative policy.",
                "SMALL NUMBERS: proportions over few observations are descriptive only and support no general claim."));
        report.put("interpretation", Map.of("nature", "descriptive counts of observed data within stated boundaries",
                "causalConclusion", NOT_ESTABLISHED,
                "notProvided", List.of("prediction", "risk score", "ranking of information states",
                        "best or worst information state", "policy recommendation", "causal interpretation")));
        report.put("rows", rows);
        return report;
    }

    /** Counts for one outcome definition over a set of decisions. */
    static final class Stats {
        int decisions;
        int approved;
        int declined;
        int observed;
        int censored;
        int notApplicable;
        int unknown;
        int occurred;
        int notOccurred;

        void add(boolean isApproved, OutcomeEvaluator.Result r) {
            decisions++;
            approved += isApproved ? 1 : 0;
            declined += isApproved ? 0 : 1;
            switch (r.status()) {
                case "OBSERVED" -> {
                    observed++;
                    occurred += "OCCURRED".equals(r.value()) ? 1 : 0;
                    notOccurred += "NOT_OCCURRED".equals(r.value()) ? 1 : 0;
                }
                case "CENSORED" -> censored++;
                case "NOT_APPLICABLE" -> notApplicable++;
                default -> unknown++;
            }
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decisions", decisions);
            m.put("approved", approved);
            m.put("declined", declined);
            m.put("observed", observed);
            m.put("censored", censored);
            m.put("notApplicable", notApplicable);
            m.put("unknown", unknown);
            m.put("occurred", occurred);
            m.put("notOccurred", notOccurred);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("numerator", occurred);
            p.put("denominator", observed);
            p.put("denominatorIs", "OBSERVED approved decisions (censored, not-applicable and declined excluded)");
            p.put("fraction", occurred + "/" + observed);
            p.put("value", observed == 0 ? null
                    : new BigDecimal(occurred).divide(new BigDecimal(observed), 4, RoundingMode.HALF_EVEN).toPlainString());
            m.put("observedProportion", p);
            return m;
        }
    }

    /** One information-state category. */
    static final class Group {
        int size;
        int approved;
        int declined;
        final Map<String, Stats> outcomes = new LinkedHashMap<>();

        Group(List<OutcomeEvaluator.Definition> defs) {
            defs.forEach(d -> outcomes.put(d.key(), new Stats()));
        }

        void add(boolean isApproved, Map<String, OutcomeEvaluator.Result> results) {
            size++;
            approved += isApproved ? 1 : 0;
            declined += isApproved ? 0 : 1;
            results.forEach((k, r) -> outcomes.get(k).add(isApproved, r));
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cohortSize", size);
            m.put("approved", approved);
            m.put("declined", declined);
            Map<String, Object> o = new LinkedHashMap<>();
            outcomes.forEach((k, st) -> o.put(k, st.json()));
            m.put("outcomes", o);
            return m;
        }
    }

    // ============================================== counterfactual / outcome boundary

    /**
     * For each counterfactual in a replay: the actual decision, the counterfactual decision, and
     * the observed outcome of the ACTUAL decision. The counterfactual outcome is UNOBSERVED; the
     * table's constraints refuse anything else.
     */
    @Transactional
    public List<Map<String, Object>> counterfactualBoundary(UUID replayId, String definitionCode, int definitionVersion,
                                                            Instant knownAt) {
        return counterfactualBoundary(replayId, definitionCode, definitionVersion, knownAt, evaluator);
    }

    @Transactional
    public List<Map<String, Object>> counterfactualBoundary(UUID replayId, String definitionCode, int definitionVersion,
                                                            Instant knownAt, OutcomeEvaluator eval) {
        Instant now = now();
        Instant k = requireKnowable(knownAt, now);
        OutcomeEvaluator.Definition d = outcomeDefinition(definitionCode, definitionVersion);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PolicyReplayService.Row r : replays.get(replayId).rows()) {
            CounterfactualDecision cf = r.counterfactual();
            String actual = r.actual().decision();
            OutcomeEvaluator.Result result = eval.evaluate(d, subject(cf.getSourceDecisionId()), k);
            String cfOutcome = eval.boundary(actual, cf.getHypotheticalDecision(), result);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO research_counterfactual_outcome (id, counterfactual_id, source_decision_id,
                        outcome_definition_code, outcome_definition_version, known_at, actual_decision,
                        counterfactual_decision, actual_outcome_status, actual_outcome_value, counterfactual_outcome,
                        evaluated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, cf.getId(),
                    cf.getSourceDecisionId(), definitionCode, definitionVersion, Timestamp.from(k), actual,
                    cf.getHypotheticalDecision(), result.status(), result.value(), cfOutcome, Timestamp.from(now));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id.toString());
            m.put("sourceDecisionId", cf.getSourceDecisionId().toString());
            m.put("actualDecision", actual);
            m.put("counterfactualDecision", cf.getHypotheticalDecision());
            m.put("actualOutcome", Map.of("status", result.status(), "value", String.valueOf(result.value()),
                    "attributedTo", "ACTUAL_DECISION"));
            m.put("counterfactualOutcome", cfOutcome);
            m.put("causalConclusion", NOT_ESTABLISHED);
            out.add(m);
        }
        return out;
    }

    // ================================================================== helpers

    /** The stored JSON of a cohort definition. */
    public static Map<String, Object> cohortJson(CohortDefinition c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", c.code());
        m.put("version", c.version());
        m.put("population", "credit decisions (CREDIT_DECISION events) of all applications");
        m.put("entryCriteria", "decidedFrom <= decision effective_at < decidedTo, and decision recorded_at <= knownAt");
        m.put("decidedFrom", c.decidedFrom().toString());
        m.put("decidedTo", c.decidedTo().toString());
        m.put("knownAt", c.knownAt().toString());
        m.put("includeDecisions", c.includeDecisions());
        m.put("requireInformationCaptured", c.requireInformationCaptured());
        m.put("exclusionRules", List.of("decision kind not included", "snapshot fails its integrity check",
                "information not captured (only if requireInformationCaptured)"));
        m.put("outcomeWindow", "per outcome definition: decidedAt + horizonDays");
        m.put("description", c.description());
        return m;
    }

    /** Read once per operation, at database precision (see {@link DatabaseTime}). */
    private Instant now() {
        return DatabaseTime.now(clock);
    }

    private Instant requireKnowable(Instant knownAt, Instant now) {
        Instant k = knownAt == null ? now : DatabaseTime.normalize(knownAt);
        if (k.isAfter(now)) {
            throw new IllegalArgumentException("Knowledge cannot be taken from the future: knownAt " + k + " > now");
        }
        return k;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String requireCode(String code) {
        if (code == null || !code.strip().matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("A code is upper-case letters, digits and underscores");
        }
        return code.strip();
    }

    private int nextVersion(String table, String code) {
        Integer max = jdbc.queryForObject("SELECT max(version) FROM " + table + " WHERE code = ?", Integer.class, code);
        return max == null ? 1 : max + 1;
    }

    private void insertDefinition(String table, String code, int version, Map<String, Object> d, Instant now) {
        String canonical = CanonicalJson.canonical(json, json.valueToTree(d));
        jdbc.update("INSERT INTO " + table + " (code, version, definition, definition_hash, created_at, created_by) "
                        + "VALUES (?, ?, ?::jsonb, ?, ?, ?)", code, version, canonical, CanonicalJson.sha256(canonical),
                Timestamp.from(now), RequestContext.forOperation("research.define").actor());
    }

    @Transactional(readOnly = true)
    public DefinitionHashes outcomeDefinitionHashes(String code, int version) {
        return definitionHashes("research_outcome_definition", "OUTCOME", code, version);
    }

    @Transactional(readOnly = true)
    public DefinitionHashes cohortDefinitionHashes(String code, int version) {
        return definitionHashes("research_cohort_definition", "COHORT", code, version);
    }

    private DefinitionHashes definitionHashes(String table, String kind, String code, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT definition::text AS d, definition_hash FROM " + table
                + " WHERE code = ? AND version = ?", code, version);
        if (rows.isEmpty()) {
            throw new NotFoundException("research_definition.not_found", table + " " + code + " v" + version + " does not exist");
        }
        String content = CanonicalJson.sha256(CanonicalJson.canonical(json, read((String) rows.get(0).get("d"))));
        return new DefinitionHashes(kind, code, version, (String) rows.get(0).get("definition_hash"), content);
    }

    private JsonNode readDefinition(String table, String code, int version) {
        List<String> rows = jdbc.queryForList("SELECT definition::text FROM " + table + " WHERE code = ? AND version = ?",
                String.class, code, version);
        if (rows.isEmpty()) {
            throw new NotFoundException("research_definition.not_found", table + " " + code + " v" + version + " does not exist");
        }
        return read(rows.get(0));
    }

    private String write(Object o) {
        return CanonicalJson.canonical(json, json.valueToTree(o));
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
