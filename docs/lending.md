# W — Credit and Lending

Status: research implementation, phase 4. Schema: Flyway `V9`. Builds on [domain.md](domain.md) §5 (the minimum loan).
Code: `obligation` package (`LoanService`, `LoanServicingService`, `domain/*`).

This phase establishes the **mechanics** of lending: how a loan comes into existence, how
money and interest flow, how what was expected is compared with what happened, and how
all of it reaches the ledger. There is **no credit scoring and no risk model**. A later
intelligence layer will learn from the data these mechanics generate, so this phase
optimises for data that is trustworthy, explainable and complete.

---

## 1. Why a loan is an obligation, not an account

An account answers "how much is in this position now?". A loan must also answer questions
an account balance cannot:

- Who owes whom? The bank (a party) is the creditor; the borrower (a party) is the
  debtor.
- On what terms: principal, rate, term, frequency, method?
- What is owed **in the future**, and when?
- Which of those future promises have **already fallen due**, and were they kept?

A loan is therefore modelled as an `obligation` (the contract), with `loan_terms`, a
contractual `obligation_installment` schedule, and a history of repayments, allocations,
accruals, waivers, credit decisions and delinquency events. The loan's *position
account* holds only the principal outstanding, in the ledger. Two loans with the same
outstanding principal can be in completely different situations: one current, one 120
days past due. That difference exists only in the obligation.

## 2. Separated concepts

| Concept | Record | Nature |
|---|---|---|
| Loan contract | `obligation` + `loan_terms` | who, what currency, how much, at what rate, for how long, under which versioned servicing rules. Terms are immutable. |
| Credit decisions | `loan_decision` | APPROVED / DECLINED / DEFAULT_DECLARED: who decided, when, why, and on what evidence (append-only) |
| Loan obligation (expected) | `obligation_installment` | the contractual schedule: what is owed and when. Immutable once originated. |
| Repayment (actual) | `obligation_repayment` + `repayment_allocation` | money received, and how the allocation policy interpreted it against the schedule (append-only) |
| Interest earned | `interest_accrual` | interest recognised at each period end (append-only, one per instalment) |
| Interest forgone | `installment_waiver` | scheduled interest never charged because the loan was settled early (append-only) |
| Delinquency history | `delinquency_event` | every change of delinquency bucket, with the overdue amounts observed (append-only) |
| Ledger posting | `journal_entry` / `posting` | the accounting effect of disbursement, accrual and repayment |

A **repayment is not its accounting entries**. "The borrower paid 300.00 against loan L on
15 Feb" is one fact. "Dr deposit 300.00; Cr loan principal 200.00; Cr interest receivable
100.00" is its accounting consequence, derived through a versioned allocation rule.
Both are stored, and they point at each other.

## 3. Lifecycle

```
  PROPOSED --approve--> APPROVED --disburse--> ACTIVE --fully satisfied--> SETTLED
     |   \                 |                     |                            ^
     | decline           cancel        declare default (≥ 90 DPD)             |
     v      v              v                     v                            |
 CANCELLED DECLINED    CANCELLED            DEFAULTED --fully satisfied-------+
```

- **PROPOSED** is the application: requested terms, a PENDING loan position, no money,
  and no schedule.
- **APPROVED** is a recorded credit decision (`loan_decision`), with the terms and
  instalment amount as evidence. There is no scoring: the decision is supplied by a
  human or system actor, with a rationale.
- **ACTIVE** begins at **origination and disbursement, which are one operation.**
  Single-tranche loans are funded on the day the agreement is executed, so there is no
  observable ORIGINATED-but-undisbursed state. That state would be added with delayed
  or multi-tranche disbursement. Disbursement:
  - fixes the schedule from that day
  - activates the loan position and opens its interest-receivable ledger account
  - posts the principal
- **DEFAULTED** is a *declared* state: a credit decision permitted only when the loan is
  at least 90 days past due (observable fact), and recorded with its evidence. It has no
  ledger effect in this model (see §9), and repayments are still accepted.
- **SETTLED** requires that every instalment is satisfied: principal paid, and interest
  paid or waived. The loan position and the receivable ledger account are then closed.
- **DECLINED and CANCELLED** close the PENDING position. No money ever moved.

Transitions are enforced by `ObligationStatus` and again by the `obligation_guard`
trigger. The API offers only named actions: `/approve`, `/decline`, `/cancel`,
`/disburse`, `/repayments`, `/default`, `/servicing`. There is no endpoint that edits
principal, rate, schedule, balances or repayment status.

## 4. Mathematical assumptions (explicit and deliberately narrow)

| Question | Answer in this model |
|---|---|
| Rate | Fixed nominal annual rate in integer basis points (0–10 000). |
| Frequency | Monthly. Due dates are the disbursement date + k months, month-end clamped and computed from the start (no drift). |
| Day count | **None; every month is one equal period** (the periodic rate is annual / 12, like 30/360). Actual calendar days never enter an interest calculation. |
| Simple or compound? | Interest is **simple within a period** on the outstanding scheduled principal. It is **never charged on unpaid interest**, and overdue amounts do not bear interest. The annuity formula compounds monthly only in the sense that each period's interest is on the principal remaining after the previous period. |
| Amortisation | Annuity (level instalment) A = P·r / (1 − (1 + r)^−n), rounded half-up to a minor unit. Each period's interest is outstanding × r, rounded half-even. The final instalment repays exactly the remaining principal. Σ principal = P exactly. (domain.md §5 covers the rounding analysis.) |
| Rounding | All arithmetic is `BigDecimal` (34 significant digits) over integer minor units. Rounding is explicit and happens only in schedule generation. The ledger never rounds. |
| Partial periods | **None.** Interest for a period is earned in full on its due date and not before. There is no daily accrual and no broken-period interest. |
| Early repayment | Only the exact **early-settlement amount** may exceed what is due: everything due, plus the principal of every future instalment. The interest of periods not yet ended is **waived** and recorded. Partial prepayment is refused, because it would require re-amortising an immutable schedule. |
| Overdue amounts | Stay owed at their contractual amounts. No penalty interest, no late fees, no grace period. They drive delinquency. |
| Paying early within a period | Not possible except through full settlement: nothing is payable before its due date. |
| Currency | One currency per loan, equal to the settlement account's. |

These assumptions are simple on purpose. Each one is a lever that a real product would
set differently: daily actual/365 accrual, prepayment with re-amortisation, late fees,
grace periods. They are isolated so they can be changed one at a time.

## 5. Contractual state versus accounting state

| Quantity | Contractual (domain) | Accounting (ledger) | Enforced equal? |
|---|---|---|---|
| Outstanding principal | principal − Σ principal repaid | loan position balance (ASSET, `LOAN_RECEIVABLE`) | **yes**: at commit (`wbank_check_obligation`) and at runtime |
| Accrued, unpaid interest | Σ accruals − Σ interest repaid | interest receivable balance (ASSET, `LOAN_INTEREST_RECEIVABLE`, one per loan) | **yes**: same two places |
| Interest earned to date | Σ accruals | credits to `4000-INTEREST-INCOME-<ccy>` from `INTEREST_ACCRUAL` entries | yes, by construction: each accrual record carries its entry; reconciliation test |
| Scheduled future interest | Σ interest due − paid − waived | **not in the ledger**: it has not been earned | n/a |
| Overdue amounts, days past due | derived from schedule vs history | not an accounting concept | n/a |

This is the answer to "what is the loan balance?". **There is no loan balance field.**
The authoritative financial values (principal outstanding, interest receivable) are
ledger balances. The contractual values are derived from immutable schedule and
append-only history. PostgreSQL refuses any commit where the two disagree. A test
confirms this: moving the receivable through the raw ledger primitive, bypassing
lending, makes the next loan operation refuse to proceed.

### Accrued interest versus paid interest

This phase moved new loans from phase 2's **cash basis** (income on receipt) to
**accrual** (income when earned):

| Event | Journal |
|---|---|
| Disbursement | Dr loan principal (asset) / Cr borrower deposit (liability) |
| Period end (servicing or repayment) | Dr loan interest receivable / Cr interest income, value-dated on the due date |
| Repayment | Dr borrower deposit / Cr loan principal (principal part) / Cr interest receivable (interest part) |
| Early settlement | as repayment. Waived interest has **no** journal: it was never accrued. |

The difference matters. Under accrual, interest income reports what the bank *earned*,
whether or not it was paid. Accrued-but-unpaid interest is a visible asset whose growth
is itself a signal. Accruals are **idempotent**: at most one per instalment (UNIQUE),
with a deterministic ledger key. They are posted by the end-of-day servicing sweep, and
also inside any repayment before allocation, so money is always applied to interest
that has already been recognised. `service(asOf)` refuses future dates, so interest is
never recognised before it is earned.

Loans written before V9 keep **V1 / CASH** (stored on their terms). They are serviced
under the rules they were written under, and the migration test proves that.

## 6. Repayment allocation

The policy is **explicit and versioned**. Its name is stored on the loan terms, fixing
the rule for the life of the loan, and on every repayment.

**`V2_DUE_ONLY_INTEREST_THEN_PRINCIPAL_FULL_PAYOFF`** (all new loans):

1. Accrue the interest of every period that has ended.
2. Only instalments whose due date has arrived are payable. Work through them oldest
   first; within each, pay interest, then principal. There are no fees in the model, so
   the classic "fees → interest → principal" order reduces to interest → principal.
3. An amount **above what is due** is accepted only if it equals the early-settlement
   amount exactly. Then every future instalment's principal is paid and its interest
   waived.
4. Anything else above what is due is refused: 422 `loan.prepayment_not_supported`, or
   `loan.overpayment` above the settlement amount. No record and no ledger effect.

**`V1_OLDEST_FIRST_INTEREST_THEN_PRINCIPAL`** (pre-V9 cash-basis loans): as in phase 2.

Paying interest before principal, oldest first, is the standard creditor-protective
order. It keeps the oldest arrears smallest, which is also what makes days-past-due
fall when a borrower catches up.

## 7. Expected versus actual; delinquency; default

The **schedule** is what was expected. The **repayment history** is what happened. They
live in different tables and neither is ever rewritten, which is the precondition for
later asking "were our expectations (and decisions) right?".

**Delinquency** is derived from observable facts only (`Delinquency.evaluate`):

- An instalment is **overdue** from the day after its due date while any of it is
  unpaid. Paying on the due date is on time.
- **Days past due** is measured from the *oldest* unpaid due date.
- **Buckets:** CURRENT, 1–29, 30–59, 60–89, 90+.
- Evaluated as of any date, together with the overdue principal and interest.
- Each bucket change is appended to `delinquency_event`, by the servicing sweep and
  after repayments. The history reads like `CURRENT→DPD_1_29→DPD_30_59→CURRENT`.
- **No probabilities or predictions are stored.**

**Default** is a *decision*, distinct from delinquency, which is a *fact*.

- It is permitted only at ≥ 90 DPD. The ≥ 90 DPD threshold is the usual regulatory
  backstop, reduced here to one explicit rule.
- It is recorded in `loan_decision` with the delinquency evidence at decision time.
- It is not automatic: the sweep reports 90+ DPD but does not declare default.

## 8. Transactions, idempotency, concurrency

- **Every loan operation is one transaction** across contract, schedule, accruals,
  repayments, waivers, decisions, accounts, ledger and audit.
  - An outer transaction that fails after a disbursement leaves: the loan APPROVED, no
    schedule, the position PENDING, no ledger accounts and no journals.
  - An outer transaction that fails after a repayment leaves no repayment, and **no
    accrual either**, even though that repayment had posted one.
- **Servicing** runs each loan in its own transaction. One corrupted loan does not stop
  the sweep; it is counted as a failure and logged.
- **Idempotency:**
  - Disbursement: a deterministic ledger key; re-disbursing an ACTIVE loan returns it.
  - Accrual: UNIQUE per instalment, plus a deterministic key.
  - Repayments: a client key (required over HTTP), looked up *after* the loan's row
    lock. Same key and amount replays; same key and a different amount is 409.
- **Concurrency:**
  - The obligation row lock serialises all operations on one loan.
  - Settlement-account debits respect holds (payments phase) under the account's
    balance lock.
  - Test: 10 concurrent 100.00 payments against 888.49 due gives exactly 8 accepted;
    the 9th would be a partial prepayment.
  - Removing the row lock makes that test fail (mutation-checked).

## 9. Invariants established in this phase

In addition to every earlier invariant (the full suite runs on each change):

| Invariant | Application | PostgreSQL |
|---|---|---|
| Lifecycle transitions; recorded facts (approval, activation, default, receivable link) immutable | `ObligationStatus`, `Obligation` | `obligation_guard`, `obligation_lifecycle_fields` |
| ACTIVE/DEFAULTED/SETTLED ⇒ approved, disbursed through a `LOAN_DISBURSEMENT` | `LoanService` | `wbank_check_obligation` |
| Ledger principal = principal − principal repaid | `assertLedgerAgrees` | `wbank_check_obligation` |
| Ledger interest receivable = Σ accruals − interest repaid | `assertLedgerAgrees` | `wbank_check_obligation` |
| One accrual per instalment, equal to its scheduled interest, dated no earlier than its due date | `accrueUpTo` | UNIQUE + `wbank_check_obligation` |
| No instalment over-satisfied: paid + waived ≤ due | `AllocationPolicy` | `wbank_check_obligation` |
| SETTLED ⇔ every instalment satisfied | `isFullySatisfied` | `wbank_check_obligation` |
| Cash-basis loans have no accruals or waivers | policy dispatch | `wbank_check_obligation` |
| Allocation policy and interest recognition fixed per loan and mutually consistent | `LoanTerms` | CHECKs + immutable `loan_terms` |
| Decisions, accruals, waivers, delinquency events append-only | `@Immutable` | `wbank_forbid_mutation` |
| Default only at ≥ 90 DPD | `LoanService.declareDefault` | **application only** (date logic) |
| Nothing payable before due, except exact early settlement | `AllocationPolicy.V2` | **application only** |
| Manual journals cannot touch loan principal or interest receivable | `JournalService` (product positions) | detected by the next loan operation |

A real defect was found while verifying against the dev database, not by the test
suite. V9's backfill `UPDATE` queued deferred constraint events, and PostgreSQL
refuses `ALTER TABLE` while events are pending. Empty test databases could not reveal
it. It was fixed with `SET CONSTRAINTS ALL IMMEDIATE` after the backfill, and is now
covered by `V9LegacyLoanMigrationTest`, which builds a consistent pre-V9 loan and
upgrades it.

## 10. Simplified or out of scope

- **Interest and pricing:**
  - no day-count conventions, daily accrual, variable or floating rates, or rate resets
  - no fees of any kind, no penalty interest, no grace periods
- **Repayment options:** no partial prepayment, re-amortisation, restructuring,
  payment holidays, or term extension.
- **Disbursement:** no multi-tranche or delayed disbursement, and no separate ORIGINATED
  state.
- **After default:**
  - no impairment, expected-credit-loss provisioning (IFRS 9 / CECL), or stage
    allocation
  - no non-accrual status: a defaulted loan keeps accruing interest here, where a real
    bank would usually stop
  - no write-off, recovery, collections workflow, or collateral
- **Delinquency** uses calendar days, with no business-day calendar and no
  holiday-adjusted due dates.
- **Lending products:** loans are always to customers from the bank. There are no
  syndication, participations, guarantees, or co-borrowers.
- **Credit decisions** are recorded but not *made* by the system: no scoring, no
  affordability model, no limits framework.
- **The legacy V1 cash-basis repayment path** is preserved for pre-V9 loans and verified
  by the migration test only at the data level. Its repayment behaviour is no longer
  exercised by an integration test.
