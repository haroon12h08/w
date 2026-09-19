# W — The Double-Entry Ledger

Status: research implementation, phase 1. Schema: Flyway `V1`–`V6`. Code: `backend/src/main/java/com/wbank/ledger`.

This document explains what the ledger is for, which properties it guarantees, where
each guarantee is enforced, and what it deliberately does not attempt. It is written to
be argued with. Every design decision below is a position we can revisit, not a
convention adopted by default.

---

## 1. The question we started from

> What must be mathematically and operationally true for a banking ledger to represent
> financial value correctly?

Our answer, in order of importance:

1. **Value is only ever moved, never created or destroyed by bookkeeping.** Every
   recorded change has an equal and opposite counterpart.
2. **Every number the bank reports is a consequence of recorded facts.** Nobody can
   assert a balance. A balance is derived from a history.
3. **Recorded facts are permanent.** A mistake is corrected by recording another fact,
   and both remain visible.
4. **A fact is recorded completely or not at all.** No observer may ever see half a
   transaction.
5. **A request that is delivered twice has one effect.**
6. **Amounts are exact**, carry their currency, and are never silently rounded.
7. **Properties 1–6 hold under concurrency**, and they hold even when someone bypasses
   the application.

The rest of this document describes how each of these is made true, and how true it is.

---

## 2. Why the ledger is the financial source of truth

A bank's obligations are claims that someone can dispute: "you owe me €1,240.17". A
dispute can only be settled by *explaining* the number, which means producing the
sequence of events that led to it. A system that stores only the number cannot explain
it. A system that stores the events can reproduce the number, and can also show that
it reproduces it.

The ledger is therefore the **only** authoritative financial record in W. Everything
else is either an input to it or a projection of it:

| Thing | Role | Authoritative? |
|---|---|---|
| `journal_entry` + `posting` | the financial facts | **yes** |
| `ledger_account_balance` | cached projection of the postings | no (verified against postings) |
| `deposit_account` | a customer contract that *owns* a ledger account | no financial state at all |
| `audit_event` | provenance of the operation | no (explains, does not define, value) |

There is exactly one write path for money: `LedgerService.post` (plus
`LedgerService.reverse`, which builds a journal and posts it the same way). Deposits,
withdrawals, transfers and manual adjustments all express themselves as a balanced
`JournalEntryRequest` and hand it to that path. Because no second way to move money
exists, the invariants below are enforceable at all.

## 3. Why balances are derived, not stored

If a balance is a mutable column, then the system contains two independent claims
about the same quantity, the balance and the history, and nothing forces them to
agree. Every bug, manual fix or partial failure becomes a silent divergence.

In W:

- The **definition** of an account's balance is the signed sum of its postings, in the
  direction of the account's normal balance:
  `balance(a) = Σ amount·(+1 if posting.direction = a.normal_balance else −1)`.
- `ledger_account_balance` exists **only for performance**. Summing an account's
  entire history on every authorisation check does not scale. The projection is
  updated in the same database transaction as the postings it summarises, so it
  cannot lag.
- The database does not trust the projection (V6). It verifies it by induction:
  - **Chain rule, checked when each posting is inserted.** For each account, postings
    carry `account_sequence` 1, 2, 3, … with no gaps, and
    `balance_after(n) = balance_after(n−1) + effect(n)`, where `balance_after(0) = 0`.
    A forged running balance is rejected.
  - **Tip rule, checked at COMMIT.** `ledger_account_balance.balance_minor` must equal
    `balance_after` of the posting at `last_sequence`, and no posting may exist beyond
    `last_sequence`.
  - Together these make the projection equal to the sum of the postings. The only way
    to change a balance is to append the postings that justify the change.
    `UPDATE ledger_account_balance SET balance_minor = …` fails at commit, even from
    `psql`.
- `GET /api/v1/ledger/accounts/{id}/balance` returns both the projection
  (`balanceMinor`) and a from-scratch recomputation (`ledgerDerivedBalanceMinor`), so
  any client can check the claim. `GET /api/v1/ledger/reconciliation` does the same
  for every account at once.

## 4. Why double-entry is a useful system invariant

Double-entry is usually taught as an accounting convention. We use it as a
**conservation law** that a machine can check:

> For every journal entry, Σ debits = Σ credits.

Consequences that follow for free:

- **Conservation.** Within one currency, the signed sum of all postings is zero, at all
  times. One `SELECT SUM(signed_amount_minor) … GROUP BY currency` verifies the whole
  ledger (`LedgerReconciliationService.conservationOfMoney`).
- **The accounting equation holds by construction.**
  Assets + Expenses = Liabilities + Equity + Revenue over any closed set of accounts.
  The randomised test in §8 asserts it.
- **Every movement names its counterpart.** A customer's money cannot "appear". A cash
  deposit is *debit bank cash (asset ↑), credit customer deposit (liability ↑)*, which
  records that the bank now holds cash *and owes it*.
- **Errors become visible.** Any bug that moves value on one side only breaks an
  equality that is checked at commit.

### How debit and credit are modelled

- A posting stores a **positive** `amount_minor` and a `direction ∈ {DEBIT, CREDIT}`.
  The sign lives in the direction, never in the amount (`CHECK amount_minor > 0`).
  This keeps "total debited to account X" and "total credited" directly queryable,
  which statements and regulatory reports need, and it removes the ambiguity of
  negative debits.
- `signed_amount_minor` is a PostgreSQL **generated column** (+amount for debits,
  −amount for credits), so "the entry balances" is a single SQL predicate:
  `SUM(signed_amount_minor) = 0`.
- Each account has a `normal_balance` that is **derived from its type**, not chosen
  freely: ASSET/EXPENSE → DEBIT; LIABILITY/EQUITY/REVENUE → CREDIT. A CHECK
  constraint pins this. Balances are expressed in the normal direction, so
  "balance ≥ floor" means the same thing for a cash asset and a customer deposit
  liability. The rule lives in one place (`PostingDirection.signumFor`) and in one
  trigger.
- **A customer's money is a liability of the bank.** A customer "balance of 100" is a
  credit balance of 100 on a LIABILITY account.

## 5. Monetary precision

| Decision | Reasoning |
|---|---|
| Amounts are `long` counts of **minor units** (`BIGINT` in SQL) | Integer addition is exact, associative and total. `float`/`double` cannot represent 0.10 and are banned. `BigDecimal` alone permits scale drift (1.5 vs 1.50) and carries no currency. |
| Every amount carries its **currency** (`Money(minorUnits, CurrencyUnit)`) | A number without a currency is not money. Mixed-currency arithmetic throws. |
| A currency's **scale** (`minor_unit`: USD 2, JPY 0) is reference data in the `currency` table | The ledger must not depend on JDK locale data. V6 makes code and scale immutable, because changing a scale would silently re-value every historical amount. |
| Major→minor conversion uses `RoundingMode.UNNECESSARY` | `10.005 USD` and `100.5 JPY` are **rejected**, not rounded. Rounding is a business decision (who gets the half cent?) and belongs to whoever generates the amount, never to the ledger. Trailing zeros (`10.500`) are not extra precision and are accepted. |
| All arithmetic uses `Math.*Exact`; oversize inputs are rejected | Overflow throws. It never wraps. A `long` covers about 9.2×10¹⁶ cents, far beyond any real balance, but the ledger still fails loudly. |
| The HTTP API takes amounts as **decimal strings** (`"125.50"`) matching `^[0-9]{1,19}(\.[0-9]{1,19})?$` | A JSON number may pass through a binary double in some intermediary. No sign, exponent or `NaN` is accepted. |
| No sub-minor-unit precision | We cannot yet represent interest accruals like 0.0037 cents. See §10. |

## 6. Atomicity and idempotency

### Atomicity

- A journal entry is the **unit of financial atomicity**. The entry row, all of its
  postings, the projection updates and the audit event are written in **one PostgreSQL
  transaction**.
- `LedgerService.post` is `@Transactional(propagation = MANDATORY)`. It refuses to run
  without a caller's transaction, so the caller's own bookkeeping (for example a
  deposit account's state) commits together with the money movement or not at all.
  `JournalService` and `DepositAccountService` are the transaction owners. A test
  asserts that posting outside a transaction is refused.
- All admissibility checks (account status, currency, balance floors) run **before
  anything is written**. That is an efficiency choice, not the safety argument.
- The safety argument is the transaction itself. If anything fails at any point, even
  after the postings are flushed, PostgreSQL discards everything. The tests show this
  in three ways:
  - A rollback after a successful `post` leaves no entry, no posting, no balance
    change and no audit row, and the idempotency key remains usable.
  - A journal whose *last* leg breaches a floor leaves its earlier legs unapplied.
  - Raw-SQL partial journals (zero or one leg, unbalanced legs, postings with no
    projection update) are rejected at COMMIT, and nothing persists.
- Nothing is ever visible as a "partially posted" state. There is no `PENDING` status
  on the ledger (see §10). Under PostgreSQL MVCC, other sessions see an entry either
  complete or not at all.

### Idempotency

The client supplies an idempotency key (`Idempotency-Key` header; required on
`POST /ledger/entries`). The guarantee is:

> For a given key, at most one journal entry ever exists, and a replay returns that
> entry only if it is **the same request**.

How it is enforced, in layers:

1. **Fingerprint.** `JournalEntryRequest.fingerprint()` is a SHA-256 over the entry's
   financial meaning: type, currency, value date, description and legs in order. It is
   stored in `journal_entry.request_fingerprint`. The same key with the same
   fingerprint is a **replay**: the original entry is returned, with
   `replayed = true`, HTTP 200 and `Idempotent-Replayed: true`. The same key with a
   different fingerprint is a **client error**, rejected with 409
   `ledger.idempotency_key_reused`. Returning the old entry in that case would tell
   the client that its *new* request succeeded. Provenance (actor, correlation id) is
   excluded, because a legitimate retry may carry a fresh correlation id.
2. **Serialisation.** Before looking up the key, the transaction takes
   `pg_advisory_xact_lock(hashtextextended(key))`. Concurrent submissions of one key
   queue on that lock. Each waiter proceeds only after the previous holder has
   committed or rolled back, and under READ COMMITTED its next statement sees that
   outcome, so it replays instead of failing. A hash collision only serialises two
   unrelated keys.
3. **Database backstop.** A partial unique index on `journal_entry(idempotency_key)`
   and a CHECK requiring a fingerprint whenever a key is present make a duplicate
   impossible even if the application is bypassed.
4. **Rollback does not consume a key.** The key lives on the journal entry itself, so
   a failed attempt leaves nothing behind and the retry succeeds.

The concurrency test submits one key from 32 threads at once. Exactly one entry is
created, and every caller receives the same entry id. With the advisory lock removed,
this test fails. We checked this by mutation.

Reversals are idempotent by a different route. An entry can be reversed at most once
(a partial unique index on `reverses_entry_id`, plus a row lock on the original), so
repeating a reversal is a 409, not a second effect.

## 7. Concurrency

- **Read-decide-write under a lock.** Posting takes `SELECT … FOR UPDATE` on every
  affected `ledger_account_balance` row before checking floors, so two withdrawals
  cannot both see the same pre-withdrawal balance. Optimistic locking would detect
  the conflict too, but it would turn contention into retries. For a hot account,
  pessimistic locking is the simpler correct choice.
- **Deadlock freedom.** Locks are taken in ascending account-id order, whatever the leg
  order in the request. The test runs 100 concurrent transfers in both directions
  between the same pair of accounts, with varied leg orders.
- **Isolation level.** READ COMMITTED plus explicit locks. SERIALIZABLE would also be
  correct but would add retry handling everywhere. The advisory-lock replay argument
  in §6 relies on READ COMMITTED. Under REPEATABLE READ the waiter would hit the
  unique index instead, which is still safe (409), just less convenient.
- **Evidence.**
  - 50 concurrent 3.00 withdrawals from a 100.00 account: exactly 33 succeed, 17 are
    refused, the final balance is 1.00, and no running balance ever goes below zero.
  - Removing the row lock makes both balance-concurrency tests fail. We checked this
    by mutation.
  - 8 concurrent reversals of one entry produce exactly 1 reversal.

## 8. Where each invariant is enforced

"App" means rejected by Java before any write, with a precise error. "DB" means
PostgreSQL rejects it even when the application is bypassed. Every DB row below has a
test that writes raw SQL with exactly one defect and asserts that the named constraint
fired (`LedgerDatabaseConstraintsIntegrationTest`).

| Invariant | App | DB mechanism |
|---|---|---|
| Entry balances (Σ debits = Σ credits) | `JournalEntryRequest` | deferred trigger `posting_entry_must_balance` |
| Entry has ≥ 2 postings | `JournalEntryRequest` | `posting_entry_must_balance` + deferred `journal_entry_must_have_postings` (V6, catches 0 legs) |
| Declared total = Σ debits | computed, not supplied | `posting_entry_must_balance` |
| Amount > 0; sign only in direction | `PostingInstruction`, `Money` | `CHECK posting_amount_positive`, `journal_entry_total_amount_positive` |
| Amount within scale, no rounding | `CurrencyUnit.toMinorUnits` | integer column (no fractional minor units) |
| No overflow | `Math.*Exact` | `BIGINT` range |
| One currency per entry; account currency = posting currency = entry currency | `JournalEntryRequest`, `LedgerService.resolveAccounts` | composite FKs `posting_currency_matches_entry` / `_account`; reversal currency FK (V6) |
| Currency scale is immutable | — | `currency_guard` (V6) |
| Normal balance follows account type | `LedgerAccountType` | `CHECK ledger_account_normal_balance_matches_type` |
| Account identity (type/currency/code) is immutable; accounts are never deleted | JPA `updatable=false` | `ledger_account_guard` (V6) |
| Postings only on ACTIVE accounts | `LedgerService` | `posting_account_postable` (V6) |
| Postings are append-only | no update API | `posting_is_append_only` |
| Journal entries are immutable except POSTED→REVERSED, once, pointing at a real reversal | `JournalEntry.markReversedBy` | `journal_entry_guard` (tightened in V6) |
| An entry is reversed at most once | `LedgerService.reverse` + row lock | partial unique index `journal_entry_reverses_unique` |
| Running balance chain is gap-free and arithmetically correct | `LedgerAccountBalance.apply` | `posting_chain_guard` (V6) |
| Projection = tip of chain; cannot be set, rewound or deleted | no setter exists | deferred `balance_must_match_ledger`, `balance_guard`, `posting_must_be_projected`, `CHECK balance_count_matches_sequence` (V6) |
| Customer deposit cannot breach its floor | `LedgerAccountBalance.wouldBreachFloor` | `CHECK balance_respects_floor` |
| Idempotency key → at most one entry; key requires a fingerprint | advisory lock + fingerprint comparison | unique index + `CHECK journal_entry_fingerprint_with_key` (V6) |
| Provenance (actor, correlation, source operation) present | `OperationContext` | `NOT NULL` columns |

**Enforced only in application code, by design or for now:**
- *Same key and different payload is a conflict.* The database guarantees one entry per
  key. The comparison of payloads happens in Java.
- *Only `ADJUSTMENT` entries may be posted through the ledger API, and customer
  deposit ledger accounts may only be opened by the deposit module.* These are
  authority rules, not arithmetic.
- *Account status checks for deposit products (frozen or closed).* These are mirrored
  in the database only as "the ledger account is ACTIVE".
- *Ledger-wide conservation per currency.* This follows from per-entry balance, and is
  verified on demand by `LedgerReconciliationService`, not by a constraint.

The application checks exist so that callers get a precise error. The database checks
exist so that a bug, a migration or a person with `psql` cannot corrupt the ledger.
If the database layer ever fires in normal operation, it has caught an application
bug, and `GlobalExceptionHandler` logs it at ERROR level.

## 9. Test evidence

61 tests: 21 JUnit test classes (nested classes counted separately), run by
`mvn test`. Every database test runs against a disposable **PostgreSQL 16
Testcontainer** with the real Flyway migrations. Ledger integration tests **do not**
use a rolled-back test transaction: deferred constraints only fire at COMMIT, so a
rolled-back test cannot observe them.

| Required property | Where |
|---|---|
| 1 valid balanced journal posts | `LedgerInvariantsIntegrationTest.ValidPosting` |
| 2 unbalanced rejected | `…Unbalanced`, `LedgerDatabaseConstraintsIntegrationTest.unbalancedJournalIsRejectedAtCommit` |
| 3 invalid amounts rejected | `…InvalidAmounts`, `…zeroOrNegativeAmountsAreRejected`, `LedgerApiIntegrationTest.invalidRequests…` |
| 4 currency mixing rejected | `…Currencies`, `…postingCurrencyMustMatchTheAccountAndTheEntry` |
| 5 no partial state after failure | `…Atomicity`, raw partial-journal tests |
| 6 duplicates have one effect | `…Idempotency`, `LedgerConcurrencyIntegrationTest.concurrentDuplicatesProduceOneEffect` |
| 7 history not modifiable; corrections by reversal | `…Corrections`, `…postedHistoryCannotBeModifiedOrDeleted`, `…aBalanceCannotBeSetWithoutPostingsThatJustifyIt` |
| 8 derived balances consistent | `…DerivedBalances`: 150 random multi-leg journals over 10 accounts of all 5 types, checked against an independent Java model, SQL recomputation, the posting chain and the accounting equation |
| 9 concurrency | `LedgerConcurrencyIntegrationTest` (4 scenarios, mutation-checked) |
| 10 DB constraints | `LedgerDatabaseConstraintsIntegrationTest` (13 tests, including a positive control) |

Writing these tests found a real defect in this phase's own migration. The first
version of the fingerprint CHECK read `key IS NOT NULL AND fingerprint ~ '…'`. With a
NULL fingerprint that expression is NULL, and **a CHECK that evaluates to NULL
passes**. The raw-SQL test caught it before the migration shipped.

## 10. Unresolved assumptions and open questions

- **Value date versus booking time.** Both are recorded, but nothing constrains value
  dates. Back-dating and future-dating are allowed, and no period is ever closed.
- **Balance floors are enforced at booking time only.** Changing an overdraft limit
  does not re-examine existing balances. Whether a floor may be lowered below the
  current balance is a product rule we have not decided.
- **Internal accounts have no floor.** The cash position can go negative. This is fine
  for research, but not how nostro or vault cash behaves.
- **The reversal floor policy is a choice.** A reversal that would overdraw a customer
  is refused. A real bank might post it and create an arrears or receivable position
  instead.
- **Idempotency keys are global and kept forever.** There is no scoping per client and
  no expiry.
- **Per-account locking serialises one account's postings.** This is correct, but a
  single hot internal account (for example cash) is a throughput ceiling. We have not
  measured it.
- **The deferred balance-check trigger is O(legs²) per entry**, because it fires per
  posting row. This is irrelevant at the 100-leg cap, but it is not free.
- `idempotency_record` (V4) is **unused**. It is kept as a reservation for HTTP-level
  response caching. The ledger's idempotency does not depend on it.
- The existing dev database contains rows written before V6. The V6 triggers validate
  only new writes and do not retroactively verify old rows. Reconciliation does.

## 11. What this implementation deliberately does NOT model

- **Multi-currency transactions or FX.** An entry is single-currency. A cross-currency
  payment would need two entries linked through FX position accounts, plus rates.
- **Pending, authorised or held funds, or two-phase posting.** There is no
  reservation state. A card authorisation or an in-flight payment cannot be
  represented yet.
- **Accruals, interest and fees**, and any sub-minor-unit precision.
- **Accounting periods and closing**, trial balance and financial statements (IFRS or
  GAAP), sub-ledger to general-ledger roll-up, or a real chart of accounts.
- **Authentication and authorisation, maker-checker, or segregation of duties.** The
  actor is taken from a header and is not verified.
- **Tamper evidence against a database superuser.** A superuser can disable triggers.
  There is no hash chain, WORM storage or external anchoring.
- **Regulatory concerns**: statements, KYC/AML, sanctions screening, reporting.
- **Distribution**: replication, sharding, disaster recovery, cross-service
  transactions.
- **Event sourcing, message brokers, agents or AI.** These are out of scope by
  instruction, and the ledger does not depend on them.

## 12. What a production banking ledger would still require

Passing these tests does **not** make this a banking-grade ledger. At minimum, a
production system would still need:

- pending and posted balance separation, holds, and two-phase (authorise, then settle)
  flows
- FX, multi-currency positions and revaluation
- period close, trial balance, GL mapping and reconciliation against external
  statements (nostro, card schemes, payment rails)
- strong authentication, fine-grained authorisation, dual control on manual journals
  and reversals, and immutable audit export
- cryptographic tamper evidence, backups with point-in-time recovery, tested disaster
  recovery, and database roles that cannot disable triggers
- idempotency key scoping and retention policy
- load and soak testing of hot accounts, and possibly sharded or bucketed balances
- formal review against the applicable regulatory and audit regime
