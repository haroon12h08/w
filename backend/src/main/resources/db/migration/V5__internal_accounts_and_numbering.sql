-- =====================================================================
-- V5 : Internal chart of accounts + human-facing numbering
--
-- Customer money does not appear from nowhere. A cash deposit debits the
-- bank's cash asset and credits the customer's deposit liability; the
-- bank's balance sheet grows on both sides and the entry balances.
-- Those internal counter-accounts must therefore exist before any
-- customer operation is possible, so they are reference data.
--
-- Internal accounts have NO balance floor (min_balance_minor IS NULL):
-- a cash/settlement position is legitimately allowed to be negative in a
-- research system. Customer deposit accounts always carry a floor.
-- =====================================================================

CREATE SEQUENCE customer_number_seq        START WITH 100000001 INCREMENT BY 1;
CREATE SEQUENCE deposit_account_number_seq START WITH 700000001 INCREMENT BY 1;

INSERT INTO ledger_account (id, code, name, account_type, normal_balance, currency, purpose, status)
SELECT md5('wbank.internal.cash.' || c.code)::uuid,
       '1000-CASH-' || c.code,
       'Cash and settlement position (' || c.code || ')',
       'ASSET', 'DEBIT', c.code, 'INTERNAL_CASH', 'ACTIVE'
  FROM currency c;

INSERT INTO ledger_account (id, code, name, account_type, normal_balance, currency, purpose, status)
SELECT md5('wbank.internal.suspense.' || c.code)::uuid,
       '1900-SUSPENSE-' || c.code,
       'Suspense / unidentified items (' || c.code || ')',
       'ASSET', 'DEBIT', c.code, 'INTERNAL_SUSPENSE', 'ACTIVE'
  FROM currency c;

-- Every ledger account must have a balance projection row from birth, so
-- that a posting can never be the thing that creates it.
INSERT INTO ledger_account_balance (ledger_account_id, currency, min_balance_minor)
SELECT a.id, a.currency, NULL
  FROM ledger_account a
 WHERE a.purpose <> 'CUSTOMER_DEPOSIT';
