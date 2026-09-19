# W — The Minimum Banking Domain

Status: research implementation, phase 2. Schema: Flyway `V7`. Builds on [ledger.md](ledger.md).
Code: `party`, `customer`, `product`, `account`, `deposit`, `obligation` packages.

Phase 1 established one authoritative record of value: the ledger. The ledger knows
*how much* sits in which account and why it moved. It does not know *whose* money it
is, *what promise* it represents, or *what is still owed*. This phase adds the smallest
set of concepts needed to give the ledger's numbers economic meaning, without
creating a second source of financial truth.

---

## 1. The concepts and what each one means

| Concept | Economic meaning | Holds money? | Relationship to the ledger |
|---|---|---|---|
| **Party** (Person, Organization) | A legal entity in the world that can hold rights and owe obligations. | no | none directly |
| **Customer** | The institution's *relationship* with one party: the decision to do business with them. | no | none directly |
| **Product** | A contract type the bank offers (a template of promises). | no | its *family* fixes the ledger nature of positions |
| **Account** | An instantiated position of a party under a product. | **no field**: its money is its ledger account | 1 : 1 with a ledger account, once active |
| **Currency** | Unit of account and its scale (phase 1). | — | every amount and ledger account is denominated in exactly one |
| **Obligation** | A contractual promise by a debtor party to pay a creditor party, with monetary terms over time. | no | its outstanding principal must equal its position's ledger balance |
| **Loan terms / schedule / repayment** | The loan-specific terms, what was *expected*, and what *happened*. | no | each repayment points to the journal entry that records it |

### Party versus Customer

A party is *who someone is*. A customer is *that the bank has agreed to deal with
them*. These are different facts, with different lifecycles:

- A party exists before the bank knows it and after the bank stops dealing with it.
  The bank itself is a party: it is the creditor on every loan. A payee or a
  guarantor can be a party without ever being a customer.
- The relationship has its own lifecycle (§3) and its own attributes, such as the
  contact email and when it was opened, activated or closed. Closing it must not erase
  the party's identity, which is needed for history, audit and later reasoning.
- Person and Organization are subtypes of Party. The distinction is structural
  (a person has a date of birth; an organization has a registration number), so they
  are separate tables. PostgreSQL pins each subtype row to its party's type
  (composite foreign key plus a commit-time check), so a party cannot be both or
  neither.
- There is **one relationship per party** (`UNIQUE(customer.party_id)`).

This replaces phase 1's `customer` table, which mixed identity (name, type, country)
with the relationship. V7 migrates existing rows into a party plus a relationship. A
dedicated migration test covers this.

### Product versus Account

A product answers "what kind of promise is this?". An account answers "whose
instance of that promise, in which currency, and in what state?".

- A product has no owner and no money. Its definition (code, family, whether an
  overdraft is allowed) is **immutable**. Changing it would silently rewrite the
  contract of every account already opened under it, so a new offering must be a new
  product. The only lifecycle action is `ACTIVE → WITHDRAWN`, which stops new sales
  while existing accounts continue.
- The product **family** is where business meaning meets accounting. A `DEPOSIT` is
  money the bank owes, so it is a `LIABILITY` ledger account. A `LOAN` is money owed
  to the bank, so it is an `ASSET` ledger account. `ProductFamily` encodes this
  mapping once, and a commit-time trigger re-checks it for every account.

### Account versus Ledger Account

| | Account | Ledger account |
|---|---|---|
| Question it answers | whose position, under which contract, may it be used? | how much value sits here, and why did it move? |
| Owner | a party, through a customer relationship | none: it is a place in the chart of accounts |
| Balance | **none stored** | derived from postings (phase 1) |
| Lifecycle | PENDING → ACTIVE ⇄ FROZEN; ACTIVE → CLOSED | ACTIVE / FROZEN / CLOSED, **mirrored** from the account |

- An account is born `PENDING` with **no ledger account**. Activation creates the
  ledger account, so money cannot move into a position that has not been accepted.
- From then on, status changes are applied to both the account and its ledger account
  in the same transaction. A commit-time trigger fails the transaction if they
  diverge, from either side.
- This closed a real hole in phase 1: a frozen or closed deposit account's ledger
  account stayed `ACTIVE`, so the ledger API could still post to it. V7 also
  repaired existing rows.
- The account stores the owner twice: `customer_id` (the relationship) and
  `owner_party_id` (the party). A composite foreign key to `customer(id, party_id)`
  makes divergence impossible. Storing the party explicitly lets obligations prove,
  with foreign keys, that the debtor owns both accounts they use.

### Financial position versus contractual obligation

A position is *what is*: the ledger balance of an account right now. An obligation is
*what was promised*: principal, rate, dates, counterparties, and a schedule of future
payments.

A loan is not "an account with a negative balance":

- The ledger balance of a loan position says how much principal is outstanding **now**.
  It says nothing about when payments are due, how much interest the contract
  demands, which instalments are late, or who the creditor is.
- Two loans with the same outstanding principal can be in very different economic
  situations: one current, one three instalments overdue. That difference exists only
  in the contract and its history.
- The contract carries information the ledger never will: parties, terms, the schedule
  (what was expected), repayments (what happened), and allocation (how each payment was
  interpreted against the contract).

## 2. Why obligations must be explicit

1. **Future obligations are real before any money moves.** A loan's schedule is a set
   of claims on future cash flows. The ledger records only past movements, so it cannot
   represent them.
2. **Expected versus actual.** Delinquency, prepayment, and the credit models of later
   phases all compare the schedule with the repayment history. If only the balance is
   stored, that comparison is impossible to reconstruct.
3. **Counterparties.** An obligation names its creditor and debtor as parties. The bank
   is an explicit party, not an implicit "us". This is the seed of the relationship
   graph later phases will need.
4. **Interpretation of money.** A 250.00 payment only becomes "10.00 interest and
   240.00 principal" through a contractual rule. That rule (`AllocationPolicy`, versioned
   and stored on each repayment) is part of the domain, not of the ledger.

## 3. Lifecycles

Transitions are enforced twice: once by the domain enums (`canTransitionTo`) with
`InvalidStateTransitionException` (HTTP 409), and once by a PostgreSQL `BEFORE UPDATE`
trigger per table. No API sets a status directly; every change is a named action.

```
Customer:   PENDING → ACTIVE ⇄ SUSPENDED;  {PENDING, ACTIVE, SUSPENDED} → CLOSED (terminal)
Account:    PENDING → ACTIVE ⇄ FROZEN;     PENDING → CLOSED;  ACTIVE → CLOSED (terminal)
Product:    ACTIVE → WITHDRAWN (terminal)
Obligation: PROPOSED → ACTIVE → SETTLED;   PROPOSED → CANCELLED (both terminal)
```

Choices worth noting:

- **FROZEN cannot go directly to CLOSED.** A hold must be lifted deliberately, not
  dodged by closing the account.
- **SUSPENDED blocks new business only.** Existing accounts keep working. Freezing is
  the per-account control.
- **Closing requires emptiness.**
  - An account needs a zero ledger balance and no open obligation using it.
  - A customer needs every account closed.
  - The account's zero-balance check runs under the ledger's balance-row lock, so a
    concurrent deposit cannot slip in. A test races `close` against deposits.
- **Obligations have no schedule until disbursement.** The schedule's dates depend on
  when funds are actually released.

## 4. How the domain uses the ledger

Every business operation with a financial effect is one database transaction that
writes the domain rows, the balanced journal (through `LedgerService.post`, the only
write path), and the audit event. Nothing commits unless all of it does.

| Operation | Domain effect | Journal |
|---|---|---|
| Activate account | account PENDING → ACTIVE | none. Opens a ledger account with a floor (−overdraft for deposits, 0 for loans). |
| Cash deposit / withdrawal / transfer | none | `CASH_DEPOSIT` / `CASH_WITHDRAWAL` / `CUSTOMER_TRANSFER`, as in phase 1 |
| Disburse loan | obligation → ACTIVE, schedule written, loan position activated | `LOAN_DISBURSEMENT`: Dr loan receivable (asset), Cr borrower deposit (liability) |
| Repay loan | repayment and allocations appended; possibly SETTLED and position closed | `LOAN_REPAYMENT`: Dr borrower deposit; Cr loan receivable (principal); Cr interest income (interest) |
| Cancel proposal | obligation → CANCELLED, PENDING position → CLOSED | none |

Guarantees at the boundary:

- **Domain and ledger agree at commit (PostgreSQL).** For every non-proposed
  obligation, the position's ledger balance equals principal minus principal repaid.
  The schedule's principal equals the contract principal. Repayments equal their
  allocations, and no instalment is over-paid. SETTLED means fully paid, and a fully
  paid loan cannot remain ACTIVE. A repayment row without the matching ledger
  movement is rejected at commit.
- **Domain and ledger agree at runtime (application).** `LoanService` re-checks the
  first equality before commit. If someone moved the loan position through the raw
  ledger primitive, the next loan operation refuses to proceed.
- **Product positions are off-limits to manual journals.** `JournalService` (the
  ledger API) refuses ADJUSTMENT postings to `CUSTOMER_DEPOSIT` and `LOAN_RECEIVABLE`
  ledger accounts, and refuses to reverse entries owned by a product module. Reversing
  only the ledger half of a loan repayment would desynchronise the contract.
- **Atomicity is tested by failing after the ledger post.** An outer transaction
  performs a disbursement or repayment, then throws. The obligation, schedule, loan
  position, ledger account, journal entries and balances are all unchanged.
- **Idempotency.** Deposit keys are namespaced per operation before reaching the
  ledger's global key space. Disbursement uses a deterministic key
  (`loan.disbursement:<id>`) and replays when the loan is already ACTIVE. Repayment
  keys are looked up *after* taking the obligation's row lock, so a concurrent retry
  sees the first attempt's result.
- **Concurrency.** Repayments of one loan serialise on the obligation row lock. The
  test runs 10 concurrent repayments where only 9 fit: exactly 9 succeed, and the
  ledger and contract agree.

## 5. The minimum loan

> **Superseded for new loans by [lending.md](lending.md) (phase 4).** Phase 4 added
> approval, accrual accounting, due-only allocation (V2) with early settlement,
> delinquency and default. Loans written before V9 keep the rules below (V1, cash basis).

This phase implements only enough lending to validate the obligation model. Lending
proper, with delinquency and default, comes in a later phase.

- **Terms:**
  - fixed nominal annual rate in integer basis points (0–10 000)
  - 1–480 monthly instalments
  - annuity amortisation
- **Schedule (`AnnuitySchedule`):**
  - Monthly rate is r = bps/120 000. Every month counts as one equal period.
  - The instalment A = P·r/(1−(1+r)^−n) is rounded half-up to a minor unit.
  - Each period's interest is rounded half-even.
  - The final instalment repays exactly the remaining principal, so Σ principal = P
    exactly.
  - Due dates are start + k months, clamped to month end, and always computed from the
    start date so they never drift.
  - All arithmetic is `BigDecimal` with 34 significant digits. The result is
    deterministic.
- **Rounding up was tried and rejected.** Always rounding A upward amortises principal
  early. The excess compounds at (1+r), and a test showed 5 000 at 99.99% over 60
  months paying off before its term. The honest bound is that the final instalment can
  differ from A by up to ((1+r)ⁿ−1)/r minor units in the worst case, and the property
  test asserts exactly that bound.
- **Allocation (`AllocationPolicy.V1`):**
  - Oldest instalment first, interest before principal.
  - Overpayment is refused.
  - The policy name is stored on each repayment.
- **Interest income is recognised on a cash basis**, when received. There are no
  accruals.

## 6. Domain invariants currently enforced

| Invariant | Application | PostgreSQL |
|---|---|---|
| A party is exactly one of person / organization | `Party` subclasses | composite FK + deferred `party_must_have_subtype` |
| Party identity and type are immutable; parties are never deleted | no setters | `party_guard` |
| One customer relationship per party; the institution is not a customer | `CustomerService.open` | `UNIQUE(party_id)` |
| Customer starts PENDING; valid transitions only; CLOSED is terminal; close requires closed accounts | `CustomerStatus`, `CustomerService.close` | `customer_guard`, `customer_lifecycle_timestamps` |
| An account has a valid owner, and owner = the party behind its customer | `Account.open` | NOT NULL + FK `account_owner_matches_customer` |
| Only an ACTIVE customer and an on-sale product can open or activate accounts | `AccountService` | `account_guard` (with `FOR SHARE` on customer and product) |
| Overdraft only on products that allow it; loans never | `Account.open` | `account_guard`, `account_loans_have_no_overdraft` |
| Account transitions valid; CLOSED terminal; close needs zero balance and no open obligation | `AccountStatus`, `AccountService.close`, balance lock | `account_guard` |
| PENDING ⇔ no ledger account; account and ledger status mirrored; family ↔ ledger type and purpose | `AccountService` | `account_ledger_presence`, deferred `account_ledger_sync` / `ledger_account_sync_to_account` |
| An account has no balance | no field | no column (asserted by test) |
| Product definition immutable; ACTIVE → WITHDRAWN only | `Product` | `product_guard` |
| Obligation names distinct creditor and debtor, positive principal, currency | `Obligation.propose` | CHECKs + FKs |
| Debtor owns both the position and settlement accounts, in the loan's currency; position is LOAN, settlement is DEPOSIT | `LoanService.propose` | composite FKs + `obligation_guard` |
| Terms, schedule, repayments and allocations are immutable or append-only | `@Immutable` entities | `obligation_guard`, `wbank_forbid_mutation` triggers |
| Ledger position = principal − principal repaid; schedule, allocations and SETTLED status consistent | `LoanService.assertLedgerAgrees` | deferred `wbank_check_obligation` |
| Manual journals cannot touch product positions or reverse product entries | `JournalService` | **not enforced**: the raw `LedgerService` primitive permits it, and the obligation check then refuses the next loan write |

## 7. Intentionally deferred

- **Parties:**
  - addresses, identity documents, KYC/AML evidence and screening
  - beneficial ownership, and relationships between parties
  - party lifecycle (deceased, dissolved), joint accounts, mandates and signatories
- **Products:**
  - interest on deposits, fees, eligibility rules and pricing
  - product versions and parameters (rate ranges, terms)
- **Accounts:**
  - changing overdraft limits, dormancy, statements
  - available versus ledger balance and holds. This belongs to the payments phase.
- **Obligations and lending:**
  - applications, approval and credit decisions
  - accrued interest, day-count conventions
  - delinquency, default, late fees, prepayment rules, restructuring, write-off
  - loans in which the bank is the debtor
- **Transfers:** a direct internal transfer (`DepositService.transfer`) remains from
  phase 1 as a service method. It will be superseded by the payment lifecycle.
