-- =====================================================================
-- V9 : Credit and lending
--
-- Extends the phase-2 obligation model into a lending domain:
--
--   * lifecycle     : PROPOSED -> APPROVED -> ACTIVE -> SETTLED, plus DECLINED,
--                     CANCELLED and DEFAULTED; credit decisions are recorded facts
--   * accrual       : interest is EARNED at each period end and booked
--                     Dr loan interest receivable / Cr interest income;
--                     repayments then settle the receivable (not income)
--   * early payoff  : interest of periods not yet ended is WAIVED (recorded)
--   * delinquency   : derived from overdue instalments; changes are recorded
--   * policy        : allocation policy and interest recognition are stored on
--                     the loan terms (versioned), so historical loans keep
--                     the rules they were written under
--
-- Loans written before V9 keep policy V1 / CASH interest recognition.
-- See docs/lending.md.
-- =====================================================================

ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_purpose_valid;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_purpose_valid CHECK (purpose IN (
    'INTERNAL_CASH', 'INTERNAL_SUSPENSE', 'INTERNAL_EQUITY', 'INTERNAL_INTEREST_INCOME',
    'CUSTOMER_DEPOSIT', 'LOAN_RECEIVABLE', 'LOAN_INTEREST_RECEIVABLE'));

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_type_valid;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_type_valid CHECK (entry_type IN (
    'CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'CUSTOMER_TRANSFER', 'REVERSAL', 'ADJUSTMENT',
    'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'PAYMENT_TRANSFER', 'INTEREST_ACCRUAL'));

-- ---------------------------------------------------------------------
-- Versioned servicing rules on the (immutable) terms
-- ---------------------------------------------------------------------
ALTER TABLE loan_terms
    ADD COLUMN allocation_policy   TEXT NOT NULL DEFAULT 'V1_OLDEST_FIRST_INTEREST_THEN_PRINCIPAL',
    ADD COLUMN interest_recognition TEXT NOT NULL DEFAULT 'CASH';
ALTER TABLE loan_terms
    ALTER COLUMN allocation_policy DROP DEFAULT,
    ALTER COLUMN interest_recognition DROP DEFAULT,
    ADD CONSTRAINT loan_terms_policy_valid CHECK (allocation_policy IN (
        'V1_OLDEST_FIRST_INTEREST_THEN_PRINCIPAL', 'V2_DUE_ONLY_INTEREST_THEN_PRINCIPAL_FULL_PAYOFF')),
    ADD CONSTRAINT loan_terms_recognition_valid CHECK (interest_recognition IN ('CASH', 'ACCRUAL_PERIOD_END')),
    ADD CONSTRAINT loan_terms_policy_matches_recognition CHECK (
        (allocation_policy LIKE 'V1_%' AND interest_recognition = 'CASH')
     OR (allocation_policy LIKE 'V2_%' AND interest_recognition = 'ACCRUAL_PERIOD_END'));

-- ---------------------------------------------------------------------
-- Obligation lifecycle
-- ---------------------------------------------------------------------
ALTER TABLE obligation
    ADD COLUMN approved_at   TIMESTAMPTZ,
    ADD COLUMN declined_at   TIMESTAMPTZ,
    ADD COLUMN defaulted_at  TIMESTAMPTZ,
    ADD COLUMN interest_receivable_ledger_account_id UUID,
    ADD CONSTRAINT obligation_interest_receivable_currency
        FOREIGN KEY (interest_receivable_ledger_account_id, currency) REFERENCES ledger_account (id, currency),
    ADD CONSTRAINT obligation_interest_receivable_unique UNIQUE (interest_receivable_ledger_account_id);

-- Pre-V9 loans were activated without a separate approval step.
UPDATE obligation SET approved_at = activated_at WHERE status IN ('ACTIVE', 'SETTLED');
-- The UPDATE queued the deferred obligation-consistency checks; PostgreSQL refuses to
-- ALTER a table with pending trigger events, so run them now (against the V8 rules the
-- legacy rows were written under) before the table is altered below.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE obligation DROP CONSTRAINT obligation_status_valid;
ALTER TABLE obligation DROP CONSTRAINT obligation_lifecycle_fields;
ALTER TABLE obligation
    ADD CONSTRAINT obligation_status_valid CHECK (status IN (
        'PROPOSED', 'APPROVED', 'DECLINED', 'CANCELLED', 'ACTIVE', 'DEFAULTED', 'SETTLED')),
    ADD CONSTRAINT obligation_lifecycle_fields CHECK (
        (status = 'PROPOSED'  AND approved_at IS NULL AND declined_at IS NULL AND cancelled_at IS NULL
                              AND activated_at IS NULL AND disbursement_entry_id IS NULL
                              AND start_date IS NULL AND maturity_date IS NULL AND settled_at IS NULL
                              AND defaulted_at IS NULL)
     OR (status = 'APPROVED'  AND approved_at IS NOT NULL AND declined_at IS NULL AND cancelled_at IS NULL
                              AND activated_at IS NULL AND disbursement_entry_id IS NULL AND start_date IS NULL
                              AND settled_at IS NULL AND defaulted_at IS NULL)
     OR (status = 'DECLINED'  AND declined_at IS NOT NULL AND approved_at IS NULL AND activated_at IS NULL
                              AND disbursement_entry_id IS NULL)
     OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND declined_at IS NULL AND activated_at IS NULL
                              AND disbursement_entry_id IS NULL)
     OR (status IN ('ACTIVE', 'DEFAULTED', 'SETTLED')
                              AND approved_at IS NOT NULL AND activated_at IS NOT NULL
                              AND disbursement_entry_id IS NOT NULL AND start_date IS NOT NULL
                              AND maturity_date IS NOT NULL AND cancelled_at IS NULL AND declined_at IS NULL
                              AND (status <> 'ACTIVE'    OR (settled_at IS NULL AND defaulted_at IS NULL))
                              AND (status <> 'DEFAULTED' OR (settled_at IS NULL AND defaulted_at IS NOT NULL))
                              AND (status <> 'SETTLED'   OR settled_at IS NOT NULL))
    );

CREATE OR REPLACE FUNCTION wbank_obligation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    pos_family TEXT;
    set_family TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'obligation rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' THEN
            RAISE EXCEPTION 'an obligation starts PROPOSED, not %', NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        SELECT product_family INTO pos_family FROM account WHERE id = NEW.position_account_id;
        SELECT product_family INTO set_family FROM account WHERE id = NEW.settlement_account_id;
        IF NEW.obligation_type = 'LOAN' AND (pos_family <> 'LOAN' OR set_family <> 'DEPOSIT') THEN
            RAISE EXCEPTION 'a LOAN needs a LOAN position account and a DEPOSIT settlement account'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id                    IS DISTINCT FROM OLD.id
    OR NEW.obligation_number     IS DISTINCT FROM OLD.obligation_number
    OR NEW.obligation_type       IS DISTINCT FROM OLD.obligation_type
    OR NEW.creditor_party_id     IS DISTINCT FROM OLD.creditor_party_id
    OR NEW.debtor_party_id       IS DISTINCT FROM OLD.debtor_party_id
    OR NEW.currency              IS DISTINCT FROM OLD.currency
    OR NEW.principal_minor       IS DISTINCT FROM OLD.principal_minor
    OR NEW.position_account_id   IS DISTINCT FROM OLD.position_account_id
    OR NEW.settlement_account_id IS DISTINCT FROM OLD.settlement_account_id
    OR NEW.proposed_at           IS DISTINCT FROM OLD.proposed_at
    OR (OLD.approved_at  IS NOT NULL AND NEW.approved_at  IS DISTINCT FROM OLD.approved_at)
    OR (OLD.activated_at IS NOT NULL AND (NEW.start_date IS DISTINCT FROM OLD.start_date
                                       OR NEW.maturity_date IS DISTINCT FROM OLD.maturity_date
                                       OR NEW.disbursement_entry_id IS DISTINCT FROM OLD.disbursement_entry_id
                                       OR NEW.activated_at IS DISTINCT FROM OLD.activated_at))
    OR (OLD.interest_receivable_ledger_account_id IS NOT NULL
        AND NEW.interest_receivable_ledger_account_id IS DISTINCT FROM OLD.interest_receivable_ledger_account_id)
    OR (OLD.defaulted_at IS NOT NULL AND NEW.defaulted_at IS DISTINCT FROM OLD.defaulted_at) THEN
        RAISE EXCEPTION 'obligation % terms and recorded lifecycle facts are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PROPOSED'  AND NEW.status IN ('APPROVED', 'DECLINED', 'CANCELLED'))
        OR (OLD.status = 'APPROVED'  AND NEW.status IN ('ACTIVE', 'CANCELLED'))
        OR (OLD.status = 'ACTIVE'    AND NEW.status IN ('SETTLED', 'DEFAULTED'))
        OR (OLD.status = 'DEFAULTED' AND NEW.status = 'SETTLED')) THEN
        RAISE EXCEPTION 'obligation % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status IN ('SETTLED', 'CANCELLED', 'DECLINED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'obligation % is terminal (%)', OLD.id, OLD.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- An account used by any non-terminal obligation may not close.
CREATE OR REPLACE FUNCTION wbank_account_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    cust_status TEXT;
    prod        product%ROWTYPE;
    bal         BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'account rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'an account is opened PENDING, not %', NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        SELECT status INTO cust_status FROM customer WHERE id = NEW.customer_id FOR SHARE;
        IF cust_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'customer % is % and cannot open accounts', NEW.customer_id, cust_status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        SELECT * INTO prod FROM product WHERE code = NEW.product_code FOR SHARE;
        IF prod.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'product % is % and cannot be sold', prod.code, prod.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.overdraft_limit_minor > 0 AND NOT prod.allows_overdraft THEN
            RAISE EXCEPTION 'product % does not permit an overdraft', prod.code
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id              IS DISTINCT FROM OLD.id
    OR NEW.account_number  IS DISTINCT FROM OLD.account_number
    OR NEW.customer_id     IS DISTINCT FROM OLD.customer_id
    OR NEW.owner_party_id  IS DISTINCT FROM OLD.owner_party_id
    OR NEW.product_code    IS DISTINCT FROM OLD.product_code
    OR NEW.product_family  IS DISTINCT FROM OLD.product_family
    OR NEW.currency        IS DISTINCT FROM OLD.currency
    OR NEW.opened_at       IS DISTINCT FROM OLD.opened_at
    OR (OLD.ledger_account_id IS NOT NULL AND NEW.ledger_account_id IS DISTINCT FROM OLD.ledger_account_id) THEN
        RAISE EXCEPTION 'account % identity, owner, product, currency and ledger link are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'account % is closed and cannot change', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status <> OLD.status THEN
        IF NOT ((OLD.status = 'PENDING' AND NEW.status IN ('ACTIVE', 'CLOSED'))
             OR (OLD.status = 'ACTIVE'  AND NEW.status IN ('FROZEN', 'CLOSED'))
             OR (OLD.status = 'FROZEN'  AND NEW.status = 'ACTIVE')) THEN
            RAISE EXCEPTION 'account % cannot move from % to %', OLD.id, OLD.status, NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF OLD.status = 'PENDING' AND NEW.status = 'ACTIVE' THEN
            SELECT status INTO cust_status FROM customer WHERE id = NEW.customer_id FOR SHARE;
            IF cust_status <> 'ACTIVE' THEN
                RAISE EXCEPTION 'customer % is % and cannot activate accounts', NEW.customer_id, cust_status
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
        IF NEW.status = 'CLOSED' THEN
            IF NEW.ledger_account_id IS NOT NULL THEN
                SELECT balance_minor INTO bal FROM ledger_account_balance
                 WHERE ledger_account_id = NEW.ledger_account_id;
                IF bal <> 0 THEN
                    RAISE EXCEPTION 'account % has a non-zero ledger balance (%) and cannot close', OLD.id, bal
                        USING ERRCODE = 'integrity_constraint_violation';
                END IF;
            END IF;
            IF EXISTS (SELECT 1 FROM obligation
                        WHERE (position_account_id = OLD.id OR settlement_account_id = OLD.id)
                          AND status IN ('PROPOSED', 'APPROVED', 'ACTIVE', 'DEFAULTED')) THEN
                RAISE EXCEPTION 'account % is referenced by an open obligation and cannot close', OLD.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------
-- Credit decisions (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE loan_decision (
    id             UUID        PRIMARY KEY,
    obligation_id  UUID        NOT NULL REFERENCES obligation (id),
    decision       TEXT        NOT NULL,
    decided_by     TEXT        NOT NULL,
    decider_type   TEXT        NOT NULL,
    correlation_id UUID        NOT NULL,
    decided_at     TIMESTAMPTZ NOT NULL,
    rationale      TEXT        NOT NULL,
    evidence       JSONB       NOT NULL,

    CONSTRAINT loan_decision_valid CHECK (decision IN ('APPROVED', 'DECLINED', 'DEFAULT_DECLARED')),
    CONSTRAINT loan_decision_rationale_present CHECK (length(btrim(rationale)) > 0),
    CONSTRAINT loan_decision_decider_type_valid CHECK (decider_type IN ('HUMAN', 'SYSTEM', 'SERVICE', 'AGENT')),
    CONSTRAINT loan_decision_evidence_object CHECK (jsonb_typeof(evidence) = 'object')
);
CREATE UNIQUE INDEX loan_decision_once ON loan_decision (obligation_id, decision);
CREATE TRIGGER loan_decision_append_only BEFORE UPDATE OR DELETE ON loan_decision
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- ---------------------------------------------------------------------
-- Interest accrual (append-only): one per instalment, at or after its due date
-- ---------------------------------------------------------------------
CREATE TABLE interest_accrual (
    id                UUID        PRIMARY KEY,
    obligation_id     UUID        NOT NULL REFERENCES obligation (id),
    installment_id    UUID        NOT NULL REFERENCES obligation_installment (id),
    amount_minor      BIGINT      NOT NULL,
    accrual_date      DATE        NOT NULL,
    journal_entry_id  UUID        NOT NULL REFERENCES journal_entry (id),
    recorded_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT accrual_amount_positive  CHECK (amount_minor > 0),
    CONSTRAINT accrual_installment_once UNIQUE (installment_id),
    CONSTRAINT accrual_entry_unique     UNIQUE (journal_entry_id)
);
CREATE INDEX accrual_obligation_idx ON interest_accrual (obligation_id);
CREATE TRIGGER interest_accrual_append_only BEFORE UPDATE OR DELETE ON interest_accrual
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- ---------------------------------------------------------------------
-- Interest waived on early settlement (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE installment_waiver (
    installment_id         UUID        PRIMARY KEY REFERENCES obligation_installment (id),
    obligation_id          UUID        NOT NULL REFERENCES obligation (id),
    repayment_id           UUID        NOT NULL REFERENCES obligation_repayment (id),
    interest_waived_minor  BIGINT      NOT NULL,
    reason                 TEXT        NOT NULL,
    waived_at              TIMESTAMPTZ NOT NULL,

    CONSTRAINT waiver_amount_positive CHECK (interest_waived_minor > 0),
    CONSTRAINT waiver_reason_valid    CHECK (reason = 'EARLY_SETTLEMENT')
);
CREATE INDEX waiver_obligation_idx ON installment_waiver (obligation_id);
CREATE TRIGGER installment_waiver_append_only BEFORE UPDATE OR DELETE ON installment_waiver
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- ---------------------------------------------------------------------
-- Delinquency history (append-only): a row whenever the bucket changes
-- ---------------------------------------------------------------------
CREATE TABLE delinquency_event (
    id                     UUID        PRIMARY KEY,
    obligation_id          UUID        NOT NULL REFERENCES obligation (id),
    as_of                  DATE        NOT NULL,
    from_bucket            TEXT        NOT NULL,
    to_bucket              TEXT        NOT NULL,
    days_past_due          INTEGER     NOT NULL,
    overdue_principal_minor BIGINT     NOT NULL,
    overdue_interest_minor BIGINT      NOT NULL,
    oldest_unpaid_due_date DATE,
    recorded_at            TIMESTAMPTZ NOT NULL,

    CONSTRAINT delinquency_buckets_valid CHECK (
        from_bucket IN ('CURRENT', 'DPD_1_29', 'DPD_30_59', 'DPD_60_89', 'DPD_90_PLUS')
    AND to_bucket   IN ('CURRENT', 'DPD_1_29', 'DPD_30_59', 'DPD_60_89', 'DPD_90_PLUS')
    AND from_bucket <> to_bucket),
    CONSTRAINT delinquency_amounts_valid CHECK (
        days_past_due >= 0 AND overdue_principal_minor >= 0 AND overdue_interest_minor >= 0)
);
CREATE INDEX delinquency_obligation_idx ON delinquency_event (obligation_id, as_of);
CREATE TRIGGER delinquency_event_append_only BEFORE UPDATE OR DELETE ON delinquency_event
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- ---------------------------------------------------------------------
-- The contract <-> ledger invariant, extended to accrued interest.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION wbank_check_obligation(obl_id UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    o                obligation%ROWTYPE;
    terms_count      INTEGER;
    sched_count      INTEGER;
    sched_principal  BIGINT;
    sched_interest   BIGINT;
    rep_principal    BIGINT;
    rep_interest     BIGINT;
    alloc_principal  BIGINT;
    alloc_interest   BIGINT;
    accrued          BIGINT;
    waived           BIGINT;
    ledger_balance   BIGINT;
    ledger_id        UUID;
    receivable       BIGINT;
    entry_type       TEXT;
BEGIN
    SELECT * INTO o FROM obligation WHERE id = obl_id;

    IF o.obligation_type = 'LOAN' THEN
        SELECT count(*) INTO terms_count FROM loan_terms WHERE obligation_id = o.id;
        IF terms_count <> 1 THEN
            RAISE EXCEPTION 'loan % has no loan_terms', o.id USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    SELECT count(*), coalesce(sum(principal_due_minor), 0), coalesce(sum(interest_due_minor), 0)
      INTO sched_count, sched_principal, sched_interest
      FROM obligation_installment WHERE obligation_id = o.id;
    SELECT coalesce(sum(principal_minor), 0), coalesce(sum(interest_minor), 0)
      INTO rep_principal, rep_interest
      FROM obligation_repayment WHERE obligation_id = o.id;
    SELECT coalesce(sum(ra.principal_minor), 0), coalesce(sum(ra.interest_minor), 0)
      INTO alloc_principal, alloc_interest
      FROM repayment_allocation ra JOIN obligation_repayment r ON r.id = ra.repayment_id
     WHERE r.obligation_id = o.id;
    SELECT coalesce(sum(amount_minor), 0) INTO accrued FROM interest_accrual WHERE obligation_id = o.id;
    SELECT coalesce(sum(interest_waived_minor), 0) INTO waived FROM installment_waiver WHERE obligation_id = o.id;

    IF o.status IN ('PROPOSED', 'APPROVED', 'DECLINED', 'CANCELLED') THEN
        IF sched_count > 0 OR rep_principal + rep_interest > 0 OR accrued > 0 THEN
            RAISE EXCEPTION 'obligation % is % but has a schedule, accruals or repayments', o.id, o.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN;
    END IF;

    IF sched_count = 0 OR sched_principal <> o.principal_minor THEN
        RAISE EXCEPTION 'obligation % schedule principal % does not equal contract principal %',
            o.id, sched_principal, o.principal_minor USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF rep_principal <> alloc_principal OR rep_interest <> alloc_interest THEN
        RAISE EXCEPTION 'obligation % repayments (%/%) disagree with their allocations (%/%)',
            o.id, rep_principal, rep_interest, alloc_principal, alloc_interest
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM repayment_allocation ra
                 JOIN obligation_repayment r ON r.id = ra.repayment_id
                 JOIN obligation_installment i ON i.id = ra.installment_id
                WHERE r.obligation_id = o.id AND i.obligation_id <> o.id)
    OR EXISTS (SELECT 1 FROM interest_accrual a JOIN obligation_installment i ON i.id = a.installment_id
                WHERE a.obligation_id = o.id AND (i.obligation_id <> o.id OR a.amount_minor <> i.interest_due_minor
                                                  OR a.accrual_date < i.due_date))
    OR EXISTS (SELECT 1 FROM installment_waiver w JOIN obligation_installment i ON i.id = w.installment_id
                WHERE w.obligation_id = o.id AND i.obligation_id <> o.id) THEN
        RAISE EXCEPTION 'obligation % has allocations, accruals or waivers inconsistent with its schedule', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    -- No instalment is over-satisfied: paid (+ waived) never exceeds what was due.
    IF EXISTS (SELECT 1 FROM obligation_installment i
                 LEFT JOIN (SELECT ra.installment_id, sum(ra.principal_minor) p, sum(ra.interest_minor) q
                              FROM repayment_allocation ra GROUP BY ra.installment_id) paid
                        ON paid.installment_id = i.id
                 LEFT JOIN installment_waiver w ON w.installment_id = i.id
                WHERE i.obligation_id = o.id
                  AND (coalesce(paid.p, 0) > i.principal_due_minor
                    OR coalesce(paid.q, 0) + coalesce(w.interest_waived_minor, 0) > i.interest_due_minor)) THEN
        RAISE EXCEPTION 'obligation % has an over-satisfied instalment', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Principal: the position's ledger balance is principal - principal repaid.
    SELECT ledger_account_id INTO ledger_id FROM account WHERE id = o.position_account_id;
    SELECT balance_minor INTO ledger_balance FROM ledger_account_balance WHERE ledger_account_id = ledger_id;
    IF ledger_balance IS DISTINCT FROM o.principal_minor - rep_principal THEN
        RAISE EXCEPTION 'obligation % outstanding principal % disagrees with its ledger position %',
            o.id, o.principal_minor - rep_principal, ledger_balance
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Interest (accrual loans): the receivable's ledger balance is accrued - interest repaid.
    IF o.interest_receivable_ledger_account_id IS NOT NULL THEN
        SELECT balance_minor INTO receivable FROM ledger_account_balance
         WHERE ledger_account_id = o.interest_receivable_ledger_account_id;
        IF receivable IS DISTINCT FROM accrued - rep_interest THEN
            RAISE EXCEPTION 'obligation % accrued-but-unpaid interest % disagrees with its ledger receivable %',
                o.id, accrued - rep_interest, receivable
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF accrued > 0 OR waived > 0 THEN
        RAISE EXCEPTION 'obligation % is cash-basis but has accruals or waivers', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT je.entry_type INTO entry_type FROM journal_entry je WHERE je.id = o.disbursement_entry_id;
    IF entry_type IS DISTINCT FROM 'LOAN_DISBURSEMENT' THEN
        RAISE EXCEPTION 'obligation % disbursement entry is a %', o.id, entry_type
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF o.status = 'SETTLED' AND (rep_principal <> sched_principal OR rep_interest + waived <> sched_interest) THEN
        RAISE EXCEPTION 'obligation % is SETTLED but not fully satisfied', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF o.status IN ('ACTIVE', 'DEFAULTED') AND rep_principal = sched_principal
       AND rep_interest + waived = sched_interest THEN
        RAISE EXCEPTION 'obligation % is fully satisfied but still %', o.id, o.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE CONSTRAINT TRIGGER accrual_consistent AFTER INSERT ON interest_accrual
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_obligation_consistency_from_child();
CREATE CONSTRAINT TRIGGER waiver_consistent AFTER INSERT ON installment_waiver
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_obligation_consistency_from_child();
