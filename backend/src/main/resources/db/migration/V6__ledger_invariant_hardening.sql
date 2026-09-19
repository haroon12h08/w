-- =====================================================================
-- V6 : Ledger invariant hardening
--
-- V2 made postings append-only and made each journal entry balance at
-- commit. It still left gaps that allowed financial state to be
-- stated rather than derived:
--
--   * ledger_account_balance could be UPDATEd to any number;
--   * posting.balance_after_minor was trusted, not checked;
--   * a journal_entry with ZERO postings could commit (the balance
--     check only fired when a posting was inserted);
--   * an idempotency key replayed with a different payload was
--     indistinguishable from a genuine retry;
--   * ledger_account currency / type could be rewritten under
--     existing postings.
--
-- After this migration, the balance of an account is a mathematical
-- consequence of its postings that PostgreSQL verifies independently
-- of the application:
--
--   posting chain  : for each account, account_sequence runs 1,2,3,...
--                    and balance_after(n) = balance_after(n-1) + effect(n)
--                    with balance_after(0) = 0          (checked per row)
--   projection     : ledger_account_balance equals the tip of that chain
--                    (checked at COMMIT)
--
-- By induction the projection therefore equals the signed sum of the
-- account's postings, and it cannot be changed by any statement that
-- does not also append the postings that justify the change.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Idempotency: bind a key to the exact request it was first used for.
-- ---------------------------------------------------------------------
ALTER TABLE journal_entry ADD COLUMN request_fingerprint TEXT;

ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_idempotency_key_well_formed CHECK (
        idempotency_key IS NULL
     OR (length(btrim(idempotency_key)) BETWEEN 1 AND 200 AND idempotency_key = btrim(idempotency_key))
    ),
    -- Note the explicit IS NOT NULL: a CHECK that evaluates to NULL passes, so
    -- `fingerprint ~ '...'` alone would silently admit a key with no fingerprint.
    ADD CONSTRAINT journal_entry_fingerprint_with_key CHECK (
        (idempotency_key IS NULL AND request_fingerprint IS NULL)
     OR (idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL
         AND request_fingerprint ~ '^[0-9a-f]{64}$')
    ),
    ADD CONSTRAINT journal_entry_description_not_blank CHECK (length(btrim(description)) > 0);

-- ---------------------------------------------------------------------
-- 2. Generic manually-originated journal (used by the ledger API).
-- ---------------------------------------------------------------------
ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_type_valid;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_type_valid CHECK (entry_type IN (
    'CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'CUSTOMER_TRANSFER', 'REVERSAL', 'ADJUSTMENT'));

-- A reversal must be denominated in the currency of what it reverses.
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_reversal_same_currency
        FOREIGN KEY (reverses_entry_id, currency) REFERENCES journal_entry (id, currency);

-- ---------------------------------------------------------------------
-- 3. journal_entry guard: also freeze the new column and the remaining
--    descriptive fields, and forbid un-reversing.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION wbank_journal_entry_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal_entry is append-only; DELETE is not permitted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.id                  IS DISTINCT FROM OLD.id
    OR NEW.entry_number        IS DISTINCT FROM OLD.entry_number
    OR NEW.currency            IS DISTINCT FROM OLD.currency
    OR NEW.entry_type          IS DISTINCT FROM OLD.entry_type
    OR NEW.description         IS DISTINCT FROM OLD.description
    OR NEW.total_amount_minor  IS DISTINCT FROM OLD.total_amount_minor
    OR NEW.value_date          IS DISTINCT FROM OLD.value_date
    OR NEW.booked_at           IS DISTINCT FROM OLD.booked_at
    OR NEW.correlation_id      IS DISTINCT FROM OLD.correlation_id
    OR NEW.initiated_by        IS DISTINCT FROM OLD.initiated_by
    OR NEW.initiator_type      IS DISTINCT FROM OLD.initiator_type
    OR NEW.source_operation    IS DISTINCT FROM OLD.source_operation
    OR NEW.idempotency_key     IS DISTINCT FROM OLD.idempotency_key
    OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
    OR NEW.reverses_entry_id   IS DISTINCT FROM OLD.reverses_entry_id
    OR NEW.created_at          IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'journal_entry % is immutable; only reversal linkage may change', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- The only legal transition is POSTED -> REVERSED, exactly once, and it
    -- must point at a reversal entry that actually reverses this one.
    IF OLD.reversed_by_entry_id IS NOT NULL
       AND NEW.reversed_by_entry_id IS DISTINCT FROM OLD.reversed_by_entry_id THEN
        RAISE EXCEPTION 'journal_entry % has already been reversed', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.reversed_by_entry_id IS NULL AND NEW.reversed_by_entry_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM journal_entry r
                        WHERE r.id = NEW.reversed_by_entry_id
                          AND r.reverses_entry_id = OLD.id) THEN
        RAISE EXCEPTION 'journal_entry % cannot be marked reversed by %, which does not reverse it',
            OLD.id, NEW.reversed_by_entry_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------
-- 4. A journal entry must have postings. Checked at COMMIT so that the
--    entry row can be written before its legs.
-- ---------------------------------------------------------------------
CREATE FUNCTION wbank_assert_entry_has_postings() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    leg_count BIGINT;
BEGIN
    SELECT count(*) INTO leg_count FROM posting WHERE journal_entry_id = NEW.id;
    IF leg_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 are required',
            NEW.id, leg_count
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_must_have_postings
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION wbank_assert_entry_has_postings();

-- ---------------------------------------------------------------------
-- 5. Posting chain: sequence and running balance are verified, not trusted.
--    Also: a posting's booking time is its entry's booking time.
-- ---------------------------------------------------------------------
CREATE FUNCTION wbank_posting_chain_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    acct_normal   TEXT;
    prev_balance  BIGINT;
    effect        BIGINT;
    entry_booked  TIMESTAMPTZ;
BEGIN
    SELECT normal_balance INTO acct_normal FROM ledger_account WHERE id = NEW.ledger_account_id;

    IF NEW.account_sequence = 1 THEN
        prev_balance := 0;
    ELSE
        SELECT balance_after_minor INTO prev_balance
          FROM posting
         WHERE ledger_account_id = NEW.ledger_account_id
           AND account_sequence  = NEW.account_sequence - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'posting to account % skips sequence: % has no predecessor',
                NEW.ledger_account_id, NEW.account_sequence
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    effect := CASE WHEN NEW.direction = acct_normal THEN NEW.amount_minor ELSE -NEW.amount_minor END;

    IF NEW.balance_after_minor <> prev_balance + effect THEN
        RAISE EXCEPTION 'posting to account % seq % claims balance_after % but chain gives %',
            NEW.ledger_account_id, NEW.account_sequence, NEW.balance_after_minor, prev_balance + effect
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT booked_at INTO entry_booked FROM journal_entry WHERE id = NEW.journal_entry_id;
    IF NEW.booked_at IS DISTINCT FROM entry_booked THEN
        RAISE EXCEPTION 'posting booked_at % differs from its journal entry booked_at %',
            NEW.booked_at, entry_booked
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER posting_chain_guard
    BEFORE INSERT ON posting
    FOR EACH ROW EXECUTE FUNCTION wbank_posting_chain_guard();

-- Every posting must be reflected in the projection by COMMIT.
CREATE FUNCTION wbank_assert_posting_projected() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger_account_balance
                    WHERE ledger_account_id = NEW.ledger_account_id
                      AND last_sequence >= NEW.account_sequence) THEN
        RAISE EXCEPTION 'posting % (account %, seq %) is not reflected in ledger_account_balance',
            NEW.id, NEW.ledger_account_id, NEW.account_sequence
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER posting_must_be_projected
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION wbank_assert_posting_projected();

-- ---------------------------------------------------------------------
-- 6. Balance projection: it may only ever equal the tip of the chain.
--    "Update balance" without postings is therefore impossible.
-- ---------------------------------------------------------------------
ALTER TABLE ledger_account_balance
    ADD CONSTRAINT balance_count_matches_sequence CHECK (posting_count = last_sequence);

CREATE FUNCTION wbank_assert_balance_matches_ledger() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    cur        ledger_account_balance%ROWTYPE;
    tip        BIGINT;
BEGIN
    -- Re-read: at commit time the row may have been updated again since this
    -- event was queued; we verify the final state.
    SELECT * INTO cur FROM ledger_account_balance WHERE ledger_account_id = NEW.ledger_account_id;

    IF cur.last_sequence = 0 THEN
        IF cur.balance_minor <> 0 OR cur.total_debits_minor <> 0 OR cur.total_credits_minor <> 0 THEN
            RAISE EXCEPTION 'account % has no postings but a non-zero balance projection',
                cur.ledger_account_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        SELECT balance_after_minor INTO tip
          FROM posting
         WHERE ledger_account_id = cur.ledger_account_id
           AND account_sequence  = cur.last_sequence;
        IF NOT FOUND OR tip <> cur.balance_minor THEN
            RAISE EXCEPTION 'balance projection for account % (%, seq %) does not match its ledger (%)',
                cur.ledger_account_id, cur.balance_minor, cur.last_sequence, tip
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    IF EXISTS (SELECT 1 FROM posting
                WHERE ledger_account_id = cur.ledger_account_id
                  AND account_sequence  > cur.last_sequence) THEN
        RAISE EXCEPTION 'balance projection for account % lags its ledger', cur.ledger_account_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER balance_must_match_ledger
    AFTER INSERT OR UPDATE ON ledger_account_balance
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION wbank_assert_balance_matches_ledger();

CREATE FUNCTION wbank_balance_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger_account_balance rows cannot be deleted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.ledger_account_id IS DISTINCT FROM OLD.ledger_account_id
    OR NEW.currency          IS DISTINCT FROM OLD.currency
    OR NEW.last_sequence     < OLD.last_sequence
    OR NEW.total_debits_minor  < OLD.total_debits_minor
    OR NEW.total_credits_minor < OLD.total_credits_minor THEN
        RAISE EXCEPTION 'balance projection for account % may only move forward', OLD.ledger_account_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER balance_guard
    BEFORE UPDATE OR DELETE ON ledger_account_balance
    FOR EACH ROW EXECUTE FUNCTION wbank_balance_guard();

-- ---------------------------------------------------------------------
-- 7. Ledger account identity is immutable once created; accounts are
--    never deleted (postings reference them forever).
-- ---------------------------------------------------------------------
CREATE FUNCTION wbank_ledger_account_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger_account rows cannot be deleted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.id             IS DISTINCT FROM OLD.id
    OR NEW.code           IS DISTINCT FROM OLD.code
    OR NEW.account_type   IS DISTINCT FROM OLD.account_type
    OR NEW.normal_balance IS DISTINCT FROM OLD.normal_balance
    OR NEW.currency       IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'ledger_account % identity (code/type/currency) is immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'CLOSED' AND NEW.status <> 'CLOSED' THEN
        RAISE EXCEPTION 'ledger_account % is closed and cannot be reopened', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_account_guard
    BEFORE UPDATE OR DELETE ON ledger_account
    FOR EACH ROW EXECUTE FUNCTION wbank_ledger_account_guard();

-- Postings may only land on accounts that accept them.
CREATE FUNCTION wbank_posting_account_postable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT status FROM ledger_account WHERE id = NEW.ledger_account_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'ledger_account % is not ACTIVE and cannot accept postings', NEW.ledger_account_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER posting_account_postable
    BEFORE INSERT ON posting
    FOR EACH ROW EXECUTE FUNCTION wbank_posting_account_postable();

-- Currencies with postings may not have their scale changed underneath them.
CREATE FUNCTION wbank_currency_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.code IS DISTINCT FROM OLD.code
       OR NEW.minor_unit IS DISTINCT FROM OLD.minor_unit THEN
        RAISE EXCEPTION 'currency % code/scale is immutable reference data', OLD.code
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER currency_guard
    BEFORE UPDATE OR DELETE ON currency
    FOR EACH ROW EXECUTE FUNCTION wbank_currency_guard();

COMMENT ON COLUMN journal_entry.request_fingerprint IS
    'SHA-256 of the canonical request that first used idempotency_key. A replay with a different payload is rejected.';
COMMENT ON TABLE idempotency_record IS
    'RESERVED, currently unused. Ledger idempotency is enforced on journal_entry (idempotency_key + request_fingerprint).';
