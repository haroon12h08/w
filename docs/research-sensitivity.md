# W — Research Sensitivity, Selection & Robustness

Status: research implementation, phase 9. Schema: Flyway `V14`. Builds on [outcome-research.md](outcome-research.md).
Code: `research` (`SensitivityAnalysis`, `SensitivityService`, `SensitivityController`), the Phase 8
`OutcomeEvaluator` (unchanged semantics), and `platform/time/DatabaseTime`.

> **The question.** If I change the observation horizon, knowledge cutoff or population
> definition, exactly which observations and descriptive results change, and why?

A reported statistic is a function of its research definition:

```
population + decision window + information cutoff + outcome definition
  + observation horizon + censoring rules + inclusion/exclusion rules
```

Two correct analyses of the same history can therefore disagree. Phase 9 makes each of those
dependencies explicit, varies them one at a time, and accounts for every change.
It is descriptive only. It provides no hypothesis test, confidence interval, p-value, model, prediction, causal
estimate, ranking or policy recommendation.

---

## 1. What is reused, and what is new

Nothing about how an outcome is decided changes. Every decision is evaluated by Phase 8's
`OutcomeEvaluator.evaluate` with exactly the Phase 8 semantics, so Phase 8 report hashes still reproduce.
Membership, definitions and counting (`Stats`) are Phase 8's own.

Phase 9 adds three things:

- `SensitivityAnalysis`: pure functions that vary one parameter, re-evaluate, and account for the changes.
- `OutcomeEvaluator.knownEvent`: a read-only view of whether a qualifying event is already known inside a window, even an incomplete one. It is **not** an outcome status (see §3).
- `SensitivityService`: resolves a configuration to explicit immutable inputs, records a reproducible artefact, and reproduces it.

## 2. A research configuration

| parameter | meaning |
|---|---|
| `cohortCode`, `cohortVersion` | population, window, inclusion and exclusion rules, cohort knowledge cutoff (Phase 8, immutable) |
| `definitionCode`, `definitionVersion` | the outcome event and threshold (Phase 8, immutable) |
| `horizonDays` | optional override of the definition's horizon; the override is recorded |
| `knowledgeBasis` + `knownAt` | `AS_KNOWN_AT` with an **explicit** timestamp, or `AS_KNOWN_AT_DECISION` |
| `informationDimension` | grouping: `completeness`, `incomeState` or `obligationsState` |

`RESEARCH_CURRENT` is refused, because the current clock is never used implicitly. A cutoff in the future is refused.
Each definition's stored hash is checked against its content before use. A definition altered outside the append-only rules is refused with a 409.

## 3. Horizon sensitivity

`POST /research/sensitivity/horizons` evaluates one configuration under strictly increasing horizons.
For each horizon it reports:
- the Phase 8 totals (population, approved, declined, observed, censored, not applicable, unknown, occurred, not occurred, observed proportion);
- the censoring share;
- the selection boundary;
- the transitions from the previous horizon, with decision ids.

**With a fixed knowledge cutoff, a longer horizon asks a longer question.** It can therefore only:

- leave an outcome unchanged;
- move `OBSERVED:NOT_OCCURRED` to `OBSERVED:OCCURRED` (a later event now falls inside the window);
- move `OBSERVED:*` to `CENSORED` (the longer window is not complete yet).

It can never move `CENSORED` to `OBSERVED`. `newlyObservable` is always 0 under a fixed cutoff.
Observability grows with the **knowledge cutoff**, not with the horizon. In the synthetic history, 30 days censors 0 of 6 approved decisions, while 180 days censors 1 of 6 and 365 days censors 6 of 6.

This means one expectation in the phase brief ("a 30-day horizon has more censoring than a 180-day horizon") does not hold under a fixed cutoff. The opposite holds, and it is tested.
Likewise, "an OBSERVED loan never returns to CENSORED when the horizon grows" cannot hold for a non-occurrence: "no default within 180 days" says nothing about 365 days.

What does hold, and is checked on every run:

| invariant | meaning |
|---|---|
| `knownEventNeverDisappears` | a qualifying event known inside the shorter window is still known inside the longer one. It stays visible as `eventsKnownWhileCensored` even when the longer window is censored |
| `occurredNeverBecomesNotOccurred` | lengthening a window never un-happens an event |
| `fixedCutoffLongerHorizonNeverCompletesAWindow` | the consistency check above |
| `declinedAlwaysUnknown` | declined decisions stay `UNKNOWN` under every horizon |

A known event inside an open window is **shown but never counted**. Counting early events while ignoring open windows with no event yet would bias every proportion toward early outcomes.
A changing proportion across horizons shows what the question includes and which windows are complete. It is not evidence of changing borrower risk.

## 4. Knowledge-cutoff sensitivity

`POST /research/sensitivity/knowledge-cutoffs` takes strictly increasing, explicit, labelled cutoffs. It can optionally start with each decision's own time.
For each cutoff it reports the totals and the decisions whose status or value changed since the previous cutoff.
The invariants checked are:
- `laterCutoffNeverReopensAWindow` (no `OBSERVED` → `CENSORED`);
- `occurredNeverBecomesNotOccurred`;
- `knownEventNeverDisappears`;
- `declinedAlwaysUnknown`.

A later cutoff only reveals events recorded later. The events, decisions and snapshots are identical under every cutoff.

Membership is fixed by the cohort's own knowledge cutoff, not by the cutoff being varied. A varied cutoff earlier than a
member's decision time (for example `kThen` in the tests, taken before loan H was decided) therefore shows that
decision as `CENSORED`: its window is incomplete at that cutoff. It does not mean the decision was known at the time.
Choose a cohort cutoff no later than the earliest varied cutoff if that distinction matters.

## 5. Censoring and the approval-selection boundary

Every report carries the selection boundary:

```
allDecisions = approved + declined
approved     = approvedObservable + approvedCensored + approvedNotApplicable
declined     : outcome UNKNOWN (never observed, estimated or extrapolated from approved borrowers)
```

- `accountingHolds` checks these identities. A computation that dropped censored loans, or gave a declined applicant an outcome, fails it.
- The observed statistic's denominator is `approvedObservable`, and nothing else.
- The censoring share is `censored / approved`, with the denominator named. Every approved decision is potentially observable once its window closes; NOT_APPLICABLE is only known after that.
- Censoring is never an outcome and is never imputed.

## 6. Stratification

`POST /research/sensitivity/population` returns the totals, the selection boundary, and one cross-tabulation per information dimension:

```
information category × decision × observation status/value
```

Categories are alphabetical, which is not a ranking. No cell becomes a score, and no category is called best or worst.

## 7. Configuration comparison and difference decomposition

`POST /research/sensitivity/comparisons` goes from configuration A to configuration B one parameter at a time, in a fixed order:

1. **POPULATION**: membership changes. Every added or removed decision has a reason. It is either `EXCLUSION_RULE: <rule>` (the other cohort excluded it) or `POPULATION_DEFINITION` (window, included kinds or cohort knowledge cutoff).
2. **KNOWLEDGE_CUTOFF**
3. **HORIZON**
4. **OUTCOME_DEFINITION**: event and threshold.
5. **INFORMATION_GROUPING**: moves decisions between groups. By construction it changes no outcome.

Each decision whose result changes is attributed to the first step at which it changes. Each step reports its totals, so the population-level accounting is visible.
`unattributedDifferences` lists any decision whose A and B results differ but that no step accounts for. It is empty whenever the decomposition is complete.
The order is a convention: an interacting change may be attributed to an earlier parameter under a different order, but no change is lost.
The decomposition explains changes in the **dataset**, not in anyone's behaviour. A difference between two descriptive proportions is not a statistical finding.

## 8. Temporal boundaries

`POST /research/sensitivity/boundaries` takes an explicit `window` (an ISO-8601 duration of at most 366 days). It lists every event within that window of:
- the decision time (compared with `effective_at`);
- the outcome cutoff (compared with `effective_at`);
- the knowledge cutoff (compared with `recorded_at`).

Each entry gives the signed offset in microseconds, BEFORE/AT/AFTER, and whether the event is part of the result.
It also lists decisions whose outcome cutoff lies within the window of the knowledge cutoff: moving the cutoff across it switches CENSORED and OBSERVED.
Events recorded after the cutoff are listed so the reader can see what a slightly later cutoff would add, and they are marked as outside the result. This is a diagnostic, not a predictive feature.

Boundary semantics, pinned by `OutcomeEvaluatorBoundaryTest` at microsecond precision:

| case | result |
|---|---|
| event exactly at the outcome cutoff | counts (inclusive) |
| 1 µs before the cutoff | counts |
| 1 µs after the cutoff | does not count |
| recorded exactly at the knowledge cutoff | known (inclusive) |
| recorded 1 µs after the knowledge cutoff | unknown |
| effective before (or exactly at) the decision, recorded after it | not an outcome of the decision |
| effective after the decision, recorded before it | impossible: `lending_event_not_future_dated` rejects it |
| knowledge exactly at the outcome cutoff | window complete |
| knowledge 1 µs before the outcome cutoff | censored |
| correction 1 µs before / at its recording | unknown / known |

## 9. Timestamp precision: a permanent rule

PostgreSQL `timestamptz` keeps microseconds and **rounds** finer values on write. For example, `.123456789` is stored as `.123457`. Java clocks carry nanoseconds.
Phase 8 found on the live database that a report computed from a nanosecond cutoff could not be reproduced from the stored, rounded one. The tests had used a whole-second clock and could not see it.

The rule now: any instant that is both stored and used in a computation passes through `DatabaseTime.normalize` (truncation to microseconds, so a cutoff never moves later) or `DatabaseTime.now(clock)`, **before** it is hashed, stored or used.
The regression tests store a nanosecond cutoff and prove it reproduces. They also show that an un-normalized computation cannot be reproduced from what is stored.

## 10. Reproducibility

`research_sensitivity_report` (append-only) stores:
- the kind;
- the complete normalized configuration and its hash;
- the input hash: every definition's stored and content hash, plus the membership hash of every cohort used;
- the calculation version (`SENSITIVITY_V1/OUTCOME_EVALUATOR_V1`);
- the report and its hash;
- `evaluated_at`, which is not in any hash.

`GET /reports/{id}/reproduction` recomputes and compares all three hashes plus the calculation version.
- A definition altered in place changes the input hash.
- A tampered snapshot is excluded from membership, which changes the population.

Either way the reproduction reports `identical: false`, so the change can't pass silently.

## 11. Isolation

Research reads the banking record and writes only `research_*` tables. Two tests enforce this:
- a fingerprint of every non-research table, taken before and after every sensitivity operation;
- a check that no research service or controller depends on a banking write service (loans, corrections, ledger, payments, accounts, customers, parties, deposits, financial facts).

Decision snapshots are verified by hash on every use.

## 12. What the experiments establish, and what they cannot

On the synthetic history they establish the **mechanics**:
- how the horizon changes censoring and which outcomes a question includes;
- how the knowledge cutoff changes observability;
- that declined decisions stay unknown and counterfactual approvals stay UNOBSERVED;
- that grouping changes description only;
- that no research parameter touches snapshots or lending history.

They cannot establish:
- what is true of real borrowers;
- whether any difference between groups or configurations is statistically meaningful or causal;
- anything about declined applicants.

## API (`/api/v1/research/sensitivity`)

| method | path | purpose |
|---|---|---|
| POST | `/horizons` | `{configuration (no horizonDays), horizonsDays: [...]}` |
| POST | `/knowledge-cutoffs` | `{configuration (no knowledge), cutoffs: [{label, knownAt}], includeDecisionTime}` |
| POST | `/comparisons` | `{a, b}`: difference decomposition |
| POST | `/boundaries` | `{configuration, window}`: temporal boundary events |
| POST | `/population` | configuration: selection boundary, censoring, cross-tabulations |
| GET | `/reports/{id}`, `/reports/{id}/reproduction` | read; reproduce |
