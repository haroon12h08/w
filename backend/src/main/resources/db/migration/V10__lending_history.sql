-- =====================================================================
-- V10 : Point-in-time lending history
--
--   lending_event            : the immutable, ordered history of every loan
--                              (bitemporal: effective_at / recorded_at)
--   credit_policy            : append-only, versioned decision rules
--   credit_decision_snapshot : what was known when each credit decision was made
--
-- Existing lending facts are backfilled into lending_event with their ORIGINAL
-- timestamps, marked origin = 'BACKFILL_V10'. Decisions made before V10 have no
-- snapshot: that context was never captured and is not fabricated.
-- See docs/lending-history.md.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Versioned credit policy (append-only; never retroactive)
-- ---------------------------------------------------------------------
CREATE TABLE credit_policy (
    policy_code     TEXT        NOT NULL,
    version         INTEGER     NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL,
    rules           JSONB       NOT NULL,
    description     TEXT        NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL,
    published_by    TEXT        NOT NULL,

    PRIMARY KEY (policy_code, version),
    CONSTRAINT credit_policy_version_positive CHECK (version >= 1),
    CONSTRAINT credit_policy_rules_object CHECK (jsonb_typeof(rules) = 'object'),
    CONSTRAINT credit_policy_effective_unique UNIQUE (policy_code, effective_from)
);

CREATE FUNCTION wbank_credit_policy_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    prev credit_policy%ROWTYPE;
BEGIN
    SELECT * INTO prev FROM credit_policy
     WHERE policy_code = NEW.policy_code ORDER BY version DESC LIMIT 1;
    IF FOUND THEN
        IF NEW.version <> prev.version + 1 THEN
            RAISE EXCEPTION 'credit_policy % version must be %, not %', NEW.policy_code, prev.version + 1, NEW.version
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.effective_from <= prev.effective_from THEN
            RAISE EXCEPTION 'credit_policy % v% must take effect after v%', NEW.policy_code, NEW.version, prev.version
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        -- Rules may not change the past: a new version takes effect no earlier than it is published.
        IF NEW.effective_from < NEW.published_at THEN
            RAISE EXCEPTION 'credit_policy % v% would be retroactive', NEW.policy_code, NEW.version
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.version <> 1 THEN
        RAISE EXCEPTION 'first credit_policy version must be 1' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER credit_policy_guard BEFORE INSERT ON credit_policy
    FOR EACH ROW EXECUTE FUNCTION wbank_credit_policy_guard();
CREATE TRIGGER credit_policy_immutable BEFORE UPDATE OR DELETE ON credit_policy
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- v1 codifies the rules phase 4 applied implicitly. Effective from system inception.
INSERT INTO credit_policy (policy_code, version, effective_from, rules, description, published_at, published_by)
VALUES ('LENDING_CREDIT_POLICY', 1, TIMESTAMPTZ '2000-01-01 00:00:00+00',
        '{"requireActiveCustomer": true,
          "maxPrincipalMajor": 50000,
          "maxInstallments": 360,
          "maxAnnualRateBps": 3000,
          "blockIfAnyObligationDefaulted": true,
          "maxExistingDaysPastDue": 29,
          "defaultDeclarationMinDaysPastDue": 90}'::jsonb,
        'Initial lending eligibility rules (codified from phase 4)',
        TIMESTAMPTZ '2000-01-01 00:00:00+00', 'migration:V10');

-- Decisions name the policy version they were made under (NULL only for pre-V10 decisions).
ALTER TABLE loan_decision
    ADD COLUMN policy_code    TEXT,
    ADD COLUMN policy_version INTEGER,
    ADD CONSTRAINT loan_decision_policy_fk
        FOREIGN KEY (policy_code, policy_version) REFERENCES credit_policy (policy_code, version),
    ADD CONSTRAINT loan_decision_policy_pair
        CHECK ((policy_code IS NULL) = (policy_version IS NULL));

-- ---------------------------------------------------------------------
-- 2. Decision snapshots (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE credit_decision_snapshot (
    id              UUID        PRIMARY KEY,
    decision_id     UUID        NOT NULL REFERENCES loan_decision (id),
    obligation_id   UUID        NOT NULL REFERENCES obligation (id),
    policy_code     TEXT        NOT NULL,
    policy_version  INTEGER     NOT NULL,
    decided_at      TIMESTAMPTZ NOT NULL,
    content         JSONB       NOT NULL,
    content_sha256  TEXT        NOT NULL,

    CONSTRAINT snapshot_decision_unique UNIQUE (decision_id),
    CONSTRAINT snapshot_policy_fk FOREIGN KEY (policy_code, policy_version)
        REFERENCES credit_policy (policy_code, version),
    CONSTRAINT snapshot_content_object CHECK (jsonb_typeof(content) = 'object'),
    CONSTRAINT snapshot_hash_hex CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX snapshot_obligation_idx ON credit_decision_snapshot (obligation_id, decided_at);
CREATE TRIGGER credit_decision_snapshot_immutable BEFORE UPDATE OR DELETE ON credit_decision_snapshot
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- ---------------------------------------------------------------------
-- 3. The lending event history
-- ---------------------------------------------------------------------
CREATE TABLE lending_event (
    id                 UUID        PRIMARY KEY,
    global_seq         BIGINT      GENERATED ALWAYS AS IDENTITY,
    obligation_id      UUID        NOT NULL REFERENCES obligation (id),
    loan_seq           INTEGER     NOT NULL,
    event_type         TEXT        NOT NULL,
    event_kind         TEXT        NOT NULL,
    effective_at       TIMESTAMPTZ NOT NULL,
    recorded_at        TIMESTAMPTZ NOT NULL,
    payload            JSONB       NOT NULL,
    source_table       TEXT,
    source_id          UUID,
    corrects_event_id  UUID        REFERENCES lending_event (id),
    actor              TEXT        NOT NULL,
    actor_type         TEXT        NOT NULL,
    correlation_id     UUID,
    origin             TEXT        NOT NULL,
    schema_version     SMALLINT    NOT NULL DEFAULT 1,

    CONSTRAINT lending_event_global_seq_unique UNIQUE (global_seq),
    CONSTRAINT lending_event_loan_seq_unique   UNIQUE (obligation_id, loan_seq),
    CONSTRAINT lending_event_loan_seq_positive CHECK (loan_seq >= 1),
    CONSTRAINT lending_event_type_kind CHECK (
        (event_type IN ('APPLICATION_RECEIVED', 'APPLICATION_CANCELLED', 'LOAN_DISBURSED', 'SCHEDULE_ESTABLISHED',
                        'REPAYMENT_RECEIVED', 'INTEREST_ACCRUED', 'LOAN_SETTLED') AND event_kind = 'FACT')
     OR (event_type IN ('CREDIT_DECISION', 'DEFAULT_DECLARED') AND event_kind = 'DECISION')
     OR (event_type = 'DELINQUENCY_CHANGED' AND event_kind = 'OBSERVATION')
     OR (event_type = 'EVENT_CORRECTED' AND event_kind = 'CORRECTION')),
    -- Nothing is recorded as having taken effect in the future.
    CONSTRAINT lending_event_not_future_dated CHECK (effective_at <= recorded_at),
    CONSTRAINT lending_event_correction_link CHECK ((event_type = 'EVENT_CORRECTED') = (corrects_event_id IS NOT NULL)),
    CONSTRAINT lending_event_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT lending_event_origin_valid CHECK (origin IN ('LIVE', 'BACKFILL_V10')),
    CONSTRAINT lending_event_actor_type_valid CHECK (actor_type IN ('HUMAN', 'SYSTEM', 'SERVICE', 'AGENT'))
);

-- One history event per underlying fact.
CREATE UNIQUE INDEX lending_event_source_unique
    ON lending_event (source_table, source_id) WHERE source_id IS NOT NULL;
CREATE INDEX lending_event_pit_idx ON lending_event (obligation_id, recorded_at, loan_seq);

COMMENT ON TABLE lending_event IS
    'Immutable per-loan history. effective_at: when true in the business world; recorded_at: when the bank knew.';

-- ---------------------------------------------------------------------
-- 4. Backfill existing lending facts with their original timestamps.
--    Rank breaks ties between events recorded at the same instant in the
--    order the application writes them.
-- ---------------------------------------------------------------------
CREATE TEMPORARY TABLE v10_backfill (
    obligation_id UUID, event_type TEXT, event_kind TEXT, effective_at TIMESTAMPTZ, recorded_at TIMESTAMPTZ,
    payload JSONB, source_table TEXT, source_id UUID, actor TEXT, actor_type TEXT, correlation_id UUID,
    rank INTEGER, tiebreak TEXT
) ON COMMIT DROP;

INSERT INTO v10_backfill
SELECT o.id, 'APPLICATION_RECEIVED', 'FACT', o.proposed_at, o.proposed_at,
       jsonb_build_object('obligationNumber', o.obligation_number, 'debtorPartyId', o.debtor_party_id,
            'creditorPartyId', o.creditor_party_id, 'productCode', a.product_code, 'currency', o.currency,
            'principalMinor', o.principal_minor, 'annualRateBps', t.annual_rate_bps,
            'installmentCount', t.installment_count, 'allocationPolicy', t.allocation_policy,
            'interestRecognition', t.interest_recognition, 'positionAccountId', o.position_account_id,
            'settlementAccountId', o.settlement_account_id),
       'obligation', o.id, 'migration:V10', 'SYSTEM', NULL, 1, ''
  FROM obligation o JOIN loan_terms t ON t.obligation_id = o.id JOIN account a ON a.id = o.position_account_id;

INSERT INTO v10_backfill
SELECT d.obligation_id,
       CASE d.decision WHEN 'DEFAULT_DECLARED' THEN 'DEFAULT_DECLARED' ELSE 'CREDIT_DECISION' END, 'DECISION',
       d.decided_at, d.decided_at,
       jsonb_build_object('decisionId', d.id, 'decision', d.decision, 'rationale', d.rationale,
            'policyCode', NULL, 'policyVersion', NULL, 'snapshotId', NULL),
       'loan_decision', d.id, d.decided_by, d.decider_type, d.correlation_id,
       CASE d.decision WHEN 'DEFAULT_DECLARED' THEN 9 ELSE 2 END, ''
  FROM loan_decision d;

INSERT INTO v10_backfill
SELECT o.id, 'APPLICATION_CANCELLED', 'FACT', o.cancelled_at, o.cancelled_at, '{}'::jsonb,
       NULL, NULL, 'migration:V10', 'SYSTEM', NULL, 3, ''
  FROM obligation o WHERE o.status = 'CANCELLED';

INSERT INTO v10_backfill
SELECT o.id, 'LOAN_DISBURSED', 'FACT', o.activated_at, o.activated_at,
       jsonb_build_object('journalEntryId', o.disbursement_entry_id, 'principalMinor', o.principal_minor,
            'startDate', o.start_date, 'interestReceivableLedgerAccountId', o.interest_receivable_ledger_account_id),
       NULL, NULL, 'migration:V10', 'SYSTEM', NULL, 4, ''
  FROM obligation o WHERE o.activated_at IS NOT NULL;

INSERT INTO v10_backfill
SELECT o.id, 'SCHEDULE_ESTABLISHED', 'FACT', o.activated_at, o.activated_at,
       jsonb_build_object('maturityDate', o.maturity_date, 'installments',
            (SELECT jsonb_agg(jsonb_build_object('sequence', i.sequence_no, 'dueDate', i.due_date,
                         'principalMinor', i.principal_due_minor, 'interestMinor', i.interest_due_minor)
                     ORDER BY i.sequence_no)
               FROM obligation_installment i WHERE i.obligation_id = o.id)),
       NULL, NULL, 'migration:V10', 'SYSTEM', NULL, 5, ''
  FROM obligation o WHERE o.activated_at IS NOT NULL;

INSERT INTO v10_backfill
SELECT x.obligation_id, 'INTEREST_ACCRUED', 'FACT', x.accrual_date::timestamp AT TIME ZONE 'UTC', x.recorded_at,
       jsonb_build_object('accrualId', x.id, 'installmentSequence', i.sequence_no, 'amountMinor', x.amount_minor,
            'accrualDate', x.accrual_date, 'journalEntryId', x.journal_entry_id),
       'interest_accrual', x.id, 'migration:V10', 'SYSTEM', NULL, 6, lpad(i.sequence_no::text, 5, '0')
  FROM interest_accrual x JOIN obligation_installment i ON i.id = x.installment_id;

INSERT INTO v10_backfill
SELECT r.obligation_id, 'REPAYMENT_RECEIVED', 'FACT', r.received_at, r.received_at,
       jsonb_build_object('repaymentId', r.id, 'journalEntryId', r.journal_entry_id, 'amountMinor', r.amount_minor,
            'principalMinor', r.principal_minor, 'interestMinor', r.interest_minor,
            'allocationPolicy', r.allocation_policy,
            'allocations', coalesce((SELECT jsonb_agg(jsonb_build_object('sequence', i.sequence_no,
                         'principalMinor', ra.principal_minor, 'interestMinor', ra.interest_minor)
                     ORDER BY i.sequence_no)
               FROM repayment_allocation ra JOIN obligation_installment i ON i.id = ra.installment_id
              WHERE ra.repayment_id = r.id), '[]'::jsonb),
            'waivers', coalesce((SELECT jsonb_agg(jsonb_build_object('sequence', i.sequence_no,
                         'interestMinor', w.interest_waived_minor) ORDER BY i.sequence_no)
               FROM installment_waiver w JOIN obligation_installment i ON i.id = w.installment_id
              WHERE w.repayment_id = r.id), '[]'::jsonb)),
       'obligation_repayment', r.id, 'migration:V10', 'SYSTEM', NULL, 7, r.id::text
  FROM obligation_repayment r;

INSERT INTO v10_backfill
SELECT e.obligation_id, 'DELINQUENCY_CHANGED', 'OBSERVATION',
       LEAST(e.as_of::timestamp AT TIME ZONE 'UTC', e.recorded_at), e.recorded_at,
       jsonb_build_object('asOf', e.as_of, 'fromBucket', e.from_bucket, 'toBucket', e.to_bucket,
            'daysPastDue', e.days_past_due, 'overduePrincipalMinor', e.overdue_principal_minor,
            'overdueInterestMinor', e.overdue_interest_minor),
       'delinquency_event', e.id, 'migration:V10', 'SYSTEM', NULL, 8, e.id::text
  FROM delinquency_event e;

INSERT INTO v10_backfill
SELECT o.id, 'LOAN_SETTLED', 'FACT', o.settled_at, o.settled_at, '{}'::jsonb,
       NULL, NULL, 'migration:V10', 'SYSTEM', NULL, 10, ''
  FROM obligation o WHERE o.status = 'SETTLED';

INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at, payload,
                           source_table, source_id, actor, actor_type, correlation_id, origin)
SELECT gen_random_uuid(), obligation_id,
       row_number() OVER (PARTITION BY obligation_id ORDER BY recorded_at, rank, tiebreak),
       event_type, event_kind, effective_at, recorded_at, payload, source_table, source_id,
       actor, actor_type, correlation_id, 'BACKFILL_V10'
  FROM v10_backfill
 ORDER BY obligation_id, recorded_at, rank, tiebreak;

-- ---------------------------------------------------------------------
-- 5. History invariants (created after the backfill)
-- ---------------------------------------------------------------------
CREATE TRIGGER lending_event_append_only BEFORE UPDATE OR DELETE ON lending_event
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- Gap-free per-loan order; record time never runs backwards within a loan;
-- corrections point at an earlier, correctable event of the same loan.
CREATE FUNCTION wbank_lending_event_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    prev lending_event%ROWTYPE;
    target lending_event%ROWTYPE;
BEGIN
    IF NEW.loan_seq > 1 THEN
        SELECT * INTO prev FROM lending_event WHERE obligation_id = NEW.obligation_id AND loan_seq = NEW.loan_seq - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'lending_event for % skips loan_seq %', NEW.obligation_id, NEW.loan_seq
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.recorded_at < prev.recorded_at THEN
            RAISE EXCEPTION 'lending_event for % recorded at % before its predecessor (%)',
                NEW.obligation_id, NEW.recorded_at, prev.recorded_at
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM lending_event WHERE obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'lending_event for % must continue its sequence', NEW.obligation_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.corrects_event_id IS NOT NULL THEN
        SELECT * INTO target FROM lending_event WHERE id = NEW.corrects_event_id;
        IF target.obligation_id <> NEW.obligation_id
           OR target.event_type NOT IN ('APPLICATION_RECEIVED', 'CREDIT_DECISION', 'DEFAULT_DECLARED') THEN
            RAISE EXCEPTION 'only application and decision information of the same loan can be corrected'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lending_event_order BEFORE INSERT ON lending_event
    FOR EACH ROW EXECUTE FUNCTION wbank_lending_event_order();

-- The current status (a projection) must equal the status the history implies.
CREATE FUNCTION wbank_check_status_matches_history(obl_id UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    actual   TEXT;
    implied  TEXT;
BEGIN
    SELECT status INTO actual FROM obligation WHERE id = obl_id;
    SELECT CASE e.event_type
               WHEN 'APPLICATION_RECEIVED'  THEN 'PROPOSED'
               WHEN 'CREDIT_DECISION'       THEN e.payload ->> 'decision'
               WHEN 'APPLICATION_CANCELLED' THEN 'CANCELLED'
               WHEN 'LOAN_DISBURSED'        THEN 'ACTIVE'
               WHEN 'DEFAULT_DECLARED'      THEN 'DEFAULTED'
               WHEN 'LOAN_SETTLED'          THEN 'SETTLED'
           END INTO implied
      FROM lending_event e
     WHERE e.obligation_id = obl_id
       AND e.event_type IN ('APPLICATION_RECEIVED', 'CREDIT_DECISION', 'APPLICATION_CANCELLED',
                            'LOAN_DISBURSED', 'DEFAULT_DECLARED', 'LOAN_SETTLED')
     ORDER BY e.loan_seq DESC LIMIT 1;
    IF implied IS DISTINCT FROM actual THEN
        RAISE EXCEPTION 'obligation % is % but its history implies %', obl_id, actual, implied
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE FUNCTION wbank_status_history_from_obligation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM wbank_check_status_matches_history(NEW.id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER obligation_status_matches_history AFTER INSERT OR UPDATE ON obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_status_history_from_obligation();

-- Every lending fact must be accompanied by its history event (and decisions by a snapshot).
CREATE FUNCTION wbank_fact_has_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending_event WHERE source_table = TG_TABLE_NAME AND source_id = NEW.id) THEN
        RAISE EXCEPTION '% % was written without its lending_event', TG_TABLE_NAME, NEW.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_TABLE_NAME = 'loan_decision'
       AND NOT EXISTS (SELECT 1 FROM credit_decision_snapshot WHERE decision_id = NEW.id) THEN
        RAISE EXCEPTION 'loan_decision % was written without its decision snapshot', NEW.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER repayment_has_history AFTER INSERT ON obligation_repayment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_fact_has_history();
CREATE CONSTRAINT TRIGGER accrual_has_history AFTER INSERT ON interest_accrual
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_fact_has_history();
CREATE CONSTRAINT TRIGGER decision_has_history AFTER INSERT ON loan_decision
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_fact_has_history();
CREATE CONSTRAINT TRIGGER delinquency_has_history AFTER INSERT ON delinquency_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_fact_has_history();
