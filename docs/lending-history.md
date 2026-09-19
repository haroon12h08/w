# W — Point-in-Time Lending History

Status: research implementation, phase 5. Schema: Flyway `V10`. Builds on [lending.md](lending.md).
Code: `obligation/history` (`LendingHistory`, `LoanHistoryFold`, `PointInTimeService`,
`DecisionReconstructionService`, `LendingDatasetService`, `CreditPolicyService`, `CorrectionService`).

> **The requirement.** Given a historical timestamp T, reconstruct the lending state and
> decision context as they existed at T, without using any information that became
> available later.

---

## 1. Why current state is insufficient for learning

Current state tells you where a loan *is*, not how it got there, and above all not what the
bank *knew* when it acted. To judge a past credit decision you need:

1. the information available when it was made
2. the rules in force at that moment
3. what was decided, by whom, and what action followed
4. what then actually happened

Current state destroys all four. A loan approved in January and defaulted in June is, today,
simply "DEFAULTED". Evaluate the January decision against today's row, and the evaluation
has already seen the answer. Every model trained or validated that way looks better than it
will ever perform, because it was graded with the answer key visible. That is future
information leakage, and it is the most common way credit analytics fool themselves.

## 2. The temporal model: which times are actually needed

The brief names five kinds of time. Here is what each means in this domain, and how it is
stored:

| Concept | Meaning here | Stored as |
|---|---|---|
| **Event time** | when something happened in the business world (payment received, decision made) | `effective_at` |
| **Effective time** | when it takes economic effect (e.g. interest earned on the period's due date) | `effective_at` |
| **Decision time** | the event time of a decision | `effective_at` of a `DECISION` event |
| **Observation time** | the date a derived observation refers to (delinquency "as of") | `effective_at` of an `OBSERVATION` event |
| **Record time** | when the bank knew / wrote it down | `recorded_at` (always system-assigned) |

**Two timestamps suffice.**

- Event time and effective time coincide for everything except interest accrual. There,
  the *effect* is the period end, and the *event* is the servicing run that recognised it,
  which is the moment it was recorded. So "event time ≠ effective time" is already fully
  represented by `effective_at ≠ recorded_at`.
- Decision time and observation time are not further dimensions. They are the *role*
  `effective_at` plays, given the event's kind: `FACT`, `DECISION`, `OBSERVATION` or
  `CORRECTION`.
- A third column would duplicate information and invite inconsistency.

This is bitemporal, reduced to the minimum:

- `effective_at` answers "true when?"
- `recorded_at` answers "known when?"
- PostgreSQL enforces `effective_at ≤ recorded_at`, so nothing is ever recorded as having
  happened in the future.

Real examples in the implementation where the two times differ:

- **Interest accrual.** Effective on the due date (Feb 4). Recorded by the servicing run
  (Feb 5, or weeks later if servicing is late).
- **A delinquency observation.** "35 days past due as of Mar 11". Recorded that morning.
- **A correction.** Effective at the original decision's time. Recorded when the error was
  found.
- **Backfilled events (V10).** Their original timestamps are preserved. `origin =
  BACKFILL_V10` records that the history row itself was materialised by the migration.

## 3. What point-in-time correctness means

A point-in-time view is defined by two instants: `asOf` (the business time you want to see)
and `knownAt` (the knowledge cut-off). `knownAt` defaults to `asOf`, meaning "what the bank
knew at that moment".

> An event is visible at (asOf, knownAt) **iff** `effective_at ≤ asOf` **and** `recorded_at ≤ knownAt`.

`LoanHistoryFold` is a pure function. It applies exactly the visible events of a loan in
`loan_seq` order to produce a `HistoricalLoanState`:

- status
- decisions (with policy version and snapshot id)
- the schedule, with what had been paid or waived
- repayments
- accrued and repaid interest
- outstanding principal
- accrued-but-unpaid interest
- delinquency
- corrections

It also reports how many events were not yet visible.

The fold reads **only** `lending_event`. It never touches the mutable current tables, so
later changes to them cannot leak backwards. A test asserts the structure: the
point-in-time service's only data dependencies are the event store, plus the immutable
`obligation.debtor_party_id`, used solely to find a borrower's loans. Whether each loan is
*visible* is still decided by its history.

The distinction matters in practice. At noon on Feb 4, loan C's first-period interest is
*effective* but was not *recorded* until the next morning's servicing run.

| View | Accrued interest |
|---|---|
| As known then (`knownAt = Feb 4 noon`) | 0 |
| With hindsight (`knownAt = today`) | the full instalment interest |

Both answers are correct; they answer different questions. A mutation test confirms the
tests catch it if the `recorded_at` condition is removed.

## 4. How ordering is kept deterministic

- **`loan_seq`** is gap-free per loan. It is assigned under the loan's row lock, which every
  lending operation holds, so it is the order events were recorded. A trigger rejects gaps
  and any `recorded_at` earlier than its predecessor's.
- **Events written in one transaction share an instant.** For example, a final payment
  records `INTEREST_ACCRUED → REPAYMENT_RECEIVED → LOAN_SETTLED` at one instant. The
  sequence orders them, and they are written in that economic order.
- **`global_seq`** (identity) orders events across loans.

## 5. Historical facts versus derived values

| Nature | Examples | How it is treated |
|---|---|---|
| **Fact** | application, disbursement, schedule, repayment and allocation, accrual, settlement | recorded once, never changed |
| **Decision** | credit decision, default declaration | recorded with a snapshot (§6) and a policy version (§7) |
| **Observation** (materialised derivation) | delinquency bucket changes | recorded as what the bank *saw* and when; labelled `OBSERVATION` |
| **Derived on read** | days past due at any date, maximum DPD over a window, outstanding amounts | recomputed from facts by the fold; returned as `derivedDelinquency` |
| **Projection / cache** | `obligation.status`, ledger balance projections | current-state conveniences |

Every derivation is recomputable from facts. Observations are kept anyway because "the bank
believed the loan was 35 DPD on Mar 11" is itself a fact about the bank. It can legitimately
differ from what the facts later imply: servicing may have run late, or a payment may have
arrived out of order.

The current projection is enforced consistent with history. PostgreSQL rejects at commit
any obligation whose status differs from the status its latest status-changing event
implies. It also rejects any repayment, accrual, decision or delinquency row written without
its history event, and any decision written without a snapshot. The history cannot silently
fall behind the domain.

## 6. Decision snapshots

Every credit decision (APPROVED, DECLINED, DEFAULT_DECLARED) writes, in its own
transaction, an immutable `credit_decision_snapshot`. It is built from **live state at
decision time**, stored as canonical JSON, and SHA-256 hashed.
`DecisionSnapshots.verify` re-derives the hash after the JSONB round trip. Each section
answers exactly one of the questions a reviewer must be able to answer:

| Question | Snapshot section |
|---|---|
| Who was the subject? | `subject`: party id, type, country; customer id, number, status, activation time |
| What loan / product was considered? | `application`: product, principal, currency, rate, term, instalment, allocation policy, interest recognition, proposal time |
| What financial information was available? | `accounts`: every account of the customer with ledger, reserved and available balance |
| What obligations already existed? | `existingObligations`: status, principal, outstanding principal (ledger), days past due |
| (default decisions) What was the loan's state? | `subjectLoan`: outstanding principal, delinquency |
| What evidence was used? | `evidence.suppliedByDecider`: declarations the bank does not hold itself, marked `UNVERIFIED_DECLARATIONS` |
| What policy / rules were active? | `policy`: code, version, effective-from, full rules |
| How were they evaluated? | `ruleEvaluation`: each rule with observed value, threshold, pass/fail |
| What decision was produced, when, by whom? | `decision`: kind, decidedAt, decidedBy, deciderType, correlationId, rationale |
| How was it produced? | `decisionLogic`: `POLICY_RULES_WITH_HUMAN_JUDGEMENT` plus the policy version. A model version would be recorded here later. |
| What was the resulting action? | `resultingAction`: from-status → to-status |

What is deliberately *not* captured:

- names, date of birth and contact details (the rules do not use them)
- ledger history beyond balances
- other customers' data
- anything "that might be useful to a model"

Every field has a reason. Adding one later requires a reason, not a hunch.

**Two independent routes to the same facts.** The snapshot is captured from live tables. The
reconstruction folds the event history. The test for decision G (declined after the same
borrower defaulted on D) asserts that the snapshot's view of the defaulted loan (status,
outstanding principal, days past due) equals the historical reconstruction of D as known at
G's decision time.

`DecisionReconstructionService.reconstruct(decisionId, outcomeKnownAt)` returns the full
trace, evidence → decision → action → outcome:

- **the snapshot**, verified
- **the policy rules** of the cited version
- **`loanBeforeDecision`**: the subject loan folded from history strictly before the
  decision, as known at decision time
- **`borrowerAtDecision`**: the debtor's other loans as known at decision time
- **`resultingStatus`**
- **`laterCorrections`**, kept separate
- only if asked, an **`outcome`** section: the events after the decision as known at
  `outcomeKnownAt`, plus derived maximum DPD, defaulted, settled and settled-early

## 7. Policy versioning

`credit_policy` holds append-only versions of `LENDING_CREDIT_POLICY`. Version 1 codifies
the rules phase 4 applied implicitly, effective from system inception:

- active customer
- principal ≤ 50 000 major units
- term ≤ 360
- rate ≤ 30%
- no defaulted obligations
- existing DPD ≤ 29
- default at ≥ 90 DPD

Rules for publishing versions:

- **Versions are consecutive, and their effective times strictly increase.**
- **No retroactive versions.** A version may not take effect before it is published,
  enforced by both application and trigger. The rules that governed the past can never be
  rewritten.
- **Decisions use the version in force at decision time.**
  - Every `loan_decision` and snapshot cites `(policy_code, version)` by foreign key.
  - Approval requires every rule to pass. The failure message names the version and the
    failed rules.
  - The default threshold is read from the version in force.
- **Pre-V10 decisions have no recorded version, and none is inferred.**

Allocation policy (V1/V2) and interest recognition were already versioned per loan in
phase 4, and appear in the snapshot. There are no machine-learning models, so there are no
model versions. `decisionLogic` is where one would be cited.

## 8. Append-only history and corrections

The following are append-only, enforced by trigger:

- `lending_event`
- `credit_decision_snapshot`
- `credit_policy`
- all earlier lending fact tables

A correction is a **new** `EVENT_CORRECTED` event:

- It references the corrected event and records the field, previous value, corrected value
  and reason.
- `effective_at` is the corrected event's business time; `recorded_at` is when the error
  was found.
- A view "as known then" shows the original. A view "as known now" also shows the
  correction.
- Corrections are **never merged silently**. The decision context lists them separately,
  and the snapshot stays exactly as the decider saw it.

Only non-financial information can be corrected this way: the rationale and supplied
evidence on application and decision events. Financial facts are corrected by their owning
operation (ledger reversal, repayment rules). Attempting to "correct" a repayment is refused.

## 9. Datasets without leakage

`LendingDatasetService.creditDecisions(horizon, knownAt)` produces one row per approve or
decline decision:

- **Context: the snapshot, exactly as captured.** No feature engineering is done here.
- **Outcome: evaluated as known at `min(decidedAt + horizon, knownAt)`.**
  - Rows whose horizon has not elapsed are marked `censored`.
  - Declined applications have no outcome: what would have happened is unobservable, and is
    recorded as unknown, not imputed.
- **Serialisation is canonical, so identical data gives identical bytes.** The API returns
  a SHA-256 of the dataset.

The synthetic history (below) is generated twice, one non-leap year apart. Both runs
produce identical per-story rows: decision, policy version, final status, maximum DPD,
defaulted / settled / early.

## 10. Why temporal correctness matters for future credit models

A future credit model will be trained on "context at decision time" and labelled by
"outcome within a horizon". Four properties must hold, and this phase provides each
structurally:

1. **No leakage.** Context and outcome are computed with different knowledge cut-offs,
   from immutable records, and tested.
2. **Reproducibility.** The same history gives the same dataset, byte for byte.
3. **Attribution.** Every outcome traces back to a decision, the policy version and
   evidence it used, and the person or system that made it.
4. **Counterfactual research becomes possible, within limits.**
   - Re-evaluate historical applications under a *different* policy version, using only
     what was known then. A policy v2 decision can be simulated against v1-era snapshots.
   - Measure the outcome gap between decided and simulated policies for the *approved*
     population.
   - Declined applicants remain unobservable: the classic reject-inference problem. It is
     now explicit in the data rather than hidden.

## 11. Synthetic history for testing

`SyntheticLendingHistory` (test support) drives the real services with a controlled clock
from a base date. It produces seven stories:

| Story | What happens |
|---|---|
| A | on-time repayment |
| B | early settlement (interest waived) |
| C | delinquent to 35 DPD, then cured |
| D | never pays; default declared at 90 DPD |
| E | defaults, then recovers in full |
| F | declined |
| G | D's borrower re-applies after defaulting; policy v1 blocks approval; declined |

All timestamps are fixed offsets from the base, so the history is deterministic and
reproducible.

## 12. Migration (V10) and its regression test

V10 creates the history and backfills it from existing facts:

- applications, decisions, cancellations, disbursements, schedules, accruals, repayments
  (with allocations and waivers), delinquency events, defaults and settlements
- **original timestamps are kept**
- ties are ordered by the order in which the application writes them
- triggers are created only after the backfill

Lessons from V9 are applied:

- no ALTER follows DML on a table with deferred triggers
- `V10LendingHistoryMigrationTest` builds a **production-like V9 lending book** in an
  isolated schema, then upgrades it. The book has:
  - a funded deposit account
  - an approved, disbursed, accruing loan that became 1 DPD, was observed delinquent, was
    repaid and was observed current
  - a declined application and a cancelled one
  - fully chained ledger postings, which themselves passed every V9 commit-time check
- After the upgrade the test checks:
  - event order and contiguity
  - preserved effective and record times
  - point-in-time folds before and after repayment
  - ledger agreement
  - legacy decisions without a fabricated snapshot or policy version
  - status/history consistency for every loan
  - the new "fact needs history" rule being live

The real development database was also upgraded. Its two existing loans were backfilled
correctly and reconstructed over HTTP.

## 13. Known limitations and what is intentionally not captured

- **`recorded_at` is the application clock at write time, not the commit time.**
  - A reader could, for a few milliseconds, query a `knownAt` between a transaction's
    write and its commit, and see less than the timestamps claim.
  - Clock skew between application instances would also matter.
  - The per-loan order is still guaranteed by lock and sequence.
- **Decision time is taken when the decision logic starts.** The event is recorded
  microseconds later. The reconstruction handles this, and it was found on the real clock
  (the controlled test clock hides it). A regression assertion now runs on the real clock.
- **Day granularity.** Dates (due dates, delinquency as-of) map to 00:00 UTC. There are no
  time zones or business calendars.
- **Deposit and payment history is not reconstructable at T.** Balances at T could be
  derived from `posting.booked_at`, but only lending is in scope. Snapshots capture the
  balances that mattered at each decision.
- **Borrower lookup trusts `obligation.debtor_party_id`.** It is immutable from creation;
  a stricter design would read it from the application event.
- **Not captured:** applicant income or affordability data beyond free-text supplied
  evidence, bureau data, collateral, identity or KYC evidence, and communications.
  - No such data exists in the domain yet.
  - Inventing fields for it would be speculative.
- **No model versions, scores or predictions.** "What did the bank predict?" is answered
  only by the decision itself and its rules. A prediction record belongs with the first
  model.
- **Pre-V10 decisions have no snapshot or policy version.** This is honest but thin:
  their context can only be reconstructed from events.
- **Backfilled events predate the correction mechanism.** Their history is only as good
  as the facts V1–V9 recorded.
