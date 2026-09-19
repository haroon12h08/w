# W — Outcome Association, Censoring & Credit Research Cohorts

Status: research implementation, phase 8. Schema: Flyway `V13`. Builds on [lending-history.md](lending-history.md),
[counterfactual.md](counterfactual.md) and [credit-information.md](credit-information.md).
Code: `research` (`OutcomeEvaluator`, `OutcomeResearchService`, `OutcomeResearchController`).

> **The question.** Given what the bank knew at decision time, what happened afterwards to the
> lending population whose outcomes can actually be observed?

This is descriptive infrastructure. It provides no prediction, score, ranking, policy
recommendation or causal estimate.

> **Observed association between information state and outcomes is not evidence that the
> information state caused the outcome.**

> **Outcomes observed under the actual lending policy cannot be directly assigned to hypothetical
> decisions produced by an alternative policy.**

---

## 1. Four separate things

| | what | where it comes from | can it change later? |
|---|---|---|---|
| 1 | information state at decision time | the decision's hash-verified snapshot (`financialInformation`, Phase 7) | never; a snapshot is immutable, and recomputing it is a tested leak |
| 2 | the decision | `CREDIT_DECISION` lending event | never (corrections are appended, see §9) |
| 3 | the outcome observed afterwards | lending events after the decision | only as more becomes known |
| 4 | whether that outcome is observable | horizon vs. knowledge cutoff | yes, as time passes |

Outcomes flow forward only. No outcome is ever written into a snapshot or a banking table.

## 2. Cohorts

`research_cohort_definition` (code, version, canonical JSON, SHA-256; append-only). A cohort defines:

- **population**: credit decisions (`CREDIT_DECISION` events).
- **entry criteria**: `decidedFrom ≤ effective_at < decidedTo`, and the decision's `recorded_at ≤ knownAt`.
- **inclusion**: `includeDecisions` (APPROVED and/or DECLINED).
- **exclusion rules**, each listed with a reason:
  - decision kind not included;
  - snapshot fails its integrity check;
  - information not captured (only if `requireInformationCaptured`).
- **knowledge cutoff** (`knownAt`). It must be ≤ the time the cohort is defined, and the database enforces this. As a result, membership can never change after definition.

Membership comes only from immutable records: decision events, snapshots and loan histories. Current loan state plays no part.
Members are ordered by (decision time, event id). The membership is hashed, and the same definition over the same records always gives the same hash.

## 3. Outcome definitions and horizons

`research_outcome_definition` (append-only, versioned). The horizon is data, not code.

| event | meaning | threshold |
|---|---|---|
| `DEFAULT` | a `DEFAULT_DECLARED` event (the existing default state) | none |
| `SETTLEMENT` | a `LOAN_SETTLED` event | none |
| `DELINQUENCY_DERIVED` | days past due, derived from the agreed schedule and repayments, reaches the threshold | ≥ 1 day |
| `DELINQUENCY_BANK_OBSERVED` | a `DELINQUENCY_CHANGED` observation the bank recorded at or above the threshold | ≥ 1 day |

For a decision at `T`, horizon `H` and knowledge cutoff `K`:

```
outcome_cutoff = T + H
an event counts iff   T < effective_at <= outcome_cutoff   AND   recorded_at <= K
```

The two delinquency definitions can legitimately disagree:
- **Derived** is what the facts imply.
- **Bank-observed** is what the bank recorded, and when it recorded it. A servicing run that happens late records its observation late.

## 4. Observability and censoring

| status | when | value |
|---|---|---|
| `UNKNOWN` | declined application: no loan was made | none, never inferred |
| `CENSORED` | approved, `outcome_cutoff > K` (window not yet fully observed) | none: neither "occurred" nor "did not occur" |
| `NOT_APPLICABLE` | approved, window complete, no disbursement within it | none |
| `OBSERVED` | approved, window complete, disbursed | `OCCURRED` / `NOT_OCCURRED` (plus event time; max DPD for derived delinquency) |

Censoring is never treated as either outcome. A default that happened before a censoring point is still reported as censored under a horizon that has not elapsed.
The timeline shows such a default, but the statistics do not count it.

## 5. Knowledge cutoffs

| basis | `knownAt` | purpose |
|---|---|---|
| `AS_KNOWN_AT_DECISION` | each decision's own time | what was knowable when deciding: every approved outcome is CENSORED |
| `AS_KNOWN_AT` | a stated instant | "as known then" |
| `RESEARCH_CURRENT` | the evaluation time, stored as a concrete instant | everything currently known |

A knowledge cutoff can never be in the future.
`GET /cohorts/{code}/{version}/knowledge-comparison` evaluates one cohort and one definition under all three bases. It reports how many rows differ, and it confirms that no decision snapshot changed.

## 6. Information-state groups

Every member is grouped independently on three dimensions, all taken from its snapshot:
- `completeness`: COMPLETE, PARTIAL or INSUFFICIENT;
- `incomeState`: VERIFIED, DECLARED_ONLY or NONE;
- `obligationsState`: KNOWN_COMPLETE, PARTIALLY_KNOWN or UNKNOWN.

Version-1 snapshots appear as `NOT_CAPTURED` in each dimension.

The dimensions are not combined into a single score. Categories are listed alphabetically, which is not a ranking.

## 7. Descriptive statistics

For each outcome definition, in total and per information-state category, the report counts:
- decisions, approved and declined;
- observed, censored, not applicable and unknown;
- occurred and not occurred.

It also gives an **observed proportion**:

```
observed proportion = occurred / observed
observed = approved decisions whose window is complete at K and whose loan was disbursed
```

- Censored, not-applicable and declined decisions are in neither the numerator nor the denominator.
- The proportion is reported both as an exact fraction and as a 4-decimal HALF_EVEN value. It is null when the denominator is 0.
- It is a proportion within this observed group. It is **not** a population default probability and not a prediction.

The report also includes:
- a **population boundary** (the tree below);
- **warnings** (selection, censoring, association versus causation, counterfactuals, small numbers);
- `causalConclusion: NOT_ESTABLISHED`.

```
all applications in the cohort
  +-- declined: future outcome never observed; not inferred
  +-- approved
        +-- observation window complete: outcome observable (or NOT_APPLICABLE if never disbursed)
        +-- observation window incomplete: CENSORED; not a negative, not a positive
```

## 8. Selection

Outcomes exist only for approved applications, and those were chosen by the policy in force.
The observed approved population is therefore not a sample of all applicants. Nothing in this report describes what would have happened to declined applicants. No reject inference is performed.

## 9. Historical corrections

Phase 5 corrections are appended (`EVENT_CORRECTED`). The original event stays exactly as it was.
- An outcome result lists the corrections to the loan's post-decision events that were recorded by `K`.
- As known before the correction: the original only.
- As known after it: the original plus the correction.

Both reconstructions are reproducible, and they legitimately differ.

W's correction model covers non-financial information only: rationale and supplied evidence. A correction therefore changes what a result documents, but under the v1 definitions it cannot turn an outcome from occurred into not occurred.

## 10. Counterfactual outcome boundary

Phase 6 asks what an alternative policy would have **decided**. Phase 8 asks what was **observed** after the decision that was actually made.
`POST /policy-replays/{id}/outcome-boundary` records one `research_counterfactual_outcome` row per counterfactual. Each row holds:
- the actual decision;
- the counterfactual decision;
- the observed outcome of the actual decision, with `actual_outcome_attributed_to = 'ACTUAL_DECISION'`;
- `counterfactual_outcome = 'UNOBSERVED'`.

Database CHECK constraints refuse any other counterfactual outcome. They also refuse any observed outcome for a declined application.
- APPROVED → counterfactual DECLINED: the observed outcome stays the outcome of the actual approval.
- DECLINED → counterfactual APPROVED: the hypothetical outcome is UNOBSERVED.

No statement of the form "policy P would have prevented X defaults" can be produced. Changing the policy changes who receives a loan.

## 11. Reproducibility

`research_outcome_report` (append-only) records:
- the cohort code and version;
- the outcome definitions;
- the knowledge basis and cutoff;
- the population (membership) hash;
- the report;
- the output hash.

The report contains no evaluation-time field, so repeated evaluation over unchanged records gives identical bytes.
`GET /outcome-reports/{id}/reproduction` rebuilds the report and compares both hashes.
Tests show that moving an outcome event inside the window changes the result, and moving one outside the window does not.

## 12. Timestamp precision

A live check found that a report computed from a nanosecond clock could not be reproduced from its
stored (microsecond) cutoff. Instants that are stored and computed with are now normalized first
(`platform/time/DatabaseTime`); see [research-sensitivity.md](research-sensitivity.md) §9.

## 13. Statistical limitations

- Descriptive only. The report provides no regression, survival model, propensity score, uplift, Bayesian model or machine learning.
- Small groups make proportions unstable, and nothing here quantifies uncertainty.
- Associations can reflect selection, policy, timing or chance. Causal conclusions are never established.
- A default is the bank's declaration, not an economic loss measure.

## API (`/api/v1/research`)

| method | path | purpose |
|---|---|---|
| POST | `/outcome-definitions` | define an outcome (event, threshold, horizonDays); versioned |
| GET | `/outcome-definitions/{code}/{version}` | read one |
| POST | `/cohorts` | define a cohort |
| GET | `/cohorts/{code}/{version}/membership` | members, exclusions, hashes |
| GET | `/decisions/{decisionId}/outcome?definitionCode&definitionVersion&knownAt` | observability and value for one decision |
| GET | `/decisions/{decisionId}/outcome-timeline?knownAt&horizonDays` | the post-decision timeline from immutable history |
| POST | `/outcome-reports` | generate and record the information-state / outcome report |
| GET | `/outcome-reports/{id}`, `/outcome-reports/{id}/reproduction` | read, reproduce |
| GET | `/cohorts/{code}/{version}/knowledge-comparison` | the three knowledge bases side by side |
| POST | `/policy-replays/{id}/outcome-boundary` | actual decision, counterfactual decision, actual outcome; counterfactual outcome UNOBSERVED |
