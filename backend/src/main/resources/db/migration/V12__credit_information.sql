-- =====================================================================
-- V12 : Credit information & affordability state
--
--   borrower_financial_fact  : append-only, bitemporal observations about a party's
--                              income, recurring obligations, obligation disclosure
--                              and employment, each with provenance
--   affordability_assessment : append-only record of a deterministic, versioned
--                              affordability calculation (input, output, hashes)
--
-- A fact that is absent stays UNKNOWN: nothing here defaults a missing value.
-- See docs/credit-information.md.
-- =====================================================================

CREATE TABLE borrower_financial_fact (
    id                     UUID        PRIMARY KEY,
    seq                    BIGINT      GENERATED ALWAYS AS IDENTITY,
    party_id               UUID        NOT NULL REFERENCES party (id),
    -- All observations about the same underlying thing (one income source, one
    -- obligation, the employment relationship, the disclosure) share a series.
    series_id              UUID        NOT NULL,
    fact_kind              TEXT        NOT NULL,
    fact_type              TEXT        NOT NULL,
    status                 TEXT        NOT NULL,
    amount_minor           BIGINT,
    currency               CHAR(3)     REFERENCES currency (code),
    frequency              TEXT,
    outstanding_minor      BIGINT,
    employment_start_date  DATE,
    -- The business date from which the income/payment applies. May be later than the
    -- observation (a signed contract starting next month); such amounts are FUTURE.
    applies_from           DATE,
    provenance             TEXT        NOT NULL,
    source                 TEXT        NOT NULL,
    evidence_reference     TEXT,
    verifies_fact_id       UUID        REFERENCES borrower_financial_fact (id),
    effective_at           TIMESTAMPTZ NOT NULL,
    recorded_at            TIMESTAMPTZ NOT NULL,
    recorded_by            TEXT        NOT NULL,
    recorder_type          TEXT        NOT NULL,
    correlation_id         UUID,

    CONSTRAINT fact_seq_unique UNIQUE (seq),
    CONSTRAINT fact_kind_valid CHECK (fact_kind IN ('INCOME', 'RECURRING_OBLIGATION', 'OBLIGATION_DISCLOSURE', 'EMPLOYMENT')),
    CONSTRAINT fact_type_valid CHECK (
        (fact_kind = 'INCOME' AND fact_type IN ('SALARY', 'SELF_EMPLOYMENT', 'PENSION', 'BENEFITS', 'RENTAL', 'INVESTMENT', 'OTHER'))
     OR (fact_kind = 'RECURRING_OBLIGATION' AND fact_type IN ('LOAN', 'CREDIT_CARD', 'RENT', 'CONTRACTUAL_PAYMENT', 'OTHER'))
     OR (fact_kind = 'OBLIGATION_DISCLOSURE' AND fact_type = 'ALL_RECURRING_OBLIGATIONS')
     OR (fact_kind = 'EMPLOYMENT' AND fact_type IN ('EMPLOYED', 'SELF_EMPLOYED', 'UNEMPLOYED', 'RETIRED', 'STUDENT', 'OTHER'))),
    CONSTRAINT fact_status_valid CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT fact_provenance_valid CHECK (provenance IN ('DECLARED', 'VERIFIED')),
    CONSTRAINT fact_verified_has_evidence
        CHECK (provenance <> 'VERIFIED' OR (evidence_reference IS NOT NULL AND length(btrim(evidence_reference)) > 0)),
    CONSTRAINT fact_frequency_valid CHECK (frequency IS NULL OR frequency IN ('MONTHLY', 'ANNUAL')),
    CONSTRAINT fact_amounts_non_negative
        CHECK ((amount_minor IS NULL OR amount_minor >= 0) AND (outstanding_minor IS NULL OR outstanding_minor >= 0)),
    -- Shape per kind: an active income or obligation observation is only meaningful with an
    -- amount, a currency and a frequency; disclosures and employment carry no amount.
    CONSTRAINT fact_shape CHECK (
        (fact_kind IN ('INCOME', 'RECURRING_OBLIGATION') AND (status = 'ENDED'
            OR (amount_minor IS NOT NULL AND currency IS NOT NULL AND frequency IS NOT NULL)))
     OR (fact_kind IN ('OBLIGATION_DISCLOSURE', 'EMPLOYMENT') AND amount_minor IS NULL AND frequency IS NULL
            AND outstanding_minor IS NULL)),
    CONSTRAINT fact_outstanding_only_for_obligations
        CHECK (outstanding_minor IS NULL OR fact_kind = 'RECURRING_OBLIGATION'),
    CONSTRAINT fact_start_only_for_employment
        CHECK (employment_start_date IS NULL OR fact_kind = 'EMPLOYMENT'),
    -- Nothing is recorded as having become true in the future.
    CONSTRAINT fact_not_future_dated CHECK (effective_at <= recorded_at),
    CONSTRAINT fact_source_present CHECK (length(btrim(source)) > 0),
    CONSTRAINT fact_recorder_type_valid CHECK (recorder_type IN ('HUMAN', 'SYSTEM', 'SERVICE', 'AGENT'))
);

CREATE INDEX fact_party_pit_idx ON borrower_financial_fact (party_id, recorded_at, effective_at);
CREATE INDEX fact_series_idx ON borrower_financial_fact (series_id, seq);

CREATE FUNCTION wbank_financial_fact_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    first borrower_financial_fact%ROWTYPE;
    target borrower_financial_fact%ROWTYPE;
BEGIN
    SELECT * INTO first FROM borrower_financial_fact WHERE series_id = NEW.series_id ORDER BY seq LIMIT 1;
    IF FOUND AND (first.party_id <> NEW.party_id OR first.fact_kind <> NEW.fact_kind) THEN
        RAISE EXCEPTION 'series % belongs to party % / %', NEW.series_id, first.party_id, first.fact_kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.verifies_fact_id IS NOT NULL THEN
        SELECT * INTO target FROM borrower_financial_fact WHERE id = NEW.verifies_fact_id;
        IF target.series_id <> NEW.series_id OR NEW.provenance <> 'VERIFIED' OR target.recorded_at > NEW.recorded_at THEN
            RAISE EXCEPTION 'a verification must be a VERIFIED observation of the same series, recorded after what it verifies'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER financial_fact_guard BEFORE INSERT ON borrower_financial_fact
    FOR EACH ROW EXECUTE FUNCTION wbank_financial_fact_guard();
CREATE TRIGGER financial_fact_append_only BEFORE UPDATE OR DELETE ON borrower_financial_fact
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

COMMENT ON TABLE borrower_financial_fact IS
    'Append-only bitemporal observations of a party''s financial situation. effective_at: when true; recorded_at: when known.';

CREATE TABLE affordability_assessment (
    id                   UUID        PRIMARY KEY,
    party_id             UUID        NOT NULL REFERENCES party (id),
    obligation_id        UUID        REFERENCES obligation (id),
    purpose              TEXT        NOT NULL,
    calculation_version  TEXT        NOT NULL,
    as_of                TIMESTAMPTZ NOT NULL,
    known_at             TIMESTAMPTZ NOT NULL,
    input                JSONB       NOT NULL,
    input_hash           TEXT        NOT NULL,
    output               JSONB       NOT NULL,
    output_hash          TEXT        NOT NULL,
    completeness         TEXT        NOT NULL,
    computed_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT assessment_purpose_valid CHECK (purpose IN ('DECISION', 'ENQUIRY', 'RESEARCH')),
    CONSTRAINT assessment_completeness_valid CHECK (completeness IN ('COMPLETE', 'PARTIAL', 'INSUFFICIENT')),
    CONSTRAINT assessment_hashes_hex CHECK (input_hash ~ '^[0-9a-f]{64}$' AND output_hash ~ '^[0-9a-f]{64}$'),
    -- An assessment can only use knowledge the bank had when it was computed.
    CONSTRAINT assessment_knowledge_not_future CHECK (known_at <= computed_at),
    CONSTRAINT assessment_input_object CHECK (jsonb_typeof(input) = 'object'),
    CONSTRAINT assessment_output_object CHECK (jsonb_typeof(output) = 'object')
);
CREATE INDEX assessment_party_idx ON affordability_assessment (party_id, as_of);
CREATE TRIGGER affordability_assessment_append_only BEFORE UPDATE OR DELETE ON affordability_assessment
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
