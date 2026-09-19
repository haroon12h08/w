-- =====================================================================
-- V1 : Reference data
--
-- Currency is reference data, not configuration. A currency defines the
-- scale (number of minor units) used by EVERY monetary amount denominated
-- in it. The application never guesses a scale: it reads it from here.
-- =====================================================================

CREATE TABLE currency (
    code          CHAR(3)     PRIMARY KEY,
    numeric_code  SMALLINT    NOT NULL,
    minor_unit    SMALLINT    NOT NULL,
    display_name  TEXT        NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT currency_code_is_uppercase_alpha CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT currency_numeric_code_range      CHECK (numeric_code BETWEEN 1 AND 999),
    CONSTRAINT currency_minor_unit_range        CHECK (minor_unit BETWEEN 0 AND 4),
    CONSTRAINT currency_numeric_code_unique     UNIQUE (numeric_code)
);

COMMENT ON TABLE  currency IS 'ISO-4217 currencies supported by the ledger.';
COMMENT ON COLUMN currency.minor_unit IS
    'Number of decimal places. All monetary amounts are stored as integers of 10^-minor_unit units.';

INSERT INTO currency (code, numeric_code, minor_unit, display_name) VALUES
    ('USD', 840, 2, 'United States Dollar'),
    ('EUR', 978, 2, 'Euro'),
    ('GBP', 826, 2, 'Pound Sterling'),
    ('INR', 356, 2, 'Indian Rupee'),
    ('JPY', 392, 0, 'Japanese Yen');
