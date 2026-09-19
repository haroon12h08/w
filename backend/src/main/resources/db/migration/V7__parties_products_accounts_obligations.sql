-- =====================================================================
-- V7 : The minimum banking domain around the ledger
--
--   party / person / organization : who exists in the world
--   customer                      : the bank's RELATIONSHIP with a party
--   product                       : what the bank offers (a contract type)
--   account                       : an instantiated position of a party under a
--                                   product, backed by exactly one ledger account
--   obligation (+ loan_terms,     : a contractual promise to pay between two
--     schedule, repayments)         parties, with monetary terms over time
--
-- Nothing in this migration stores a balance. Every monetary position is still
-- the ledger's; the domain records who, what, under which contract, and when.
-- See docs/domain.md.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Parties
-- ---------------------------------------------------------------------
CREATE TABLE party (
    id            UUID        PRIMARY KEY,
    party_type    TEXT        NOT NULL,
    legal_name    TEXT        NOT NULL,
    display_name  TEXT,
    country_code  CHAR(2)     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT party_type_valid        CHECK (party_type IN ('PERSON', 'ORGANIZATION')),
    CONSTRAINT party_legal_name_present CHECK (length(btrim(legal_name)) > 0),
    CONSTRAINT party_country_code_valid CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT party_id_type_unique    UNIQUE (id, party_type)
);

COMMENT ON TABLE party IS
    'A legal entity (natural person or organization) independent of any relationship with the bank.';

-- Subtype tables. The composite FK on (party_id, party_type) with a fixed
-- party_type makes it impossible to attach PERSON details to an ORGANIZATION.
CREATE TABLE person (
    party_id       UUID PRIMARY KEY,
    party_type     TEXT NOT NULL DEFAULT 'PERSON',
    given_name     TEXT,
    family_name    TEXT,
    -- Nullable only because parties migrated from V3 customers have no recorded
    -- date of birth. The application requires it for every new person.
    date_of_birth  DATE,

    CONSTRAINT person_is_person      CHECK (party_type = 'PERSON'),
    CONSTRAINT person_dob_plausible  CHECK (date_of_birth IS NULL OR date_of_birth > DATE '1850-01-01'),
    CONSTRAINT person_party_fk       FOREIGN KEY (party_id, party_type) REFERENCES party (id, party_type)
);

CREATE TABLE organization (
    party_id             UUID PRIMARY KEY,
    party_type           TEXT NOT NULL DEFAULT 'ORGANIZATION',
    -- Nullable for the same migration reason as person.date_of_birth.
    registration_number  TEXT,

    CONSTRAINT organization_is_organization CHECK (party_type = 'ORGANIZATION'),
    CONSTRAINT organization_registration_present
        CHECK (registration_number IS NULL OR length(btrim(registration_number)) > 0),
    CONSTRAINT organization_party_fk FOREIGN KEY (party_id, party_type) REFERENCES party (id, party_type)
);

CREATE UNIQUE INDEX organization_registration_unique
    ON organization (registration_number) WHERE registration_number IS NOT NULL;

-- The institution itself is a party: it is the creditor of every loan it grants.
INSERT INTO party (id, party_type, legal_name, display_name, country_code)
VALUES (md5('wbank.institution')::uuid, 'ORGANIZATION', 'W Bank (research institution)', 'W Bank', 'GB');
INSERT INTO organization (party_id, registration_number)
VALUES (md5('wbank.institution')::uuid, 'W-RESEARCH-0001');

-- ---------------------------------------------------------------------
-- 2. Customer becomes a relationship WITH a party
-- ---------------------------------------------------------------------
ALTER TABLE customer ADD COLUMN party_id UUID;
UPDATE customer SET party_id = md5('wbank.party.' || id::text)::uuid;

INSERT INTO party (id, party_type, legal_name, display_name, country_code, created_at, updated_at)
SELECT party_id,
       CASE customer_type WHEN 'INDIVIDUAL' THEN 'PERSON' ELSE 'ORGANIZATION' END,
       legal_name, display_name, country_code, created_at, updated_at
  FROM customer;
INSERT INTO person (party_id)       SELECT party_id FROM customer WHERE customer_type = 'INDIVIDUAL';
INSERT INTO organization (party_id) SELECT party_id FROM customer WHERE customer_type = 'ORGANISATION';

ALTER TABLE customer
    ALTER COLUMN party_id SET NOT NULL,
    ADD CONSTRAINT customer_party_fk        FOREIGN KEY (party_id) REFERENCES party (id),
    -- One relationship per party: the bank knows a party as a customer at most once.
    ADD CONSTRAINT customer_party_unique    UNIQUE (party_id),
    ADD CONSTRAINT customer_id_party_unique UNIQUE (id, party_id);

-- Identity attributes now live on the party.
ALTER TABLE customer
    DROP COLUMN customer_type,
    DROP COLUMN legal_name,
    DROP COLUMN display_name,
    DROP COLUMN country_code;

ALTER TABLE customer ADD COLUMN activated_at TIMESTAMPTZ, ADD COLUMN closed_at TIMESTAMPTZ;
UPDATE customer SET activated_at = onboarded_at;
UPDATE customer SET closed_at = updated_at WHERE status = 'CLOSED';

ALTER TABLE customer DROP CONSTRAINT customer_status_valid;
ALTER TABLE customer
    ADD CONSTRAINT customer_status_valid CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    ADD CONSTRAINT customer_lifecycle_timestamps CHECK (
        (status = 'PENDING'                 AND activated_at IS NULL     AND closed_at IS NULL)
     OR (status IN ('ACTIVE', 'SUSPENDED')  AND activated_at IS NOT NULL AND closed_at IS NULL)
     OR (status = 'CLOSED'                                               AND closed_at IS NOT NULL)
    );

COMMENT ON TABLE customer IS
    'The institution''s relationship with exactly one party. Lifecycle: PENDING -> ACTIVE <-> SUSPENDED -> CLOSED.';

-- ---------------------------------------------------------------------
-- 3. Products
-- ---------------------------------------------------------------------
CREATE TABLE product (
    code              TEXT        PRIMARY KEY,
    name              TEXT        NOT NULL,
    family            TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    allows_overdraft  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT product_code_format    CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT product_family_valid   CHECK (family IN ('DEPOSIT', 'LOAN')),
    CONSTRAINT product_status_valid   CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT product_overdraft_only_on_deposits CHECK (family = 'DEPOSIT' OR NOT allows_overdraft),
    CONSTRAINT product_code_family_unique UNIQUE (code, family)
);

COMMENT ON TABLE product IS
    'A contract type the bank offers. Family fixes the accounting nature of positions opened under it.';

INSERT INTO product (code, name, family, status, allows_overdraft) VALUES
    ('CURRENT',   'Current account',       'DEPOSIT', 'ACTIVE', TRUE),
    ('SAVINGS',   'Savings account',       'DEPOSIT', 'ACTIVE', FALSE),
    ('TERM_LOAN', 'Amortising term loan',  'LOAN',    'ACTIVE', FALSE);

-- ---------------------------------------------------------------------
-- 4. Ledger reference data for lending
-- ---------------------------------------------------------------------
ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_purpose_valid;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_purpose_valid CHECK (purpose IN (
    'INTERNAL_CASH', 'INTERNAL_SUSPENSE', 'INTERNAL_EQUITY', 'INTERNAL_INTEREST_INCOME',
    'CUSTOMER_DEPOSIT', 'LOAN_RECEIVABLE'));

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_type_valid;
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_type_valid CHECK (entry_type IN (
    'CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'CUSTOMER_TRANSFER', 'REVERSAL', 'ADJUSTMENT',
    'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT'));

INSERT INTO ledger_account (id, code, name, account_type, normal_balance, currency, purpose, status)
SELECT md5('wbank.internal.interest_income.' || c.code)::uuid,
       '4000-INTEREST-INCOME-' || c.code,
       'Interest income (' || c.code || ')',
       'REVENUE', 'CREDIT', c.code, 'INTERNAL_INTEREST_INCOME', 'ACTIVE'
  FROM currency c;

INSERT INTO ledger_account_balance (ledger_account_id, currency, min_balance_minor)
SELECT a.id, a.currency, NULL FROM ledger_account a WHERE a.purpose = 'INTERNAL_INTEREST_INCOME';

-- ---------------------------------------------------------------------
-- 5. deposit_account generalises to account
-- ---------------------------------------------------------------------
ALTER TABLE deposit_account RENAME TO account;
ALTER SEQUENCE deposit_account_number_seq RENAME TO account_number_seq;
ALTER TABLE account RENAME CONSTRAINT deposit_account_number_unique           TO account_number_unique;
ALTER TABLE account RENAME CONSTRAINT deposit_account_ledger_account_unique   TO account_ledger_account_unique;
ALTER TABLE account RENAME CONSTRAINT deposit_account_overdraft_non_negative  TO account_overdraft_non_negative;
ALTER TABLE account RENAME CONSTRAINT deposit_account_closed_consistency      TO account_closed_consistency;
ALTER TABLE account RENAME CONSTRAINT deposit_account_currency_matches_ledger TO account_currency_matches_ledger;
ALTER INDEX deposit_account_customer_idx RENAME TO account_customer_idx;
ALTER INDEX deposit_account_status_idx   RENAME TO account_status_idx;

ALTER TABLE account
    ADD COLUMN owner_party_id UUID,
    ADD COLUMN product_family TEXT,
    ADD COLUMN activated_at   TIMESTAMPTZ;

UPDATE account a SET owner_party_id = c.party_id FROM customer c WHERE c.id = a.customer_id;
UPDATE account SET product_family = 'DEPOSIT', activated_at = opened_at;

ALTER TABLE account DROP CONSTRAINT deposit_account_status_valid;
ALTER TABLE account DROP CONSTRAINT deposit_account_product_valid;

ALTER TABLE account
    ALTER COLUMN owner_party_id SET NOT NULL,
    ALTER COLUMN product_family SET NOT NULL,
    -- A PENDING account has no ledger position yet.
    ALTER COLUMN ledger_account_id DROP NOT NULL,
    ADD CONSTRAINT account_status_valid CHECK (status IN ('PENDING', 'ACTIVE', 'FROZEN', 'CLOSED')),
    ADD CONSTRAINT account_product_fk
        FOREIGN KEY (product_code, product_family) REFERENCES product (code, family),
    -- The owner is the party behind the customer relationship; they cannot diverge.
    ADD CONSTRAINT account_owner_matches_customer
        FOREIGN KEY (customer_id, owner_party_id) REFERENCES customer (id, party_id),
    ADD CONSTRAINT account_loans_have_no_overdraft
        CHECK (product_family = 'DEPOSIT' OR overdraft_limit_minor = 0),
    ADD CONSTRAINT account_ledger_presence CHECK (
        (status = 'PENDING'           AND ledger_account_id IS NULL     AND activated_at IS NULL)
     OR (status IN ('ACTIVE','FROZEN') AND ledger_account_id IS NOT NULL AND activated_at IS NOT NULL)
     OR (status = 'CLOSED' AND ((ledger_account_id IS NULL     AND activated_at IS NULL)
                             OR (ledger_account_id IS NOT NULL AND activated_at IS NOT NULL)))
    ),
    ADD CONSTRAINT account_id_owner_unique    UNIQUE (id, owner_party_id),
    ADD CONSTRAINT account_id_currency_unique UNIQUE (id, currency);

CREATE INDEX account_owner_party_idx ON account (owner_party_id);

COMMENT ON TABLE account IS
    'A financial position of a party under a product. Holds no balance: its money is its ledger account''s postings.';

-- Pre-V7 deposit accounts were frozen/closed without telling the ledger. Align them.
UPDATE ledger_account la SET status = a.status, updated_at = now()
  FROM account a
 WHERE a.ledger_account_id = la.id AND a.status IN ('FROZEN', 'CLOSED') AND la.status <> a.status;

-- ---------------------------------------------------------------------
-- 6. Obligations
-- ---------------------------------------------------------------------
CREATE SEQUENCE obligation_number_seq START WITH 500000001 INCREMENT BY 1;

CREATE TABLE obligation (
    id                     UUID        PRIMARY KEY,
    obligation_number      TEXT        NOT NULL,
    obligation_type        TEXT        NOT NULL,
    status                 TEXT        NOT NULL,
    creditor_party_id      UUID        NOT NULL REFERENCES party (id),
    debtor_party_id        UUID        NOT NULL REFERENCES party (id),
    currency               CHAR(3)     NOT NULL REFERENCES currency (code),
    principal_minor        BIGINT      NOT NULL,
    -- The account that represents this obligation's outstanding position.
    position_account_id    UUID        NOT NULL,
    -- The debtor's account through which money is paid out and back.
    settlement_account_id  UUID        NOT NULL,
    proposed_at            TIMESTAMPTZ NOT NULL,
    start_date             DATE,
    maturity_date          DATE,
    disbursement_entry_id  UUID        REFERENCES journal_entry (id),
    activated_at           TIMESTAMPTZ,
    settled_at             TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT obligation_number_unique       UNIQUE (obligation_number),
    CONSTRAINT obligation_type_valid          CHECK (obligation_type IN ('LOAN')),
    CONSTRAINT obligation_status_valid        CHECK (status IN ('PROPOSED', 'ACTIVE', 'SETTLED', 'CANCELLED')),
    CONSTRAINT obligation_distinct_parties    CHECK (creditor_party_id <> debtor_party_id),
    CONSTRAINT obligation_principal_positive  CHECK (principal_minor > 0),
    CONSTRAINT obligation_distinct_accounts   CHECK (position_account_id <> settlement_account_id),
    CONSTRAINT obligation_position_unique     UNIQUE (position_account_id),
    CONSTRAINT obligation_disbursement_unique UNIQUE (disbursement_entry_id),
    CONSTRAINT obligation_id_currency_unique  UNIQUE (id, currency),
    CONSTRAINT obligation_dates_ordered       CHECK (maturity_date IS NULL OR maturity_date > start_date),

    -- Both accounts belong to the debtor, and are in the obligation's currency.
    CONSTRAINT obligation_position_owned_by_debtor
        FOREIGN KEY (position_account_id, debtor_party_id)   REFERENCES account (id, owner_party_id),
    CONSTRAINT obligation_settlement_owned_by_debtor
        FOREIGN KEY (settlement_account_id, debtor_party_id) REFERENCES account (id, owner_party_id),
    CONSTRAINT obligation_position_currency
        FOREIGN KEY (position_account_id, currency)   REFERENCES account (id, currency),
    CONSTRAINT obligation_settlement_currency
        FOREIGN KEY (settlement_account_id, currency) REFERENCES account (id, currency),

    CONSTRAINT obligation_lifecycle_fields CHECK (
        (status = 'PROPOSED'  AND activated_at IS NULL AND disbursement_entry_id IS NULL AND start_date IS NULL
                              AND maturity_date IS NULL AND settled_at IS NULL AND cancelled_at IS NULL)
     OR (status = 'ACTIVE'    AND activated_at IS NOT NULL AND disbursement_entry_id IS NOT NULL
                              AND start_date IS NOT NULL AND maturity_date IS NOT NULL
                              AND settled_at IS NULL AND cancelled_at IS NULL)
     OR (status = 'SETTLED'   AND activated_at IS NOT NULL AND disbursement_entry_id IS NOT NULL
                              AND settled_at IS NOT NULL AND cancelled_at IS NULL)
     OR (status = 'CANCELLED' AND activated_at IS NULL AND disbursement_entry_id IS NULL
                              AND cancelled_at IS NOT NULL)
    )
);

CREATE INDEX obligation_debtor_idx     ON obligation (debtor_party_id);
CREATE INDEX obligation_settlement_idx ON obligation (settlement_account_id);

COMMENT ON TABLE obligation IS
    'A contractual promise by debtor to pay creditor. Terms are immutable; progress is derived from repayments and the ledger.';

CREATE TABLE loan_terms (
    obligation_id        UUID     PRIMARY KEY REFERENCES obligation (id),
    annual_rate_bps      INTEGER  NOT NULL,
    installment_count    SMALLINT NOT NULL,
    repayment_frequency  TEXT     NOT NULL,
    amortization_method  TEXT     NOT NULL,

    CONSTRAINT loan_terms_rate_range        CHECK (annual_rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT loan_terms_installment_range CHECK (installment_count BETWEEN 1 AND 480),
    CONSTRAINT loan_terms_frequency_valid   CHECK (repayment_frequency = 'MONTHLY'),
    CONSTRAINT loan_terms_method_valid      CHECK (amortization_method = 'ANNUITY')
);

-- The contractual schedule. Immutable once written; what has been PAID against
-- it is derived from repayment_allocation, never stored here.
CREATE TABLE obligation_installment (
    id                   UUID     PRIMARY KEY,
    obligation_id        UUID     NOT NULL REFERENCES obligation (id),
    sequence_no          SMALLINT NOT NULL,
    due_date             DATE     NOT NULL,
    principal_due_minor  BIGINT   NOT NULL,
    interest_due_minor   BIGINT   NOT NULL,

    CONSTRAINT installment_sequence_positive CHECK (sequence_no >= 1),
    CONSTRAINT installment_amounts_valid
        CHECK (principal_due_minor >= 0 AND interest_due_minor >= 0
               AND principal_due_minor + interest_due_minor > 0),
    CONSTRAINT installment_sequence_unique UNIQUE (obligation_id, sequence_no),
    CONSTRAINT installment_due_date_unique UNIQUE (obligation_id, due_date)
);

CREATE TABLE obligation_repayment (
    id                UUID        PRIMARY KEY,
    obligation_id     UUID        NOT NULL,
    currency          CHAR(3)     NOT NULL,
    journal_entry_id  UUID        NOT NULL REFERENCES journal_entry (id),
    idempotency_key   TEXT,
    amount_minor      BIGINT      NOT NULL,
    principal_minor   BIGINT      NOT NULL,
    interest_minor    BIGINT      NOT NULL,
    -- Which versioned allocation rule split this repayment (see AllocationPolicy).
    allocation_policy TEXT        NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT repayment_obligation_currency
        FOREIGN KEY (obligation_id, currency) REFERENCES obligation (id, currency),
    CONSTRAINT repayment_entry_unique   UNIQUE (journal_entry_id),
    CONSTRAINT repayment_amount_split
        CHECK (amount_minor > 0 AND principal_minor >= 0 AND interest_minor >= 0
               AND amount_minor = principal_minor + interest_minor)
);

CREATE UNIQUE INDEX repayment_idempotency_unique
    ON obligation_repayment (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX repayment_obligation_idx ON obligation_repayment (obligation_id);

CREATE TABLE repayment_allocation (
    repayment_id     UUID   NOT NULL REFERENCES obligation_repayment (id),
    installment_id   UUID   NOT NULL REFERENCES obligation_installment (id),
    principal_minor  BIGINT NOT NULL,
    interest_minor   BIGINT NOT NULL,

    PRIMARY KEY (repayment_id, installment_id),
    CONSTRAINT allocation_amounts_valid
        CHECK (principal_minor >= 0 AND interest_minor >= 0 AND principal_minor + interest_minor > 0)
);

CREATE INDEX allocation_installment_idx ON repayment_allocation (installment_id);

-- Append-only / immutable evidence.
CREATE TRIGGER loan_terms_immutable             BEFORE UPDATE OR DELETE ON loan_terms
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER installment_immutable            BEFORE UPDATE OR DELETE ON obligation_installment
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER repayment_append_only            BEFORE UPDATE OR DELETE ON obligation_repayment
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
CREATE TRIGGER repayment_allocation_append_only BEFORE UPDATE OR DELETE ON repayment_allocation
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();

-- =====================================================================
-- 7. Lifecycle and consistency triggers
-- =====================================================================

-- Party: identity immutable, never deleted, subtype row must exist at COMMIT.
CREATE FUNCTION wbank_party_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'party rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_type IS DISTINCT FROM OLD.party_type THEN
        RAISE EXCEPTION 'party % identity and type are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER party_guard BEFORE UPDATE OR DELETE ON party
    FOR EACH ROW EXECUTE FUNCTION wbank_party_guard();

CREATE FUNCTION wbank_assert_party_has_subtype() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.party_type = 'PERSON'       AND NOT EXISTS (SELECT 1 FROM person       WHERE party_id = NEW.id))
    OR (NEW.party_type = 'ORGANIZATION' AND NOT EXISTS (SELECT 1 FROM organization WHERE party_id = NEW.id)) THEN
        RAISE EXCEPTION 'party % of type % has no % record', NEW.id, NEW.party_type, lower(NEW.party_type)
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER party_must_have_subtype AFTER INSERT ON party
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_assert_party_has_subtype();

-- Customer lifecycle.
CREATE FUNCTION wbank_customer_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'customer rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'a customer relationship starts PENDING, not %', NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.customer_number IS DISTINCT FROM OLD.customer_number THEN
        RAISE EXCEPTION 'customer % party and number are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'CLOSED' AND (NEW.status <> 'CLOSED' OR NEW.email IS DISTINCT FROM OLD.email) THEN
        RAISE EXCEPTION 'customer % is closed', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PENDING'   AND NEW.status IN ('ACTIVE', 'CLOSED'))
        OR (OLD.status = 'ACTIVE'    AND NEW.status IN ('SUSPENDED', 'CLOSED'))
        OR (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE', 'CLOSED'))) THEN
        RAISE EXCEPTION 'customer % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status = 'CLOSED' AND OLD.status <> 'CLOSED'
       AND EXISTS (SELECT 1 FROM account WHERE customer_id = OLD.id AND status <> 'CLOSED') THEN
        RAISE EXCEPTION 'customer % still has open accounts', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER customer_guard BEFORE INSERT OR UPDATE OR DELETE ON customer
    FOR EACH ROW EXECUTE FUNCTION wbank_customer_guard();

-- Product lifecycle.
CREATE FUNCTION wbank_product_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'product rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.code IS DISTINCT FROM OLD.code OR NEW.family IS DISTINCT FROM OLD.family
       OR NEW.allows_overdraft IS DISTINCT FROM OLD.allows_overdraft THEN
        RAISE EXCEPTION 'product % definition is immutable', OLD.code
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'WITHDRAWN') THEN
        RAISE EXCEPTION 'product % cannot move from % to %', OLD.code, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER product_guard BEFORE UPDATE OR DELETE ON product
    FOR EACH ROW EXECUTE FUNCTION wbank_product_guard();

-- Account lifecycle.
CREATE FUNCTION wbank_account_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
        -- FOR SHARE serialises against a concurrent customer close/suspend.
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
                          AND status IN ('PROPOSED', 'ACTIVE')) THEN
                RAISE EXCEPTION 'account % is referenced by an open obligation and cannot close', OLD.id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_guard BEFORE INSERT OR UPDATE OR DELETE ON account
    FOR EACH ROW EXECUTE FUNCTION wbank_account_guard();

-- Account <-> ledger account: status mirrored, nature matches product family.
-- Checked at COMMIT from both sides, because the two rows are updated separately.
CREATE FUNCTION wbank_check_account_ledger(acct_id UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    a  account%ROWTYPE;
    la ledger_account%ROWTYPE;
BEGIN
    SELECT * INTO a FROM account WHERE id = acct_id;
    IF NOT FOUND OR a.ledger_account_id IS NULL THEN
        RETURN;
    END IF;
    SELECT * INTO la FROM ledger_account WHERE id = a.ledger_account_id;
    IF la.status <> a.status THEN
        RAISE EXCEPTION 'account % is % but its ledger account % is %', a.id, a.status, la.id, la.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NOT ((a.product_family = 'DEPOSIT' AND la.account_type = 'LIABILITY' AND la.purpose = 'CUSTOMER_DEPOSIT')
         OR (a.product_family = 'LOAN'    AND la.account_type = 'ASSET'     AND la.purpose = 'LOAN_RECEIVABLE')) THEN
        RAISE EXCEPTION 'account % (%) is backed by a % / % ledger account', a.id, a.product_family,
            la.account_type, la.purpose
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE FUNCTION wbank_account_ledger_sync_from_account() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM wbank_check_account_ledger(NEW.id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_ledger_sync AFTER INSERT OR UPDATE ON account
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_account_ledger_sync_from_account();

CREATE FUNCTION wbank_account_ledger_sync_from_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    acct UUID;
BEGIN
    SELECT id INTO acct FROM account WHERE ledger_account_id = NEW.id;
    IF FOUND THEN
        PERFORM wbank_check_account_ledger(acct);
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ledger_account_sync_to_account AFTER UPDATE ON ledger_account
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_account_ledger_sync_from_ledger();

-- Obligation lifecycle + immutable terms.
CREATE FUNCTION wbank_obligation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
    OR (OLD.status <> 'PROPOSED' AND (NEW.start_date IS DISTINCT FROM OLD.start_date
                                   OR NEW.maturity_date IS DISTINCT FROM OLD.maturity_date
                                   OR NEW.disbursement_entry_id IS DISTINCT FROM OLD.disbursement_entry_id)) THEN
        RAISE EXCEPTION 'obligation % terms are immutable', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'CANCELLED'))
        OR (OLD.status = 'ACTIVE'   AND NEW.status = 'SETTLED')) THEN
        RAISE EXCEPTION 'obligation % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status IN ('SETTLED', 'CANCELLED') AND NEW.status = OLD.status
       AND (NEW.settled_at IS DISTINCT FROM OLD.settled_at OR NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at) THEN
        RAISE EXCEPTION 'obligation % is terminal', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER obligation_guard BEFORE INSERT OR UPDATE OR DELETE ON obligation
    FOR EACH ROW EXECUTE FUNCTION wbank_obligation_guard();

-- The central domain <-> ledger invariant, checked at COMMIT:
--   * the schedule's principal equals the contract principal;
--   * what repayments say was paid equals what their allocations say was paid;
--   * no installment is over-paid;
--   * the position's LEDGER balance equals principal - principal repaid;
--   * SETTLED <=> everything paid; PROPOSED/CANCELLED => no schedule, no money.
CREATE FUNCTION wbank_check_obligation(obl_id UUID) RETURNS void LANGUAGE plpgsql AS $$
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
    ledger_balance   BIGINT;
    ledger_id        UUID;
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

    IF o.status IN ('PROPOSED', 'CANCELLED') THEN
        IF sched_count > 0 OR rep_principal + rep_interest > 0 THEN
            RAISE EXCEPTION 'obligation % is % but has a schedule or repayments', o.id, o.status
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
                WHERE r.obligation_id = o.id AND i.obligation_id <> o.id) THEN
        RAISE EXCEPTION 'obligation % has allocations to another obligation''s installments', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM obligation_installment i
                 LEFT JOIN repayment_allocation ra ON ra.installment_id = i.id
                WHERE i.obligation_id = o.id
                GROUP BY i.id, i.principal_due_minor, i.interest_due_minor
               HAVING coalesce(sum(ra.principal_minor), 0) > i.principal_due_minor
                   OR coalesce(sum(ra.interest_minor), 0)  > i.interest_due_minor) THEN
        RAISE EXCEPTION 'obligation % has an over-paid installment', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT ledger_account_id INTO ledger_id FROM account WHERE id = o.position_account_id;
    SELECT balance_minor INTO ledger_balance FROM ledger_account_balance WHERE ledger_account_id = ledger_id;
    IF ledger_balance IS DISTINCT FROM o.principal_minor - rep_principal THEN
        RAISE EXCEPTION 'obligation % outstanding principal % disagrees with its ledger position %',
            o.id, o.principal_minor - rep_principal, ledger_balance
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT je.entry_type INTO entry_type FROM journal_entry je WHERE je.id = o.disbursement_entry_id;
    IF entry_type <> 'LOAN_DISBURSEMENT' THEN
        RAISE EXCEPTION 'obligation % disbursement entry is a %', o.id, entry_type
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF o.status = 'SETTLED' AND (rep_principal <> sched_principal OR rep_interest <> sched_interest) THEN
        RAISE EXCEPTION 'obligation % is SETTLED but not fully repaid', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF o.status = 'ACTIVE' AND rep_principal = sched_principal AND rep_interest = sched_interest THEN
        RAISE EXCEPTION 'obligation % is fully repaid but still ACTIVE', o.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE FUNCTION wbank_obligation_consistency_from_obligation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM wbank_check_obligation(NEW.id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER obligation_consistent AFTER INSERT OR UPDATE ON obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_obligation_consistency_from_obligation();

CREATE FUNCTION wbank_obligation_consistency_from_child() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM wbank_check_obligation(NEW.obligation_id);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER installment_consistent AFTER INSERT ON obligation_installment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_obligation_consistency_from_child();
CREATE CONSTRAINT TRIGGER repayment_consistent AFTER INSERT ON obligation_repayment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_obligation_consistency_from_child();

CREATE FUNCTION wbank_obligation_consistency_from_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM wbank_check_obligation((SELECT obligation_id FROM obligation_repayment WHERE id = NEW.repayment_id));
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER allocation_consistent AFTER INSERT ON repayment_allocation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION wbank_obligation_consistency_from_allocation();
