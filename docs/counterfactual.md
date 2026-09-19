# W — Counterfactual Credit-Decision Evaluation

Status: research implementation, phase 6. Schema: Flyway `V11`. Builds on [lending-history.md](lending-history.md).
Code: `obligation/history` (`PolicyRule`, `PolicyEngine`, `DecisionFacts`, `CreditPolicyRules`) and `research`
(`CounterfactualEvaluator`, `SnapshotDecisionFacts`, `PolicyReplayService`, `ReplayOutcomeEvaluator`).

> **The question.** What would the bank's historical lending decisions have looked like if a
> different policy had been applied, using only the information that was actually available at
> each original decision time?

This is a **scientific instrument**, not an optimiser.

- It does not score, rank or recommend policies.
- It does not write policies; policies remain human-defined and versioned.
- It never changes a decision, an outcome or a policy version.

---

## 1. Prediction versus counterfactual reasoning

- **Prediction** asks: *given what is known now, what will happen?* It needs a model of the
  future.
- **Counterfactual reasoning** asks: *had something else been done, what would have been
  different?* It needs a model of the intervention.

This engine answers the narrowest counterfactual that can be answered **exactly**:

> *What would policy P have **decided**, given the exact facts the bank held when the real
> decision was made?*

It does not predict outcomes, and it does not claim to know what would have happened
afterwards. The decision part is exact because the facts are preserved (the phase-5
snapshot) and the policy is a deterministic function of those facts. Anything beyond the
decision is not claimed.

## 2. Historical replay and policy intervention

A **policy intervention** replaces the rules applied at decision time and holds everything
else fixed: the applicant, the application, the accounts and the other obligations.
**Historical replay** applies that intervention to a population of past decisions.

The model has three layers:

| Layer | What it is | Where |
|---|---|---|
| Rules | versioned `PolicyRule`s: `id`, `input`, `operator` (`EQ`, `LTE`, `GTE`, `IS_FALSE`), `threshold`, outcome on failure `DECLINE` | compiled deterministically from a `credit_policy` version |
| Facts | `DecisionFacts`, read only from the decision snapshot | `SnapshotDecisionFacts` |
| Engine | `PolicyEngine.evaluate(rules, facts)`: per-rule `PASS` / `FAIL` / `INDETERMINATE` with observed value, threshold and operator, and a final verdict | pure, with no clock, I/O or randomness |

A missing fact makes its rule `INDETERMINATE`, never a guess. The hypothetical decision is a
function of the verdict alone: `PASS → APPROVED`, `FAIL → DECLINED`,
`INDETERMINATE → UNDETERMINED`. PostgreSQL enforces this mapping with a CHECK constraint.

**The live approval path uses the same engine.** So replaying the policy that was actually
used reproduces, rule by rule, the evaluation recorded in each snapshot (test t06). That
fidelity check shows the replay machinery is not itself a source of discrepancy.

**Human discretion is separated from the policy effect.** A real decision may involve
judgement beyond the rules: in the synthetic history, F is declined although v1 permits it.
Each counterfactual therefore also records the **control verdict**, which is the actual
policy evaluated on the same facts. Each replay row reports two differences:

- `differsFromActualDecision`: the counterfactual decision differs from what the bank
  actually decided
- `differsFromActualPolicyVerdict`: the alternative policy's verdict differs from the
  actual policy's verdict. This isolates the effect of the rule change from human discretion.

**Research policies.** Alternatives are ordinary, append-only `credit_policy` versions under
a research code (for example `RESEARCH_PRINCIPAL_LIMIT`). Only `LENDING_CREDIT_POLICY` is
ever in force for live decisions. Versions of every code are consecutive, never retroactive
and immutable, which is enforced by trigger. A new optional parameter
(`minSettlementAvailableMajor`) produces a rule over snapshot data, so a policy intervention
can use decision-time account state.

## 3. Why counterfactual decisions cannot modify historical facts

The actual decision is a fact: it happened, the bank acted on it, and money moved or did
not. A counterfactual is a hypothesis computed later. Mixing them would corrupt the record
future learning depends on, so they are kept apart structurally:

| | Actual decision | Counterfactual decision |
|---|---|---|
| Type | `ActualDecision` (read model over `loan_decision` and its snapshot) | `CounterfactualDecision` (`research_counterfactual_decision`) |
| Nature | banking fact, with consequences | experiment result, with no consequences |
| Written by | the lending lifecycle | the research engine only |
| History | a `CREDIT_DECISION` lending event | **none**: no lending event, no audit of banking state |
| Mutability | append-only | append-only |

References run **one way only**, from research to banking.

- **The banking record is proven untouched.** Test t03 fingerprints every banking table
  before and after replays, evaluations and outcome reports. The live run confirms no
  research content ever reached `lending_event`.
- **Every counterfactual records, immutably:**
  - source decision id and source snapshot id (plus the snapshot's hash)
  - the actual decision and actual policy version
  - the alternative policy version
  - the original decision time and the evaluation time (the database requires
    evaluation time ≥ decision time)
  - rule-level results, the verdict, the control verdict and the hypothetical decision
  - a context-consistency check
  - the canonical **input** (facts plus compiled rules) with its SHA-256, and the SHA-256 of
    the output

## 4. Temporal honesty: how leakage is prevented

The counterfactual must not use later events, current mutable state, or the eventual outcome.

1. **Facts come only from the snapshot.**
   - `SnapshotDecisionFacts` depends on nothing but `CurrencyRegistry`. It needs the
     currency's scale, which is immutable reference data guarded by trigger since V6.
     A test asserts this dependency structurally.
   - The snapshot was captured in the decision's own transaction. Its SHA-256 is **verified
     before use**: an altered snapshot is refused, not evaluated (t11).
2. **The decision path reads only immutable records:** the decision row, its history event
   (for decision time) and the verified snapshot.
3. **The outcome is not an input.** The input's field set is closed and asserted (t08):
   schema, source (ids, time, actual decision, actual policy), facts and policy.
4. **Context consistency is checked independently.** The engine reconstructs the borrower's
   other obligations from history, as known at decision time (using the fold with its
   `recorded_at ≤ knownAt` cut-off). It records whether that agrees with the snapshot. The
   check is not an input, so it cannot change the decision; it detects a snapshot or
   history that disagree.
5. **Reproducibility.** `reproduce(id)` recomputes the result from the **stored input
   alone**. An altered stored input no longer reproduces (t12).

**Leakage mutation testing** (`leakageMutationsAreDetectedAndTheRealEngineIsImmune`):

- **How the perturbations are run.** They happen inside a transaction that is always rolled
  back, with PostgreSQL triggers disabled (`session_replication_role = replica`) for that
  transaction only. They alter:
  - post-decision default events
  - the current loan status
  - today's account balance
  - a late-recorded, back-dated fact
- **The real engine's output hashes never change.**
- **Deliberately leaky fact sources are always detected:**
  - *current state* (a stand-in for "replace the snapshot with current state")
  - *post-decision history*
  - *hindsight*: decision time, but with no knowledge cut-off
- **Code-level mutations were also run** against the production code. Each made a test fail:

| Mutation of production code | Failing test |
|---|---|
| remove `recorded_at ≤ knownAt` from the point-in-time fold | leakage test (context no longer consistent) |
| skip snapshot hash verification | t11 |
| read the borrower's context "as of now" instead of decision time | leakage test and context-consistency test |
| drop censoring | t10 |
| give declined applicants an outcome | t09 |

## 5. Observability: approved versus declined populations

The two populations are **fundamentally asymmetric**:

- **Approved applications became loans.** Their outcomes can be observed: repayment,
  delinquency, default, settlement.
- **Declined applications never became loans.** Their outcomes do not exist. Nothing was
  lent, so nothing could be repaid or defaulted.

So when an alternative policy would *approve* someone the bank declined, the consequence is
**unknowable** from the bank's own data. When it would *decline* someone the bank approved,
the bank observed what happened under approval, but only under approval.

**Reject inference** is the practice of estimating declined applicants' outcomes: assuming
they would have behaved like similar approved ones, or extrapolating from bureau data.
**This engine does not do it.** Declined rows are reported as `UNKNOWABLE`, counted
separately, and never given an imputed outcome (t09). Reject inference is an assumption, and
disguising an assumption as data is exactly the leakage of certainty this project avoids. A
legitimate route to those outcomes is an experiment: approving a randomised subset under
explicit risk limits. That is a business and ethical decision, and out of scope.

## 6. Censoring

An outcome observed through a window `[decision, decision + horizon]` is **censored** if that
window has not fully elapsed at `outcomeKnownAt`. A censored loan has not defaulted *yet*,
which is weaker than "did not default".

- Censored rows are flagged, counted, and their findings say so.
- Their outcomes are evaluated only as known at `outcomeKnownAt` (t10: as of 2028-03-01,
  D's later default is invisible and all five approvals are censored).
- The same experiment evaluated later is no longer censored, and then sees the defaults.

## 7. Policy replay versus causal inference

Replay answers: *how would policy P have classified the applications we actually saw?* A
causal claim would answer: *what would have happened had we operated under P?* These differ
because a real policy change also changes:

- who applies (selection)
- what is offered (pricing, limits)
- how borrowers behave
- the bank's capital and portfolio, and so later decisions (feedback)

Replay holds all of that fixed. So every report states `causalConclusion: NOT_ESTABLISHED`,
and each row keeps three things apart:

- **Observed outcome:** a fact, and only for historically approved applications
- **Counterfactual decision:** exact, computed from decision-time facts
- **Causal conclusion:** not established

## 8. What the experiments legitimately support

On the synthetic seven-loan history (base 2028-01-04):

| Experiment | Supported conclusion |
|---|---|
| `RESEARCH_PRINCIPAL_LIMIT` v1 (≤ 5,000.00) | Exactly one historical decision changes: **B** (6,000.00), approved → declined. **F**'s policy verdict changes (PASS → FAIL), but its actual decision does not, because F was declined at the officer's discretion. |
| `RESEARCH_PRINCIPAL_LIMIT` v2 (≤ 2,000.00) | All five approvals would have been declined. Among them were **observed** defaults (D, E) and **observed** full repayments (A, B, C, and E after recovery). The policy "would have avoided two observed defaults" **and** "would have forgone three observed repaid loans". |
| Changing D's post-decision default, status or events | The counterfactual for D is bit-for-bit identical. Outcomes cannot reach the decision input. |
| Replaying the same policy twice | Identical input hashes, output hashes and replay hash. |
| A, C, D and E | Identical decision-time facts: any policy over the current snapshot fields must treat them identically. Distinguishing the loans that later defaulted would require **information the bank did not capture**, not a cleverer rule. This is the most important research finding so far. |

Legitimate statements take this form: "under v2, these specific applications would have
been declined; among those the bank approved, these outcomes were observed".

## 9. What the experiments cannot support

- "Policy v2 is better" or "worse". The unknowable declined outcomes make any comparison
  incomplete.
- "Policy v2 would have prevented two defaults" as a causal statement. The borrowers,
  pricing and book would have differed.
- Default rates or loss estimates for a population. Samples are tiny, not drawn for
  inference, and partly censored.
- Anything about applicants whose snapshot was never captured. These are pre-V10 decisions,
  reported as `NOT_EVALUABLE`.

## 10. Why this is a prerequisite for simulation and AI-assisted policy research

Any future system that proposes, simulates or learns credit policy must answer "what would
this policy have done?" **without cheating**. This phase supplies that primitive:

- exact decision-time facts
- versioned, human-defined rules evaluated by one deterministic engine
- immutable, hash-reproducible experiment records
- a test harness that proves temporal honesty by detecting deliberate leaks

Simulation and learning can build on it: outcome models, portfolio effects, and eventually
AI-suggested policies reviewed by humans. Without it, every later result would be
unfalsifiable.

## 11. Known limitations

- **Only credit decisions (approve/decline) are replayed.** Default declarations are not.
- **The decision logic is rules plus human judgement.** Only the rules can be replayed;
  discretion appears only as the gap between the control verdict and the actual decision.
- **Rule vocabulary is closed and small.** Inputs and operators are extended in code, with
  a migration if a new fact must be captured.
- **Facts are limited to what snapshots capture.** Income, bureau and affordability data
  exist only as unverified supplied evidence, and are not rule inputs.
- **Pre-V10 decisions have no snapshot and are `NOT_EVALUABLE`.**
- **Outcome evaluation uses the phase-5 derivations:** maximum DPD, default declared,
  settled. It has no loss amounts, recoveries net of cost, or time value.
- **The context check compares status, outstanding principal and DPD only.**
- **Replays and outcome reports are computed synchronously.** This is fine for research
  populations, not for a production-scale book.
