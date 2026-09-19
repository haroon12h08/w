-- =====================================================================
-- V8 : Payment lifecycle and account-level funds control
--
-- Five concepts, five kinds of record (see docs/payments.md):
--
--   payment_instruction : what was ASKED (immutable)
--   payment             : where processing STANDS (lifecycle state)
--   payment_event       : what HAPPENED, step by step (append-only provenance)
--   funds_reservation   : money set aside by an authorisation (a hold)
--   journal_entry/posting (existing) : the financial transaction and its ledger legs
--   payment_settlement  : the fact that the payment settled, and through which entry
--
-- Available funds are enforced here, not only in Java:
--   ledger balance - active reservations >= authorised floor
-- for every ledger account that has a floor, checked at COMMIT whenever a
-- balance or a reservation changes. A hold is therefore binding against every
-- debit path (withdrawals, loan repayments, other payments), not just payments.
-- =====================================================================

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_type_valid;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_type_valid CHECK (entry_type IN (
    'CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'CUSTOMER_TRANSFER', 'REVERSAL', 'ADJUSTMENT',
    'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'PAYMENT_TRANSFER'));

-- ---------------------------------------------------------------------
-- Funds reservations (holds)
-- ---------------------------------------------------------------------
CREATE TABLE funds_reservation (
    id                 UUID        PRIMARY KEY,
    account_id         UUID        NOT NULL,
    ledger_account_id  UUID        NOT NULL,
    currency           CHAR(3)     NOT NULL,
    amount_minor       BIGINT      NOT NULL,
    status             TEXT        NOT NULL,
    purpose            TEXT        NOT NULL,
    reference_id       UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    resolved_at        TIMESTAMPTZ,

    CONSTRAINT reservation_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT reservation_status_valid    CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED')),
    CONSTRAINT reservation_resolution      CHECK ((status = 'ACTIVE') = (resolved_at IS NULL)),
    CONSTRAINT reservation_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT reservation_account_currency
        FOREIGN KEY (account_id, currency) REFERENCES account (id, currency),
    CONSTRAINT reservation_ledger_currency
        FOREIGN KEY (ledger_account_id, currency) REFERENCES ledger_account (id, currency)
);

CREATE INDEX reservation_active_by_ledger_account
    ON funds_reservation (ledger_account_id) WHERE status = 'ACTIVE';
CREATE INDEX reservation_reference_idx ON funds_reservation (reference_id);

COMMENT ON TABLE funds_reservation IS
    'Funds set aside by an authorisation. Not an accounting entry: the ledger is untouched until settlement.';

CREATE FUNCTION wbank_reservation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'funds_reservation rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.account_id IS DISTINCT FROM OLD.account_id
    OR NEW.ledger_account_id IS DISTINCT FROM OLD.ledger_account_id OR NEW.currency IS DISTINCT FROM OLD.currency
    OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor OR NEW.reference_id IS DISTINCT FROM OLD.reference_id
    OR NEW.purpose IS DISTINCT FROM OLD.purpose OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'funds_reservation % terms are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'ACTIVE' AND NEW.status IN ('CONSUMED', 'RELEASED')) THEN
        RAISE EXCEPTION 'funds_reservation % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER reservation_guard BEFORE UPDATE OR DELETE ON funds_reservation
    FOR EACH ROW EXECUTE FUNCTION wbank_reservation_guard();

-- Available funds may never be negative.
CREATE FUNCTION wbank_check_available_funds(ledger_id UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    bal      BIGINT;
    floor_minor BIGINT;
    reserved BIGINT;
BEGIN
    SELECT balance_minor, min_balance_minor INTO bal, floor_minor
      FROM ledger_account_balance WHERE ledger_account_id = ledger_id;
    IF floor_minor IS NULL THEN
        RETURN; -- unconstrained internal account
    END IF;
    SELECT coalesce(sum(amount_minor), 0) INTO reserved
      FROM funds_reservation WHERE ledger_account_id = ledger_id AND status = 'ACTIVE';
    IF bal - reserved < floor_minor THEN
        RAISE EXCEPTION 'available funds of ledger account % would be negative: balance % - reserved % < floor %',
            ledger_id, bal, reserved, floor_minor
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE FUNCTION wbank_available_from_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM wbank_check_available_funds(NEW.ledger_account_id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER balance_respects_reservations AFTER UPDATE ON ledger_account_balance
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_available_from_balance();
CREATE CONSTRAINT TRIGGER reservation_respects_available AFTER INSERT OR UPDATE ON funds_reservation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_available_from_balance();

-- ---------------------------------------------------------------------
-- Payment instruction (immutable)
-- ---------------------------------------------------------------------
CREATE TABLE payment_instruction (
    id                   UUID        PRIMARY KEY,
    idempotency_key      TEXT        NOT NULL,
    request_fingerprint  TEXT        NOT NULL,
    debtor_account_id    UUID        NOT NULL REFERENCES account (id),
    creditor_account_id  UUID        NOT NULL REFERENCES account (id),
    currency             CHAR(3)     NOT NULL REFERENCES currency (code),
    amount_minor         BIGINT      NOT NULL,
    remittance_info      TEXT,
    received_at          TIMESTAMPTZ NOT NULL,
    initiated_by         TEXT        NOT NULL,
    initiator_type       TEXT        NOT NULL,
    correlation_id       UUID        NOT NULL,
    channel              TEXT        NOT NULL,

    CONSTRAINT instruction_key_unique        UNIQUE (idempotency_key),
    CONSTRAINT instruction_key_well_formed   CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 200),
    CONSTRAINT instruction_fingerprint_hex   CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT instruction_amount_positive   CHECK (amount_minor > 0),
    CONSTRAINT instruction_distinct_accounts CHECK (debtor_account_id <> creditor_account_id),
    CONSTRAINT instruction_initiator_type_valid
        CHECK (initiator_type IN ('HUMAN', 'SYSTEM', 'SERVICE', 'AGENT'))
);

CREATE INDEX instruction_debtor_idx   ON payment_instruction (debtor_account_id, received_at DESC);
CREATE INDEX instruction_creditor_idx ON payment_instruction (creditor_account_id, received_at DESC);

CREATE TRIGGER payment_instruction_immutable BEFORE UPDATE OR DELETE ON payment_instruction
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- ---------------------------------------------------------------------
-- Payment (processing state)
-- ---------------------------------------------------------------------
CREATE TABLE payment (
    id                 UUID        PRIMARY KEY,
    instruction_id     UUID        NOT NULL REFERENCES payment_instruction (id),
    status             TEXT        NOT NULL,
    reason_code        TEXT,
    reason_detail      TEXT,
    reservation_id     UUID        REFERENCES funds_reservation (id),
    authorized_at      TIMESTAMPTZ,
    expires_at         TIMESTAMPTZ,
    settled_at         TIMESTAMPTZ,
    reversed_at        TIMESTAMPTZ,
    reversal_entry_id  UUID        REFERENCES journal_entry (id),
    closed_at          TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT payment_instruction_unique  UNIQUE (instruction_id),
    CONSTRAINT payment_reservation_unique  UNIQUE (reservation_id),
    CONSTRAINT payment_reversal_unique     UNIQUE (reversal_entry_id),
    CONSTRAINT payment_status_valid CHECK (status IN (
        'AUTHORIZED', 'SETTLED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'FAILED', 'REVERSED')),
    CONSTRAINT payment_lifecycle_fields CHECK (
        (status = 'AUTHORIZED' AND reservation_id IS NOT NULL AND authorized_at IS NOT NULL
                               AND expires_at IS NOT NULL AND settled_at IS NULL AND closed_at IS NULL)
     OR (status = 'REJECTED'   AND reservation_id IS NULL AND authorized_at IS NULL AND reason_code IS NOT NULL
                               AND settled_at IS NULL AND closed_at IS NOT NULL)
     OR (status IN ('CANCELLED', 'EXPIRED', 'FAILED')
                               AND reason_code IS NOT NULL AND settled_at IS NULL AND closed_at IS NOT NULL)
     OR (status = 'SETTLED'    AND reservation_id IS NOT NULL AND settled_at IS NOT NULL
                               AND reversed_at IS NULL AND closed_at IS NULL)
     OR (status = 'REVERSED'   AND settled_at IS NOT NULL AND reversed_at IS NOT NULL
                               AND reversal_entry_id IS NOT NULL AND reason_code IS NOT NULL AND closed_at IS NOT NULL)
    )
);

CREATE INDEX payment_status_idx          ON payment (status);
CREATE INDEX payment_authorized_expiry_idx ON payment (expires_at) WHERE status = 'AUTHORIZED';

CREATE FUNCTION wbank_payment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('AUTHORIZED', 'REJECTED') THEN
            RAISE EXCEPTION 'a payment is born AUTHORIZED or REJECTED, not %', NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.instruction_id IS DISTINCT FROM OLD.instruction_id
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR (OLD.reservation_id IS NOT NULL AND NEW.reservation_id IS DISTINCT FROM OLD.reservation_id)
    OR (OLD.authorized_at IS NOT NULL AND NEW.authorized_at IS DISTINCT FROM OLD.authorized_at) THEN
        RAISE EXCEPTION 'payment % identity and authorisation are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'AUTHORIZED' AND NEW.status IN ('SETTLED', 'CANCELLED', 'EXPIRED', 'FAILED'))
        OR (OLD.status = 'SETTLED'    AND NEW.status = 'REVERSED')) THEN
        RAISE EXCEPTION 'payment % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status = OLD.status AND OLD.status IN ('REJECTED', 'CANCELLED', 'EXPIRED', 'FAILED', 'REVERSED') THEN
        RAISE EXCEPTION 'payment % is terminal (%)', OLD.id, OLD.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_guard BEFORE INSERT OR UPDATE OR DELETE ON payment
    FOR EACH ROW EXECUTE FUNCTION wbank_payment_guard();

-- ---------------------------------------------------------------------
-- Payment events (provenance) and settlement
-- ---------------------------------------------------------------------
CREATE TABLE payment_event (
    id              UUID        PRIMARY KEY,
    payment_id      UUID        NOT NULL REFERENCES payment (id),
    sequence_no     INTEGER     NOT NULL,
    event_type      TEXT        NOT NULL,
    from_status     TEXT,
    to_status       TEXT,       -- NULL for steps that do not change state (e.g. a passed check)
    occurred_at     TIMESTAMPTZ NOT NULL,
    actor           TEXT        NOT NULL,
    actor_type      TEXT        NOT NULL,
    correlation_id  UUID        NOT NULL,
    details         JSONB       NOT NULL,

    CONSTRAINT payment_event_sequence_unique UNIQUE (payment_id, sequence_no),
    CONSTRAINT payment_event_sequence_positive CHECK (sequence_no >= 1),
    CONSTRAINT payment_event_details_object CHECK (jsonb_typeof(details) = 'object')
);

CREATE TRIGGER payment_event_append_only BEFORE UPDATE OR DELETE ON payment_event
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

CREATE TABLE payment_settlement (
    id                 UUID        PRIMARY KEY,
    payment_id         UUID        NOT NULL REFERENCES payment (id),
    journal_entry_id   UUID        NOT NULL REFERENCES journal_entry (id),
    method             TEXT        NOT NULL,
    settled_at         TIMESTAMPTZ NOT NULL,

    -- Exactly once: a payment settles at most once, through one journal entry.
    CONSTRAINT settlement_payment_unique UNIQUE (payment_id),
    CONSTRAINT settlement_entry_unique   UNIQUE (journal_entry_id),
    CONSTRAINT settlement_method_valid   CHECK (method = 'INTERNAL_BOOK_TRANSFER')
);

CREATE TRIGGER payment_settlement_append_only BEFORE UPDATE OR DELETE ON payment_settlement
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- The payment <-> reservation <-> ledger invariant, checked at COMMIT.
CREATE FUNCTION wbank_check_payment(pay_id UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    p            payment%ROWTYPE;
    i            payment_instruction%ROWTYPE;
    r            funds_reservation%ROWTYPE;
    s            payment_settlement%ROWTYPE;
    debtor_ledger   UUID;
    creditor_ledger UUID;
    entry_type   TEXT;
    legs_ok      BIGINT;
    legs_total   BIGINT;
BEGIN
    SELECT * INTO p FROM payment WHERE id = pay_id;
    SELECT * INTO i FROM payment_instruction WHERE id = p.instruction_id;
    SELECT ledger_account_id INTO debtor_ledger   FROM account WHERE id = i.debtor_account_id;
    SELECT ledger_account_id INTO creditor_ledger FROM account WHERE id = i.creditor_account_id;
    IF p.reservation_id IS NOT NULL THEN
        SELECT * INTO r FROM funds_reservation WHERE id = p.reservation_id;
        IF r.amount_minor <> i.amount_minor OR r.currency <> i.currency OR r.account_id <> i.debtor_account_id
           OR r.reference_id <> p.id THEN
            RAISE EXCEPTION 'payment % reservation does not match its instruction', p.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    SELECT * INTO s FROM payment_settlement WHERE payment_id = p.id;

    IF p.status = 'AUTHORIZED' AND r.status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'AUTHORIZED payment % has no active reservation', p.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF p.status IN ('SETTLED', 'REVERSED') THEN
        IF s.id IS NULL THEN
            RAISE EXCEPTION 'payment % is % without a settlement record', p.id, p.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF r.status IS DISTINCT FROM 'CONSUMED' THEN
            RAISE EXCEPTION 'settled payment % did not consume its reservation', p.id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        SELECT je.entry_type INTO entry_type FROM journal_entry je WHERE je.id = s.journal_entry_id;
        SELECT count(*) FILTER (WHERE (ledger_account_id = debtor_ledger   AND direction = 'DEBIT'
                                       AND amount_minor = i.amount_minor)
                                   OR (ledger_account_id = creditor_ledger AND direction = 'CREDIT'
                                       AND amount_minor = i.amount_minor)),
               count(*)
          INTO legs_ok, legs_total
          FROM posting WHERE journal_entry_id = s.journal_entry_id;
        IF entry_type IS DISTINCT FROM 'PAYMENT_TRANSFER' OR legs_total <> 2 OR legs_ok <> 2 THEN
            RAISE EXCEPTION 'payment % settlement entry does not debit the debtor and credit the creditor by %',
                p.id, i.amount_minor USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        IF s.id IS NOT NULL THEN
            RAISE EXCEPTION 'payment % is % but has a settlement record', p.id, p.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    IF p.status IN ('REJECTED', 'CANCELLED', 'EXPIRED', 'FAILED') AND r.id IS NOT NULL AND r.status IS DISTINCT FROM 'RELEASED' THEN
        RAISE EXCEPTION 'payment % is % but its reservation is %', p.id, p.status, r.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF p.status = 'REVERSED' AND NOT EXISTS (SELECT 1 FROM journal_entry
                                              WHERE id = p.reversal_entry_id AND reverses_entry_id = s.journal_entry_id) THEN
        RAISE EXCEPTION 'payment % reversal entry does not reverse its settlement', p.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE FUNCTION wbank_payment_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'payment' THEN
        PERFORM wbank_check_payment(NEW.id);
    ELSE
        PERFORM wbank_check_payment(NEW.payment_id);
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER payment_consistent AFTER INSERT OR UPDATE ON payment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_payment_consistency();
CREATE CONSTRAINT TRIGGER settlement_consistent AFTER INSERT ON payment_settlement
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_payment_consistency();
