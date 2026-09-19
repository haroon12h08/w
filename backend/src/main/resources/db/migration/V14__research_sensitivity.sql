-- =====================================================================
-- V14 : Research sensitivity, selection and robustness
--
--   research_sensitivity_report : one reproducible sensitivity analysis (horizons,
--                                 knowledge cutoffs, configuration comparison, temporal
--                                 boundaries, population/selection view)
--
-- A research artefact only: it references nothing in the banking record and nothing
-- references it. Append-only. Every existing table is left exactly as it is.
-- See docs/research-sensitivity.md.
-- =====================================================================

CREATE TABLE research_sensitivity_report (
    id                   UUID        PRIMARY KEY,
    kind                 TEXT        NOT NULL,
    -- the complete, normalized request: every parameter explicit, every instant at
    -- microsecond precision (what is stored is exactly what was computed from)
    configuration        JSONB       NOT NULL,
    configuration_hash   TEXT        NOT NULL,
    -- definitions (stored and content hashes) and population membership hashes used
    input_hash           TEXT        NOT NULL,
    calculation_version  TEXT        NOT NULL,
    report               JSONB       NOT NULL,
    output_hash          TEXT        NOT NULL,
    evaluated_at         TIMESTAMPTZ NOT NULL,
    evaluated_by         TEXT        NOT NULL,

    CONSTRAINT sensitivity_kind_valid CHECK (kind IN ('HORIZON', 'KNOWLEDGE', 'COMPARISON', 'BOUNDARY', 'POPULATION')),
    CONSTRAINT sensitivity_configuration_object CHECK (jsonb_typeof(configuration) = 'object'),
    CONSTRAINT sensitivity_report_object CHECK (jsonb_typeof(report) = 'object'),
    CONSTRAINT sensitivity_hashes_hex CHECK (configuration_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$'
        AND output_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX sensitivity_kind_idx ON research_sensitivity_report (kind, evaluated_at);

CREATE TRIGGER research_sensitivity_report_immutable BEFORE UPDATE OR DELETE ON research_sensitivity_report
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
