-- =====================================================================
-- V11 : Counterfactual credit-decision research
--
-- Experiments, not banking events. These tables only REFERENCE the banking
-- record (decisions, snapshots, policies); nothing in the banking record
-- references them, and no lending_event is ever written by an experiment.
--
--   research_policy_replay           : one replay of a population under an alternative policy
--   research_counterfactual_decision : what that policy would have decided for one historical decision
--   research_outcome_evaluation      : a separate, later comparison with observed outcomes
--
-- All three are append-only. See docs/counterfactual.md.
-- =====================================================================

CREATE TABLE research_policy_replay (
    id                          UUID        PRIMARY KEY,
    alternative_policy_code     TEXT        NOT NULL,
    alternative_policy_version  INTEGER     NOT NULL,
    population                  JSONB       NOT NULL,
    decision_count              INTEGER     NOT NULL,
    evaluated_at                TIMESTAMPTZ NOT NULL,
    evaluated_by                TEXT        NOT NULL,
    input_hash                  TEXT        NOT NULL,
    output_hash                 TEXT        NOT NULL,

    CONSTRAINT replay_policy_fk FOREIGN KEY (alternative_policy_code, alternative_policy_version)
        REFERENCES credit_policy (policy_code, version),
    CONSTRAINT replay_count_non_negative CHECK (decision_count >= 0),
    CONSTRAINT replay_population_object CHECK (jsonb_typeof(population) = 'object'),
    CONSTRAINT replay_hashes_hex CHECK (input_hash ~ '^[0-9a-f]{64}$' AND output_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE research_counterfactual_decision (
    id                          UUID        PRIMARY KEY,
    replay_id                   UUID        REFERENCES research_policy_replay (id),
    source_decision_id          UUID        NOT NULL REFERENCES loan_decision (id),
    source_snapshot_id          UUID        REFERENCES credit_decision_snapshot (id),
    source_snapshot_sha256      TEXT,
    actual_decision             TEXT        NOT NULL,
    actual_policy_code          TEXT,
    actual_policy_version       INTEGER,
    alternative_policy_code     TEXT        NOT NULL,
    alternative_policy_version  INTEGER     NOT NULL,
    original_decided_at         TIMESTAMPTZ NOT NULL,
    evaluated_at                TIMESTAMPTZ NOT NULL,
    evaluated_by                TEXT        NOT NULL,
    input                       JSONB       NOT NULL,
    input_hash                  TEXT        NOT NULL,
    rule_results                JSONB       NOT NULL,
    policy_result               TEXT        NOT NULL,
    control_policy_result       TEXT,
    hypothetical_decision       TEXT        NOT NULL,
    output_hash                 TEXT        NOT NULL,
    context_check               TEXT        NOT NULL,

    CONSTRAINT cf_alternative_policy_fk FOREIGN KEY (alternative_policy_code, alternative_policy_version)
        REFERENCES credit_policy (policy_code, version),
    CONSTRAINT cf_actual_decision_valid CHECK (actual_decision IN ('APPROVED', 'DECLINED')),
    CONSTRAINT cf_policy_result_valid CHECK (policy_result IN ('PASS', 'FAIL', 'INDETERMINATE', 'NOT_EVALUABLE')),
    CONSTRAINT cf_control_result_valid
        CHECK (control_policy_result IS NULL OR control_policy_result IN ('PASS', 'FAIL', 'INDETERMINATE')),
    -- The hypothetical decision is a function of the policy result; nothing else.
    CONSTRAINT cf_hypothetical_follows_result CHECK (
        (policy_result = 'PASS'          AND hypothetical_decision = 'APPROVED')
     OR (policy_result = 'FAIL'          AND hypothetical_decision = 'DECLINED')
     OR (policy_result = 'INDETERMINATE' AND hypothetical_decision = 'UNDETERMINED')
     OR (policy_result = 'NOT_EVALUABLE' AND hypothetical_decision = 'NOT_EVALUABLE')),
    -- Only a decision with a captured snapshot can be evaluated.
    CONSTRAINT cf_snapshot_required CHECK ((policy_result = 'NOT_EVALUABLE') = (source_snapshot_id IS NULL)),
    CONSTRAINT cf_context_check_valid CHECK (context_check IN ('CONSISTENT', 'INCONSISTENT', 'NOT_CHECKED')),
    -- An experiment happens after the decision it replays.
    CONSTRAINT cf_evaluated_after_decision CHECK (evaluated_at >= original_decided_at),
    CONSTRAINT cf_hashes_hex CHECK (input_hash ~ '^[0-9a-f]{64}$' AND output_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT cf_input_object CHECK (jsonb_typeof(input) = 'object'),
    CONSTRAINT cf_rule_results_array CHECK (jsonb_typeof(rule_results) = 'array')
);

CREATE INDEX cf_source_decision_idx ON research_counterfactual_decision (source_decision_id);
CREATE INDEX cf_replay_idx ON research_counterfactual_decision (replay_id);

CREATE TABLE research_outcome_evaluation (
    id                UUID        PRIMARY KEY,
    replay_id         UUID        NOT NULL REFERENCES research_policy_replay (id),
    outcome_horizon   TEXT        NOT NULL,
    outcome_known_at  TIMESTAMPTZ NOT NULL,
    evaluated_at      TIMESTAMPTZ NOT NULL,
    evaluated_by      TEXT        NOT NULL,
    report            JSONB       NOT NULL,
    report_sha256     TEXT        NOT NULL,

    CONSTRAINT outcome_eval_report_object CHECK (jsonb_typeof(report) = 'object'),
    CONSTRAINT outcome_eval_hash_hex CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT outcome_eval_known_by_evaluation CHECK (outcome_known_at <= evaluated_at)
);

CREATE TRIGGER research_policy_replay_immutable BEFORE UPDATE OR DELETE ON research_policy_replay
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER research_counterfactual_immutable BEFORE UPDATE OR DELETE ON research_counterfactual_decision
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER research_outcome_evaluation_immutable BEFORE UPDATE OR DELETE ON research_outcome_evaluation
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

COMMENT ON TABLE research_counterfactual_decision IS
    'A hypothetical decision computed from a historical decision snapshot under an alternative policy. Never a banking event.';
