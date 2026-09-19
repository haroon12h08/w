-- =====================================================================
-- V2 : The ledger
--
-- This migration defines the authoritative representation of financial
-- state. Everything else in the system is a projection of, or an input
-- to, these three tables:
--
--   ledger_account  - a named place value can sit
--   journal_entry   - an atomic, balanced financial fact
--   posting         - one leg of a journal entry (immutable)
--
-- Design rules encoded here:
--   1. Money is an integer number of minor units. Never a float.
--   2. A posting is append-only. Corrections are made by reversal.
--   3. Debits must equal credits within a journal entry. Enforced by the
--      database, not only by the application.
--   4. A posting, its journal entry and its ledger account must all be
--      denominated in the same currency. Enforced by composite FKs.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Chart of accounts
-- ---------------------------------------------------------------------
CREATE TABLE ledger_account (
    id              UUID        PRIMARY KEY,
    code            TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    account_type    TEXT        NOT NULL,
    normal_balance  TEXT        NOT NULL,
    currency        CHAR(3)     NOT NULL REFERENCES currency (code),
    purpose         TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ledger_account_code_unique UNIQUE (code),
    CONSTRAINT ledger_account_type_valid
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT ledger_account_normal_balance_valid
        CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_account_purpose_valid
        CHECK (purpose IN ('INTERNAL_CASH', 'INTERNAL_SUSPENSE', 'INTERNAL_EQUITY', 'CUSTOMER_DEPOSIT')),
    CONSTRAINT ledger_account_status_valid
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),

    -- Accounting identity: the normal balance of an account is a function of
    -- its type. Storing it denormalised is convenient, but it must not drift.
    CONSTRAINT ledger_account_normal_balance_matches_type CHECK (
        (account_type IN ('ASSET', 'EXPENSE')              AND normal_balance = 'DEBIT')
     OR (account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND normal_balance = 'CREDIT')
    ),

    -- Target of composite foreign keys used to pin currency consistency.
    CONSTRAINT ledger_account_id_currency_unique UNIQUE (id, currency)
);

CREATE INDEX ledger_account_purpose_idx  ON ledger_account (purpose);
CREATE INDEX ledger_account_currency_idx ON ledger_account (currency);

COMMENT ON TABLE ledger_account IS 'Chart of accounts. A ledger account is single-currency by construction.';

-- ---------------------------------------------------------------------
-- Journal entries
-- ---------------------------------------------------------------------
CREATE TABLE journal_entry (
    id                  UUID        PRIMARY KEY,
    entry_number        BIGINT      GENERATED ALWAYS AS IDENTITY,
    currency            CHAR(3)     NOT NULL REFERENCES currency (code),
    entry_type          TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    description         TEXT        NOT NULL,
    value_date          DATE        NOT NULL,
    booked_at           TIMESTAMPTZ NOT NULL,
    total_amount_minor  BIGINT      NOT NULL,
    reverses_entry_id   UUID        REFERENCES journal_entry (id),
    reversed_by_entry_id UUID       REFERENCES journal_entry (id),

    -- Provenance. Deliberately mandatory: an unattributable financial fact
    -- is not acceptable in this system.
    correlation_id      UUID        NOT NULL,
    initiated_by        TEXT        NOT NULL,
    initiator_type      TEXT        NOT NULL,
    source_operation    TEXT        NOT NULL,
    idempotency_key     TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT journal_entry_number_unique UNIQUE (entry_number),
    CONSTRAINT journal_entry_type_valid CHECK (entry_type IN (
        'CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'CUSTOMER_TRANSFER', 'REVERSAL')),
    CONSTRAINT journal_entry_status_valid CHECK (status IN ('POSTED', 'REVERSED')),
    CONSTRAINT journal_entry_initiator_type_valid
        CHECK (initiator_type IN ('HUMAN', 'SYSTEM', 'SERVICE', 'AGENT')),
    CONSTRAINT journal_entry_total_amount_positive CHECK (total_amount_minor > 0),
    CONSTRAINT journal_entry_reversal_is_typed CHECK (
        (entry_type = 'REVERSAL' AND reverses_entry_id IS NOT NULL)
     OR (entry_type <> 'REVERSAL' AND reverses_entry_id IS NULL)
    ),
    CONSTRAINT journal_entry_not_self_reversing CHECK (
        reverses_entry_id IS NULL OR reverses_entry_id <> id
    ),
    CONSTRAINT journal_entry_reversed_status_consistent CHECK (
        (status = 'REVERSED' AND reversed_by_entry_id IS NOT NULL)
     OR (status = 'POSTED'   AND reversed_by_entry_id IS NULL)
    ),
    CONSTRAINT journal_entry_id_currency_unique UNIQUE (id, currency)
);

-- An entry may be reversed at most once.
CREATE UNIQUE INDEX journal_entry_reverses_unique
    ON journal_entry (reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;

-- Idempotency at the ledger level: the same client request can never produce
-- two journal entries, even if the application layer is bypassed or racing.
CREATE UNIQUE INDEX journal_entry_idempotency_key_unique
    ON journal_entry (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX journal_entry_booked_at_idx      ON journal_entry (booked_at DESC);
CREATE INDEX journal_entry_correlation_id_idx ON journal_entry (correlation_id);
CREATE INDEX journal_entry_value_date_idx     ON journal_entry (value_date);

COMMENT ON TABLE journal_entry IS
    'An atomic balanced financial fact. Immutable except for reversal linkage.';

-- ---------------------------------------------------------------------
-- Postings (the actual double-entry legs)
-- ---------------------------------------------------------------------
CREATE TABLE posting (
    id                   UUID        PRIMARY KEY,
    journal_entry_id     UUID        NOT NULL REFERENCES journal_entry (id),
    entry_leg            SMALLINT    NOT NULL,
    ledger_account_id    UUID        NOT NULL REFERENCES ledger_account (id),
    currency             CHAR(3)     NOT NULL,
    direction            TEXT        NOT NULL,
    amount_minor         BIGINT      NOT NULL,

    -- Generated, so that "money is neither created nor destroyed" is a single
    -- SQL predicate: SUM(signed_amount_minor) = 0 for any closed set.
    signed_amount_minor  BIGINT      GENERATED ALWAYS AS (
        CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END
    ) STORED,

    -- Per-account monotonic sequence + running balance snapshot. This makes
    -- the account statement reconstructible and tamper-evident without
    -- trusting any cached aggregate.
    account_sequence     BIGINT      NOT NULL,
    balance_after_minor  BIGINT      NOT NULL,

    booked_at            TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT posting_direction_valid CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT posting_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT posting_leg_positive    CHECK (entry_leg >= 0),
    CONSTRAINT posting_sequence_positive CHECK (account_sequence > 0),
    CONSTRAINT posting_leg_unique      UNIQUE (journal_entry_id, entry_leg),
    CONSTRAINT posting_account_sequence_unique UNIQUE (ledger_account_id, account_sequence),

    -- Currency may not diverge between posting, entry and account.
    CONSTRAINT posting_currency_matches_entry
        FOREIGN KEY (journal_entry_id, currency) REFERENCES journal_entry (id, currency),
    CONSTRAINT posting_currency_matches_account
        FOREIGN KEY (ledger_account_id, currency) REFERENCES ledger_account (id, currency)
);

CREATE INDEX posting_account_statement_idx
    ON posting (ledger_account_id, account_sequence DESC);
CREATE INDEX posting_journal_entry_idx ON posting (journal_entry_id);
CREATE INDEX posting_booked_at_idx     ON posting (booked_at DESC);

COMMENT ON TABLE posting IS 'Immutable double-entry leg. Append-only: enforced by trigger.';

-- ---------------------------------------------------------------------
-- Balance projection
--
-- Balances are NOT the source of truth. This table is a materialised
-- projection of SUM(posting) maintained inside the same database
-- transaction that writes the postings, so it can never lag or diverge.
-- `reconciliation` verifies it against the ledger.
--
-- balance_minor is signed IN THE DIRECTION OF THE ACCOUNT'S NORMAL BALANCE:
--   debit-normal  : debits - credits
--   credit-normal : credits - debits
-- so that "balance >= floor" has the same meaning for a cash asset account
-- and for a customer deposit liability account.
-- ---------------------------------------------------------------------
CREATE TABLE ledger_account_balance (
    ledger_account_id    UUID        PRIMARY KEY REFERENCES ledger_account (id),
    currency             CHAR(3)     NOT NULL,
    balance_minor        BIGINT      NOT NULL DEFAULT 0,
    total_debits_minor   BIGINT      NOT NULL DEFAULT 0,
    total_credits_minor  BIGINT      NOT NULL DEFAULT 0,
    posting_count        BIGINT      NOT NULL DEFAULT 0,
    last_sequence        BIGINT      NOT NULL DEFAULT 0,

    -- NULL means "no floor" (internal accounts). A customer deposit account
    -- carries 0, or -overdraft_limit when an overdraft is granted.
    min_balance_minor    BIGINT,

    last_posted_at       TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT balance_totals_non_negative
        CHECK (total_debits_minor >= 0 AND total_credits_minor >= 0 AND posting_count >= 0),

    -- The overdraft / no-negative-balance invariant, enforced by the database.
    -- Even a defective application cannot write an unauthorised debit balance.
    CONSTRAINT balance_respects_floor
        CHECK (min_balance_minor IS NULL OR balance_minor >= min_balance_minor),

    CONSTRAINT balance_currency_matches_account
        FOREIGN KEY (ledger_account_id, currency) REFERENCES ledger_account (id, currency)
);

COMMENT ON TABLE ledger_account_balance IS
    'Derived projection of the ledger, maintained transactionally. Not authoritative.';

-- ---------------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------------
CREATE FUNCTION wbank_forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'relation % is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER posting_is_append_only
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- A journal entry is immutable apart from its reversal linkage.
CREATE FUNCTION wbank_journal_entry_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal_entry is append-only; DELETE is not permitted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.id                 IS DISTINCT FROM OLD.id
    OR NEW.currency           IS DISTINCT FROM OLD.currency
    OR NEW.entry_type         IS DISTINCT FROM OLD.entry_type
    OR NEW.total_amount_minor IS DISTINCT FROM OLD.total_amount_minor
    OR NEW.value_date         IS DISTINCT FROM OLD.value_date
    OR NEW.booked_at          IS DISTINCT FROM OLD.booked_at
    OR NEW.correlation_id     IS DISTINCT FROM OLD.correlation_id
    OR NEW.idempotency_key    IS DISTINCT FROM OLD.idempotency_key
    OR NEW.reverses_entry_id  IS DISTINCT FROM OLD.reverses_entry_id THEN
        RAISE EXCEPTION 'journal_entry % is immutable; only reversal linkage may change', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.reversed_by_entry_id IS NOT NULL
       AND NEW.reversed_by_entry_id IS DISTINCT FROM OLD.reversed_by_entry_id THEN
        RAISE EXCEPTION 'journal_entry % has already been reversed', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entry_guard
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION wbank_journal_entry_guard();

-- ---------------------------------------------------------------------
-- The double-entry invariant, enforced at COMMIT time.
--
-- Deferred so that the legs of one entry may be inserted in any order,
-- but checked before the transaction is allowed to become durable.
-- ---------------------------------------------------------------------
CREATE FUNCTION wbank_assert_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    leg_count   BIGINT;
    net         BIGINT;
    debit_total BIGINT;
    declared    BIGINT;
BEGIN
    SELECT count(*),
           coalesce(sum(signed_amount_minor), 0),
           coalesce(sum(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0)
      INTO leg_count, net, debit_total
      FROM posting
     WHERE journal_entry_id = NEW.journal_entry_id;

    IF leg_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 are required',
            NEW.journal_entry_id, leg_count
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF net <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced by % minor units',
            NEW.journal_entry_id, net
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT total_amount_minor INTO declared
      FROM journal_entry WHERE id = NEW.journal_entry_id;

    IF declared <> debit_total THEN
        RAISE EXCEPTION 'journal entry % declares % but its debits total %',
            NEW.journal_entry_id, declared, debit_total
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER posting_entry_must_balance
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION wbank_assert_entry_balanced();
