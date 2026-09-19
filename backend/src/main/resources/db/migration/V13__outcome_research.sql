-- =====================================================================
-- V13 : Outcome association, censoring and research cohorts
--
--   research_outcome_definition      : versioned, deterministic outcome definitions
--                                      (event, threshold, horizon as data)
--   research_cohort_definition       : versioned, reproducible research populations
--   research_outcome_report          : a reproducible descriptive report (cohort x
--                                      outcome definitions x knowledge cutoff)
--   research_counterfactual_outcome  : the policy-intervention boundary: an observed
--                                      outcome belongs to the ACTUAL decision; the
--                                      outcome of a hypothetical decision is UNOBSERVED
--
-- Research artefacts only. Nothing here references them from the banking record, and
-- nothing here is written into a banking table. All four are append-only.
-- See docs/outcome-research.md.
-- =====================================================================

CREATE TABLE research_outcome_definition (
    code             TEXT        NOT NULL,
    version          INTEGER     NOT NULL,
    definition       JSONB       NOT NULL,
    definition_hash  TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    created_by       TEXT        NOT NULL,

    PRIMARY KEY (code, version),
    CONSTRAINT outcome_def_version_positive CHECK (version >= 1),
    CONSTRAINT outcome_def_object CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT outcome_def_event_valid CHECK (definition ->> 'event' IN
        ('DEFAULT', 'SETTLEMENT', 'DELINQUENCY_DERIVED', 'DELINQUENCY_BANK_OBSERVED')),
    -- a delinquency outcome needs a threshold; the others must not carry one
    CONSTRAINT outcome_def_threshold CHECK (
        (definition ->> 'event' LIKE 'DELINQUENCY_%' AND (definition ->> 'thresholdDaysPastDue')::int >= 1)
     OR (definition ->> 'event' NOT LIKE 'DELINQUENCY_%'
         AND coalesce(jsonb_typeof(definition -> 'thresholdDaysPastDue'), 'null') = 'null')),
    CONSTRAINT outcome_def_horizon_days CHECK ((definition ->> 'horizonDays')::int >= 1),
    CONSTRAINT outcome_def_hash_hex CHECK (definition_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE research_cohort_definition (
    code             TEXT        NOT NULL,
    version          INTEGER     NOT NULL,
    definition       JSONB       NOT NULL,
    definition_hash  TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    created_by       TEXT        NOT NULL,

    PRIMARY KEY (code, version),
    CONSTRAINT cohort_def_version_positive CHECK (version >= 1),
    CONSTRAINT cohort_def_object CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT cohort_def_window CHECK ((definition ->> 'decidedFrom')::timestamptz < (definition ->> 'decidedTo')::timestamptz),
    -- membership may only use knowledge that existed when the cohort was defined: it can never change later
    CONSTRAINT cohort_def_known_by_creation CHECK ((definition ->> 'knownAt')::timestamptz <= created_at),
    CONSTRAINT cohort_def_hash_hex CHECK (definition_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE research_outcome_report (
    id                    UUID        PRIMARY KEY,
    cohort_code           TEXT        NOT NULL,
    cohort_version        INTEGER     NOT NULL,
    outcome_definitions   JSONB       NOT NULL,
    knowledge_basis       TEXT        NOT NULL,
    known_at              TIMESTAMPTZ,
    evaluated_at          TIMESTAMPTZ NOT NULL,
    evaluated_by          TEXT        NOT NULL,
    population_hash       TEXT        NOT NULL,
    report                JSONB       NOT NULL,
    output_hash           TEXT        NOT NULL,

    CONSTRAINT report_cohort_fk FOREIGN KEY (cohort_code, cohort_version)
        REFERENCES research_cohort_definition (code, version),
    CONSTRAINT report_definitions_array CHECK (jsonb_typeof(outcome_definitions) = 'array'),
    -- AS_KNOWN_AT_DECISION uses each decision's own time; the others name one instant
    CONSTRAINT report_basis_valid CHECK (
        (knowledge_basis = 'AS_KNOWN_AT_DECISION' AND known_at IS NULL)
     OR (knowledge_basis IN ('AS_KNOWN_AT', 'RESEARCH_CURRENT') AND known_at IS NOT NULL)),
    CONSTRAINT report_known_by_evaluation CHECK (known_at IS NULL OR known_at <= evaluated_at),
    CONSTRAINT report_object CHECK (jsonb_typeof(report) = 'object'),
    CONSTRAINT report_hashes_hex CHECK (population_hash ~ '^[0-9a-f]{64}$' AND output_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX outcome_report_cohort_idx ON research_outcome_report (cohort_code, cohort_version);

CREATE TABLE research_counterfactual_outcome (
    id                          UUID        PRIMARY KEY,
    counterfactual_id           UUID        NOT NULL REFERENCES research_counterfactual_decision (id),
    source_decision_id          UUID        NOT NULL REFERENCES loan_decision (id),
    outcome_definition_code     TEXT        NOT NULL,
    outcome_definition_version  INTEGER     NOT NULL,
    known_at                    TIMESTAMPTZ NOT NULL,
    actual_decision             TEXT        NOT NULL,
    counterfactual_decision     TEXT        NOT NULL,
    actual_outcome_status       TEXT        NOT NULL,
    actual_outcome_value        TEXT,
    -- an observed outcome is the consequence of the decision that was actually taken
    actual_outcome_attributed_to TEXT       NOT NULL DEFAULT 'ACTUAL_DECISION',
    -- the outcome of a decision that was never taken was never observed; it is not estimated
    counterfactual_outcome      TEXT        NOT NULL DEFAULT 'UNOBSERVED',
    evaluated_at                TIMESTAMPTZ NOT NULL,

    CONSTRAINT cfo_definition_fk FOREIGN KEY (outcome_definition_code, outcome_definition_version)
        REFERENCES research_outcome_definition (code, version),
    CONSTRAINT cfo_actual_decision_valid CHECK (actual_decision IN ('APPROVED', 'DECLINED')),
    CONSTRAINT cfo_status_valid CHECK (actual_outcome_status IN ('OBSERVED', 'CENSORED', 'NOT_APPLICABLE', 'UNKNOWN')),
    CONSTRAINT cfo_value_valid CHECK (
        (actual_outcome_status = 'OBSERVED' AND actual_outcome_value IN ('OCCURRED', 'NOT_OCCURRED'))
     OR (actual_outcome_status <> 'OBSERVED' AND actual_outcome_value IS NULL)),
    -- a declined application has no loan and so no outcome: never observed, never inferred
    CONSTRAINT cfo_declined_unknown CHECK (actual_decision <> 'DECLINED' OR actual_outcome_status = 'UNKNOWN'),
    CONSTRAINT cfo_attributed_to_actual CHECK (actual_outcome_attributed_to = 'ACTUAL_DECISION'),
    CONSTRAINT cfo_counterfactual_unobserved CHECK (counterfactual_outcome = 'UNOBSERVED'),
    CONSTRAINT cfo_known_by_evaluation CHECK (known_at <= evaluated_at)
);
CREATE INDEX cfo_counterfactual_idx ON research_counterfactual_outcome (counterfactual_id);

CREATE TRIGGER research_outcome_definition_immutable BEFORE UPDATE OR DELETE ON research_outcome_definition
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER research_cohort_definition_immutable BEFORE UPDATE OR DELETE ON research_cohort_definition
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER research_outcome_report_immutable BEFORE UPDATE OR DELETE ON research_outcome_report
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER research_counterfactual_outcome_immutable BEFORE UPDATE OR DELETE ON research_counterfactual_outcome
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

COMMENT ON TABLE research_counterfactual_outcome IS
    'Actual decision, counterfactual decision and the observed outcome of the ACTUAL decision. The counterfactual outcome is always UNOBSERVED.';
