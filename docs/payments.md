# W — The Payment Lifecycle

Status: research implementation, phase 3. Schema: Flyway `V8`. Builds on [ledger.md](ledger.md) and [domain.md](domain.md).
Code: `payment` package, `account/funds`.

**This is not a payment system.** It implements internal account-to-account transfers
inside one institution. It has:

- no clearing network, no settlement institution, and no payment rail
- no ISO 20022 support, no scheme rules, and no cut-off times
- no fraud engine, no sanctions screening, and no regulatory reporting

---

## 1. Why a payment is a lifecycle, not a transaction

"Move 100 from A to B" hides several distinct questions. Each has its own answer, its
own time, and its own evidence:

1. **What was asked?** Who asked, from which channel, for how much, in which currency,
   to whom, and with what reference. This is a fact the moment it is received,
   whatever happens next.
2. **Can the bank process it at all?** Validation: do both accounts exist, are they
   open, do the currencies match?
3. **Is the bank willing to commit the funds?** Authorisation: are the funds
   available, and is the amount within limits? A "yes" is a *promise*, so the money
   must be set aside.
4. **Did value actually move?** Execution and settlement.
5. **Was it later corrected?** Reversal.

Between 3 and 4 the world can change. The creditor's account may be frozen, the
initiator may cancel, or the authorisation may lapse. A single database transaction
cannot represent "promised but not yet paid". A lifecycle can. Real-world rails make
the gap even larger: a payment can be in flight for hours or days.

## 2. The state model, and why these states

```
                ┌──> SETTLED ──reverse──> REVERSED
   AUTHORIZED ──┼──> CANCELLED   initiator withdrew before execution
                ├──> EXPIRED     authorisation lapsed (default 7 days)
                └──> FAILED      execution could not complete; no financial effect
   REJECTED                      failed validation or authorisation; funds never held
```

A payment is born `AUTHORIZED` or `REJECTED`. The domain enum and a PostgreSQL trigger
both enforce this.

Several states from the usual textbook model were **rejected on purpose**:

| Candidate | Decision | Reason |
|---|---|---|
| INITIATED / RECEIVED | omitted | The instruction is recorded, validated and authorised or rejected in *one* transaction, so no one can ever observe it "received but not decided". The step survives as the `INSTRUCTION_RECEIVED` event. |
| VALIDATED | omitted | Same reason. It is the `VALIDATION_PASSED` event, which carries every check and its evidence. |
| PROCESSING | omitted | An internal book transfer settles atomically. PROCESSING only means something when submission and confirmation are separated in time by an external rail. It is the first state to add when one is introduced. |
| AUTHORIZED | kept | Observable and meaningful: funds are held, the ledger is unchanged, and the payment can still be cancelled, can expire, or can fail. |
| REJECTED vs FAILED | both kept | REJECTED means "we refused before promising anything". FAILED means "we promised, then could not deliver". They have different causes and different customer consequences, and they must be distinguishable in the data later phases learn from. |
| REVERSED | kept | A settled payment is corrected by a new, visible entry, never by un-settling it. |

Separately from states, there is a hard boundary between **malformed** and
**refused** requests:

- A **malformed** request gets 400 or 404 and **no record**. Examples: a missing or
  invalid amount, an unknown currency or account, or the same account on both sides.
  It was never a valid instruction.
- A **semantically refused** request gets a **recorded REJECTED payment**, with the
  failed check and its evidence. Examples: a frozen account, a currency mismatch,
  insufficient funds, or an amount over the limit. It *was* an instruction, and the
  refusal is part of history.

HTTP returns 201 in both the settled and the rejected case, because a resource was
created. Clients must read `status`.

## 3. Instruction, processing, financial transaction, posting, settlement

These are five different things, stored in five different kinds of record:

| Concept | Record | Mutability | Meaning |
|---|---|---|---|
| Payment instruction | `payment_instruction` | immutable | what was asked, by whom, when, through which channel |
| Payment processing | `payment` + `payment_event` | state row + append-only log | where the payment stands, and every step that got it there |
| Financial transaction | `journal_entry` (`PAYMENT_TRANSFER`) | immutable | the balanced accounting fact |
| Ledger posting | `posting` (2 legs) | append-only | debit the debtor's deposit liability, credit the creditor's |
| Settlement | `payment_settlement` | append-only, unique per payment | the fact that this payment was discharged, through that entry, by that method |

Consequences of keeping them apart:

- **Rejected, cancelled, expired and failed payments** leave instructions and events
  behind, but no journal entry.
- **A settled payment has exactly one settlement.** It points at exactly one journal
  entry, which has exactly two legs. PostgreSQL checks the legs match the instruction,
  and both settlement columns are unique.
- **A reversal is a second journal entry** (`REVERSAL`). It points at the first
  through `reverses_entry_id`, and the payment records it.

## 4. Authorisation versus execution

**Authorisation** decides whether the bank *may* commit the funds, and then commits
them by placing a hold. It checks:

- the single-payment limit (default 1 000 000 major units)
- available funds

It runs **under the debtor's ledger balance-row lock**, the same lock every posting
takes. It posts nothing.

**Execution**:

1. Re-checks that both accounts are still operable (state may have changed since
   authorisation).
2. Consumes the hold.
3. Posts the `PAYMENT_TRANSFER` journal.
4. Records the settlement.

All four steps happen in one transaction.

Authorisation here means **financial** authorisation: account controls, funds and
limits. It does *not* verify that the initiator is entitled to debit the account.
There is no authentication; the actor comes from a header (see §10).

`PaymentService` runs authorisation and execution in **separate transactions**:

- **An authorisation is durable before execution starts.** If the process dies
  between the two, the payment stays AUTHORIZED with its hold, and can be executed,
  cancelled or left to expire.
- **An execution failure can be recorded.** If execution throws for any reason, its
  transaction rolls back completely: no posting, no settlement, and the hold is still
  active. A fresh transaction then records FAILED and releases the hold. The test
  injects a fault *after* the ledger journal has been written inside the execution
  transaction, and verifies that nothing financial survives.

## 5. Settlement versus initiation

Initiation creates the obligation to pay, and authorisation reserves the means.
**Settlement** is the moment the debtor's liability to the creditor is discharged by
moving value. Here that is an internal book transfer between two liabilities of the
same bank. The bank's balance sheet total does not change; only whom it owes changes.

With an external rail, settlement would be a separate, later event confirmed by a
settlement institution. The `method` column (`INTERNAL_BOOK_TRANSFER` only) and the
separate `payment_settlement` table are where that would attach.

## 6. Ledger, reserved and available balances

| Balance | Definition | Source |
|---|---|---|
| **Ledger balance** | what has actually been booked | ledger projection (phase 1, verified against postings) |
| **Reserved** | Σ ACTIVE holds on the account | derived: `SUM(funds_reservation.amount) WHERE status = 'ACTIVE'` |
| **Available** | ledger − reserved − floor | derived |

Only one thing is materialised: the **holds**, as rows. They are facts ("on this
date, 40.00 was promised to payment P"), with a lifecycle
(`ACTIVE → CONSUMED | RELEASED`) and immutable terms.

The **reserved total is derived** rather than kept as a running column:

- A second running number would be another cache to keep consistent. The ledger's
  projection needed chain-and-tip triggers to become trustworthy.
- The partial index `WHERE status = 'ACTIVE'` keeps the sum cheap while the number of
  active holds per account is small.
- A single hot account with thousands of concurrent holds would favour materialising
  it. That is a deliberate future trade-off, not an oversight.

**Holds bind every debit path, not just payments:**

- Cash withdrawals, loan repayments and payment authorisation all call
  `FundsService.requireAvailable` / `lockedBalances` under the balance lock.
- PostgreSQL enforces `ledger balance − active holds ≥ floor` at every commit that
  changes a balance or a hold (`wbank_check_available_funds`).
- A test shows even the raw `LedgerService` primitive cannot spend held money. A 300
  debit passes the ledger floor (1 000 booked), but fails because 800 is held.

## 7. Idempotency, and why it matters financially

Networks lose responses. A client that timed out does not know whether its payment
happened, so it retries. Without idempotency, the retry is a second payment: real
money, paid twice.

- **A key is required** on every instruction (`Idempotency-Key` header).
- **The key is bound to a SHA-256 fingerprint of the request's financial meaning:**
  debtor, creditor, currency, amount and remittance information. Provenance is
  excluded.
  - Same key, same meaning: **replay**. The original payment is returned with
    `replayed = true`, whatever its status, *including REJECTED*. The same
    instruction is the same decision; it is not re-evaluated because the account has
    since been funded.
  - Same key, different meaning: **409**.
- **Concurrent duplicates are serialised** on a transaction-scoped PostgreSQL advisory
  lock taken on the key before the lookup. With 20 threads submitting one key there
  is 1 instruction, 1 settlement, and 1 non-replayed response. Removing the lock makes
  the test fail (mutation-checked).
- **Settlement is idempotent independently.**
  - The journal uses the deterministic key `payment.settlement:<paymentId>`.
  - `payment_settlement.payment_id` is unique.
  - Re-executing a SETTLED payment is a no-op.
- **Keys are namespaced.** Payment keys are stored on the instruction. Deposit and
  loan keys are prefixed before they reach the ledger's global key space.

## 8. Concurrency and double spending

The double-spend risk is a check-then-act race: two requests both read "available
100", both approve 80, and the account ends at −60. This is prevented in three layers:

1. **Serialise the decision.** Authorisation reads available funds and places the hold
   while holding the debtor's balance-row lock. Withdrawals and postings take the same
   lock, so every decision about an account sees every earlier decision. Locks are
   taken in a consistent order (the ledger sorts by account id).
2. **Make the decision binding.** A hold reduces available funds for every later
   debit, from any path.
3. **Backstop in PostgreSQL.** Available funds must be ≥ 0 at commit, whatever the
   application did.

The tests exercise each layer:

| Scenario | Result |
|---|---|
| 30 concurrent 10.00 payments from an account holding 100.00 | exactly 10 settle, 20 are REJECTED `INSUFFICIENT_FUNDS`, and no running balance is ever negative |
| 10 holds and 10 withdrawals racing on 100.00 | exactly 10 succeed in total, and available ends at 0 |
| authorisation lock removed (mutation) | the tests fail. Money is still not double-spent (layer 3 rejects at commit), but the losers surface as integrity errors instead of recorded rejections. The database is a backstop, not the mechanism. |

## 9. Failure and reversal semantics

| Situation | Outcome | Ledger |
|---|---|---|
| Validation or authorisation fails | REJECTED; reason code and evidence recorded | nothing |
| Initiator cancels an AUTHORIZED payment | CANCELLED; hold released | nothing |
| Authorisation lapses (`POST /payments/expirations` or execution after expiry) | EXPIRED; hold released | nothing |
| An account becomes non-operable before execution | FAILED `ACCOUNT_NOT_OPERABLE`; hold released | nothing |
| Anything throws during execution | execution transaction rolled back; then FAILED `PROCESSING_ERROR`, hold released | nothing survives |
| A SETTLED payment is corrected | REVERSED; mirror `REVERSAL` entry; original settlement untouched | two entries, net zero |
| Reversal when the creditor no longer has the funds available | refused (422); payment stays SETTLED | nothing |

Reversal is a *correction by the bank* and is not guaranteed. If the creditor has
spent or committed the money, a reversal would force them into an unauthorised
overdraft, and it is refused. Recovering money in that case (recall, dispute,
chargeback) is a process this system does not model.

FAILED is terminal. A technically transient failure (for example a database outage)
also ends as FAILED, and the client must submit a new instruction. Retrying *inside*
the lifecycle would need a PROCESSING state and a retry policy (§10).

## 10. Provenance

A later intelligence layer can reconstruct each payment from the database alone:

| Question | Where |
|---|---|
| who initiated it, how, when | `payment_instruction.initiated_by`, `initiator_type`, `channel`, `received_at`, `correlation_id` |
| which accounts, what amount and currency | `payment_instruction` (immutable) |
| which validations ran, with what evidence | `payment_event` `VALIDATION_PASSED` / `VALIDATION_FAILED`: every check, pass or fail, with the account status and currencies it saw |
| what authorisation decided, on what basis | `AUTHORIZED` / `AUTHORIZATION_DENIED`: the limit, ledger balance, reserved, floor, available, requested, and the hold id |
| what happened in processing | ordered `payment_event` rows (`sequence_no`), each with actor and correlation id |
| whether it settled, and which ledger entries resulted | `payment_settlement` → `journal_entry` → `posting`; the `SETTLED` event repeats the legs and running balances |
| whether and why it was reversed | `REVERSED` event: reason and reversal entry id; `journal_entry.reverses_entry_id` |

Every transition also writes an `audit_event`. All of it is append-only and enforced
by trigger.

## 11. Invariants preserved and added

All ledger (phase 1) and domain (phase 2) invariants still hold. The whole suite runs
on every change. Phase 3 adds:

| Invariant | Application | PostgreSQL |
|---|---|---|
| Available funds ≥ 0 (ledger − active holds ≥ floor) | `FundsService` under the balance lock | deferred `balance_respects_reservations` / `reservation_respects_available` |
| Hold terms immutable; ACTIVE → CONSUMED \| RELEASED only | `FundsReservation` | `reservation_guard` |
| Instruction immutable; one per idempotency key; distinct accounts; positive amount | `PaymentInstruction`, advisory lock | `wbank_forbid_mutation`, UNIQUE, CHECKs |
| Payment born AUTHORIZED or REJECTED; valid transitions; terminal states frozen | `PaymentStatus` | `payment_guard`, `payment_lifecycle_fields` |
| AUTHORIZED ⇒ an active hold equal to the instruction | `PaymentProcessor` | deferred `wbank_check_payment` |
| SETTLED ⇒ exactly one settlement, through a `PAYMENT_TRANSFER` debiting the debtor and crediting the creditor by exactly the amount; hold consumed | `PaymentProcessor.execute` | `wbank_check_payment`, UNIQUE on settlement |
| REJECTED / CANCELLED / EXPIRED / FAILED ⇒ no settlement, hold released or never taken | `PaymentProcessor` | `wbank_check_payment` |
| REVERSED ⇒ the reversal entry reverses the settlement entry | `PaymentProcessor.reverse` | `wbank_check_payment` |
| Payment events append-only and ordered | `PaymentEvent` | trigger + UNIQUE `(payment_id, sequence_no)` |

The phase-2 direct `DepositService.transfer` was **removed**. It moved money between
customers without authorisation, holds or provenance. Account-to-account movement now
exists only as a payment.

## 12. Outside the current scope

- **External rails:** clearing and settlement networks (ACH, SEPA, RTGS, card schemes),
  nostro/vostro accounts, settlement institutions, ISO 20022 messages, cut-off times,
  value dating and business-day calendars.
- **Multi-currency payments** and FX.
- **Initiator authentication and entitlements:** mandates, dual authorisation,
  per-customer or per-channel limits, and velocity limits.
- **Financial crime:** fraud scoring, sanctions and AML screening, and investigation
  holds.
- **Scheduled and recurring payments, standing orders, direct debits and payment
  batches.**
- **Retries inside the lifecycle.** PROCESSING, retry policies, and dead-letter
  handling.
- **Automatic expiry.** Expiry is an explicit sweep endpoint; there is no scheduler.
- **Recalls, disputes, chargebacks,** and partial reversals.
- **Fees and charges** on payments.
- **Idempotency key scoping** per client, and key expiry.
