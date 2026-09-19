-- =====================================================================
-- V3 : Parties and banking products
--
-- A "customer" is a legal party the bank has a relationship with.
-- A "deposit account" is a product the bank sells to a party. It is NOT
-- a ledger account: it is a contract that OWNS exactly one ledger
-- account, which from the bank's point of view is a LIABILITY (the bank
-- owes the customer their money).
--
-- Keeping the product separate from the ledger account is the reason the
-- system can later add loans, cards, term deposits or securities without
-- touching the ledger core.
-- =====================================================================

CREATE TABLE customer (
    id              UUID        PRIMARY KEY,
    customer_number TEXT        NOT NULL,
    customer_type   TEXT        NOT NULL,
    legal_name      TEXT        NOT NULL,
    display_name    TEXT,
    country_code    CHAR(2)     NOT NULL,
    email           TEXT,
    status          TEXT        NOT NULL,
    onboarded_at    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT customer_number_unique UNIQUE (customer_number),
    CONSTRAINT customer_type_valid   CHECK (customer_type IN ('INDIVIDUAL', 'ORGANISATION')),
    CONSTRAINT customer_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT customer_country_code_valid CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT customer_legal_name_not_blank CHECK (length(btrim(legal_name)) > 0)
);

CREATE UNIQUE INDEX customer_email_unique_ci
    ON customer (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX customer_status_idx ON customer (status);

COMMENT ON TABLE customer IS 'A legal party (natural person or organisation) known to the bank.';


CREATE TABLE deposit_account (
    id                    UUID        PRIMARY KEY,
    account_number        TEXT        NOT NULL,
    customer_id           UUID        NOT NULL REFERENCES customer (id),
    ledger_account_id     UUID        NOT NULL REFERENCES ledger_account (id),
    currency              CHAR(3)     NOT NULL REFERENCES currency (code),
    product_code          TEXT        NOT NULL,
    status                TEXT        NOT NULL,
    overdraft_limit_minor BIGINT      NOT NULL DEFAULT 0,
    opened_at             TIMESTAMPTZ NOT NULL,
    closed_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT deposit_account_number_unique UNIQUE (account_number),
    -- One deposit account maps to exactly one ledger account, and vice versa.
    CONSTRAINT deposit_account_ledger_account_unique UNIQUE (ledger_account_id),
    CONSTRAINT deposit_account_status_valid
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT deposit_account_product_valid
        CHECK (product_code IN ('CURRENT', 'SAVINGS')),
    CONSTRAINT deposit_account_overdraft_non_negative
        CHECK (overdraft_limit_minor >= 0),
    CONSTRAINT deposit_account_closed_consistency CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL)
     OR (status <> 'CLOSED' AND closed_at IS NULL)
    ),
    CONSTRAINT deposit_account_currency_matches_ledger
        FOREIGN KEY (ledger_account_id, currency) REFERENCES ledger_account (id, currency)
);

CREATE INDEX deposit_account_customer_idx ON deposit_account (customer_id);
CREATE INDEX deposit_account_status_idx   ON deposit_account (status);

COMMENT ON TABLE deposit_account IS
    'Customer-facing demand deposit contract, backed 1:1 by a LIABILITY ledger account.';
