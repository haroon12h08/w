package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.time.DatabaseTime;
import com.wbank.research.OutcomeResearchService.KnowledgeBasis;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves research configurations to explicit, immutable inputs, runs
 * {@link SensitivityAnalysis}, and records each result as a reproducible artefact.
 *
 * <p>Every knowledge cutoff is an explicit timestamp supplied by the researcher (never the
 * current clock), normalized to database precision before it is hashed, stored or used. The
 * clock is read only to refuse cutoffs in the future and to stamp {@code evaluated_at}, which
 * is not part of any hash. Reads the banking record through Phase 8's membership and
 * definitions; writes only {@code research_sensitivity_report}.
 */
@Service
public class SensitivityService {

    public enum Kind { HORIZON, KNOWLEDGE, COMPARISON, BOUNDARY, POPULATION }

    /**
     * One research configuration.
     *
     * @param horizonDays          null = the outcome definition's own horizon
     * @param knowledgeBasis       AS_KNOWN_AT (with knownAt) or AS_KNOWN_AT_DECISION (without)
     * @param informationDimension completeness | incomeState | obligationsState (default completeness)
     */
    public record Configuration(String cohortCode, Integer cohortVersion, String definitionCode,
                                Integer definitionVersion, Integer horizonDays, KnowledgeBasis knowledgeBasis,
                                Instant knownAt, String informationDimension) {}

    public record HorizonRequest(Configuration configuration, List<Integer> horizonsDays) {}

    public record KnowledgeRequest(Configuration configuration, List<SensitivityAnalysis.Cutoff> cutoffs,
                                   boolean includeDecisionTime) {}

    public record ComparisonRequest(Configuration a, Configuration b) {}

    public record BoundaryRequest(Configuration configuration, Duration window) {}

    public record Reproduction(UUID reportId, String kind, String storedConfigurationHash,
                               String recomputedConfigurationHash, String storedInputHash, String recomputedInputHash,
                               String storedOutputHash, String recomputedOutputHash, boolean identical) {}

    record Computed(Map<String, Object> report, Map<String, Object> inputs) {}

    private final OutcomeResearchService research;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public SensitivityService(OutcomeResearchService research, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.research = research;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public static String calculationVersion() {
        return SensitivityAnalysis.VERSION + "/" + OutcomeEvaluator.VERSION;
    }

    // ================================================================== operations

    @Transactional
    public Map<String, Object> horizons(HorizonRequest r) {
        Instant now = DatabaseTime.now(clock);
        if (r.configuration() == null || r.horizonsDays() == null) {
            throw new IllegalArgumentException("configuration and horizonsDays are required");
        }
        if (r.configuration().horizonDays() != null) {
            throw new IllegalArgumentException("The horizon is the varied parameter; leave configuration.horizonDays empty");
        }
        if (r.horizonsDays().stream().anyMatch(h -> h == null || h < 1)) {
            throw new IllegalArgumentException("Every horizon must be at least one day");
        }
        HorizonRequest n = new HorizonRequest(normalize(r.configuration(), now), List.copyOf(r.horizonsDays()));
        return record(Kind.HORIZON, n, compute(Kind.HORIZON, n, new OutcomeEvaluator()), now);
    }

    @Transactional
    public Map<String, Object> knowledge(KnowledgeRequest r) {
        Instant now = DatabaseTime.now(clock);
        if (r.configuration() == null || r.cutoffs() == null || r.cutoffs().isEmpty()) {
            throw new IllegalArgumentException("configuration and at least one explicit cutoff are required");
        }
        Configuration c = r.configuration();
        if (c.knowledgeBasis() != null || c.knownAt() != null) {
            throw new IllegalArgumentException("The knowledge cutoff is the varied parameter; give it only in cutoffs");
        }
        List<SensitivityAnalysis.Cutoff> cutoffs = new ArrayList<>();
        for (SensitivityAnalysis.Cutoff k : r.cutoffs()) {
            if (k.knownAt() == null || k.label() == null || k.label().isBlank()) {
                throw new IllegalArgumentException("Every cutoff needs a label and an explicit knownAt");
            }
            cutoffs.add(new SensitivityAnalysis.Cutoff(k.label().strip(), knowable(k.knownAt(), now)));
        }
        Configuration base = new Configuration(c.cohortCode(), c.cohortVersion(), c.definitionCode(),
                c.definitionVersion(), c.horizonDays(), null, null, dimension(c.informationDimension()));
        requireReferences(base);
        KnowledgeRequest n = new KnowledgeRequest(base, List.copyOf(cutoffs), r.includeDecisionTime());
        return record(Kind.KNOWLEDGE, n, compute(Kind.KNOWLEDGE, n, new OutcomeEvaluator()), now);
    }

    @Transactional
    public Map<String, Object> compare(ComparisonRequest r) {
        Instant now = DatabaseTime.now(clock);
        if (r.a() == null || r.b() == null) {
            throw new IllegalArgumentException("Two configurations, a and b, are required");
        }
        ComparisonRequest n = new ComparisonRequest(normalize(r.a(), now), normalize(r.b(), now));
        return record(Kind.COMPARISON, n, compute(Kind.COMPARISON, n, new OutcomeEvaluator()), now);
    }

    @Transactional
    public Map<String, Object> boundaries(BoundaryRequest r) {
        Instant now = DatabaseTime.now(clock);
        if (r.configuration() == null || r.window() == null) {
            throw new IllegalArgumentException("configuration and an explicit window are required");
        }
        if (r.window().isNegative() || r.window().isZero() || r.window().compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("The window must be positive and at most 366 days");
        }
        BoundaryRequest n = new BoundaryRequest(normalize(r.configuration(), now), r.window());
        return record(Kind.BOUNDARY, n, compute(Kind.BOUNDARY, n, new OutcomeEvaluator()), now);
    }

    @Transactional
    public Map<String, Object> population(Configuration r) {
        Instant now = DatabaseTime.now(clock);
        if (r == null) {
            throw new IllegalArgumentException("A configuration is required");
        }
        Configuration n = normalize(r, now);
        return record(Kind.POPULATION, n, compute(Kind.POPULATION, n, new OutcomeEvaluator()), now);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        Map<String, Object> row = row(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("kind", row.get("kind"));
        out.put("configuration", read((String) row.get("configuration")));
        out.put("configurationHash", row.get("configuration_hash"));
        out.put("inputHash", row.get("input_hash"));
        out.put("calculationVersion", row.get("calculation_version"));
        out.put("outputHash", row.get("output_hash"));
        out.put("evaluatedAt", ((Timestamp) row.get("evaluated_at")).toInstant().toString());
        out.put("report", read((String) row.get("report")));
        return out;
    }

    /** Recomputes a stored report from its stored configuration and compares all three hashes. */
    @Transactional(readOnly = true)
    public Reproduction reproduce(UUID id) {
        Map<String, Object> row = row(id);
        Kind kind = Kind.valueOf((String) row.get("kind"));
        JsonNode config = read((String) row.get("configuration"));
        try {
            Class<?> type = switch (kind) {
                case HORIZON -> HorizonRequest.class;
                case KNOWLEDGE -> KnowledgeRequest.class;
                case COMPARISON -> ComparisonRequest.class;
                case BOUNDARY -> BoundaryRequest.class;
                case POPULATION -> Configuration.class;
            };
            Object request = json.treeToValue(config, type);
            Computed c = compute(kind, request, new OutcomeEvaluator());
            String configHash = hash(request);
            String inputHash = hash(c.inputs());
            String outputHash = hash(c.report());
            return new Reproduction(id, kind.name(), (String) row.get("configuration_hash"), configHash,
                    (String) row.get("input_hash"), inputHash, (String) row.get("output_hash"), outputHash,
                    configHash.equals(row.get("configuration_hash")) && inputHash.equals(row.get("input_hash"))
                            && outputHash.equals(row.get("output_hash"))
                            && calculationVersion().equals(row.get("calculation_version")));
        } catch (Exception e) {
            throw new IllegalStateException("Stored sensitivity configuration is unreadable", e);
        }
    }

    // =================================================================== computation

    /**
     * The analysis for a normalized request. No clock, no current state: only immutable
     * definitions, memberships and histories. {@code evaluator} is the correct one except in
     * mutation tests.
     */
    Computed compute(Kind kind, Object request, OutcomeEvaluator evaluator) {
        SensitivityAnalysis analysis = new SensitivityAnalysis(evaluator);
        Inputs inputs = new Inputs();
        Map<String, Object> result = switch (kind) {
            case HORIZON -> {
                HorizonRequest r = (HorizonRequest) request;
                yield analysis.horizons(resolve(r.configuration(), inputs), r.horizonsDays());
            }
            case KNOWLEDGE -> {
                KnowledgeRequest r = (KnowledgeRequest) request;
                yield analysis.knowledge(resolve(r.configuration(), inputs), r.cutoffs(), r.includeDecisionTime());
            }
            case COMPARISON -> {
                ComparisonRequest r = (ComparisonRequest) request;
                yield analysis.compare(resolve(r.a(), inputs), resolve(r.b(), inputs));
            }
            case BOUNDARY -> {
                BoundaryRequest r = (BoundaryRequest) request;
                yield analysis.boundaries(resolve(r.configuration(), inputs), r.window());
            }
            case POPULATION -> analysis.population(resolve((Configuration) request, inputs));
        };
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "research-sensitivity-report/v1");
        report.put("kind", kind.name());
        report.put("calculationVersion", calculationVersion());
        report.put("inputs", inputs.json());
        report.putAll(result);
        report.put("statisticalDiscipline", "descriptive only: no hypothesis test, confidence interval, p-value, model "
                + "or causal estimate; a difference between descriptive proportions is not a statistical finding");
        report.put("notProvided", List.of("prediction", "risk score", "ranking of information states",
                "policy recommendation", "causal interpretation", "outcomes for declined applicants"));
        return new Computed(report, inputs.json());
    }

    /** Definitions (stored and content hashes) and memberships a computation used. */
    private final class Inputs {
        final Map<String, Object> definitions = new LinkedHashMap<>();
        final Map<String, Object> memberships = new LinkedHashMap<>();

        void definition(OutcomeResearchService.DefinitionHashes h) {
            definitions.put(h.kind() + ":" + h.code() + " v" + h.version(),
                    Map.of("storedHash", h.storedHash(), "contentHash", h.contentHash(), "intact", h.intact()));
        }

        Map<String, Object> json() {
            return Map.of("definitions", definitions, "memberships", memberships);
        }
    }

    private SensitivityAnalysis.Resolved resolve(Configuration c, Inputs inputs) {
        var cohortHashes = research.cohortDefinitionHashes(c.cohortCode(), c.cohortVersion());
        var outcomeHashes = research.outcomeDefinitionHashes(c.definitionCode(), c.definitionVersion());
        inputs.definition(cohortHashes);
        inputs.definition(outcomeHashes);
        var membership = research.membership(research.cohortDefinition(c.cohortCode(), c.cohortVersion()),
                OutcomeResearchService.FROM_SNAPSHOT);
        inputs.memberships.put(c.cohortCode() + " v" + c.cohortVersion(), membership.membershipHash());
        OutcomeEvaluator.Definition d = research.outcomeDefinition(c.definitionCode(), c.definitionVersion());
        if (c.horizonDays() != null) {
            d = SensitivityAnalysis.withHorizon(d, c.horizonDays());
        }
        return new SensitivityAnalysis.Resolved(membership, d, c.knowledgeBasis(), c.knownAt(), c.informationDimension());
    }

    // ================================================================= normalization

    /** Every parameter explicit and valid; every instant at database precision and not in the future. */
    private Configuration normalize(Configuration c, Instant now) {
        if (c.knowledgeBasis() == null) {
            throw new IllegalArgumentException("knowledgeBasis is required: AS_KNOWN_AT with an explicit knownAt, "
                    + "or AS_KNOWN_AT_DECISION");
        }
        Instant k = switch (c.knowledgeBasis()) {
            case AS_KNOWN_AT -> {
                if (c.knownAt() == null) {
                    throw new IllegalArgumentException("AS_KNOWN_AT needs an explicit knownAt; the current clock is never "
                            + "used implicitly");
                }
                yield knowable(c.knownAt(), now);
            }
            case AS_KNOWN_AT_DECISION -> {
                if (c.knownAt() != null) {
                    throw new IllegalArgumentException("AS_KNOWN_AT_DECISION takes no knownAt");
                }
                yield null;
            }
            case RESEARCH_CURRENT -> throw new IllegalArgumentException("Give the current knowledge cutoff as an explicit "
                    + "AS_KNOWN_AT timestamp: a sensitivity result must not depend on when it was run");
        };
        if (c.horizonDays() != null && c.horizonDays() < 1) {
            throw new IllegalArgumentException("horizonDays must be at least 1");
        }
        Configuration n = new Configuration(c.cohortCode(), c.cohortVersion(), c.definitionCode(), c.definitionVersion(),
                c.horizonDays(), c.knowledgeBasis(), k, dimension(c.informationDimension()));
        requireReferences(n);
        return n;
    }

    private void requireReferences(Configuration c) {
        if (c.cohortCode() == null || c.cohortVersion() == null || c.definitionCode() == null
                || c.definitionVersion() == null) {
            throw new IllegalArgumentException("cohortCode, cohortVersion, definitionCode and definitionVersion are required");
        }
        for (var h : List.of(research.cohortDefinitionHashes(c.cohortCode(), c.cohortVersion()),
                research.outcomeDefinitionHashes(c.definitionCode(), c.definitionVersion()))) {
            if (!h.intact()) {
                throw new ConflictException("research_definition.altered", h.kind() + " " + h.code() + " v" + h.version()
                        + " no longer matches the hash it was created with; research definitions are immutable");
            }
        }
    }

    private static Instant knowable(Instant knownAt, Instant now) {
        Instant k = DatabaseTime.normalize(knownAt);
        if (k.isAfter(now)) {
            throw new IllegalArgumentException("Knowledge cannot be taken from the future: knownAt " + k + " > now");
        }
        return k;
    }

    private static String dimension(String d) {
        String v = d == null ? "completeness" : d;
        if (!OutcomeResearchService.DIMENSIONS.contains(v)) {
            throw new IllegalArgumentException("informationDimension must be one of " + OutcomeResearchService.DIMENSIONS);
        }
        return v;
    }

    // =================================================================== persistence

    private Map<String, Object> record(Kind kind, Object request, Computed c, Instant now) {
        UUID id = UUID.randomUUID();
        String configuration = CanonicalJson.canonical(json, json.valueToTree(request));
        String report = CanonicalJson.canonical(json, json.valueToTree(c.report()));
        String configurationHash = CanonicalJson.sha256(configuration);
        String inputHash = hash(c.inputs());
        String outputHash = CanonicalJson.sha256(report);
        jdbc.update("""
                INSERT INTO research_sensitivity_report (id, kind, configuration, configuration_hash, input_hash,
                    calculation_version, report, output_hash, evaluated_at, evaluated_by)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?)""", id, kind.name(), configuration, configurationHash,
                inputHash, calculationVersion(), report, outputHash, Timestamp.from(now),
                RequestContext.forOperation("research.sensitivity").actor());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("kind", kind.name());
        out.put("configurationHash", configurationHash);
        out.put("inputHash", inputHash);
        out.put("outputHash", outputHash);
        out.put("calculationVersion", calculationVersion());
        out.put("report", c.report());
        return out;
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT kind, configuration::text AS configuration, configuration_hash, input_hash, calculation_version,
                       report::text AS report, output_hash, evaluated_at
                  FROM research_sensitivity_report WHERE id = ?""", id);
        if (rows.isEmpty()) {
            throw NotFoundException.of("sensitivity_report", id);
        }
        return rows.get(0);
    }

    private String hash(Object o) {
        return CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(o)));
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
