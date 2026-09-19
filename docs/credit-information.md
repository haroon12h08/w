# W — Credit Information Completeness & Affordability State

Status: research implementation, phase 7. Schema: Flyway `V12`. Builds on [lending-history.md](lending-history.md)
and [counterfactual.md](counterfactual.md).
Code: `information` (`FinancialFact`, `FactSelection`, `FinancialInformationService`, `AffordabilityCalculator`,
`AffordabilityService`, `InformationController`) and `research/InformationResearchService`.

> **The question.** What did the bank actually know about a borrower's financial position when it
> made a credit decision? Which facts were verified, which were declared, and which were missing?

This phase records what the bank knew. It does **not** estimate what it did not know. Nothing here
predicts, scores or ranks borrowers.

---

## 1. Information model

`borrower_financial_fact` is one append-only, bitemporal table of observations about a party.

| kind                    | types                                                                 | carries                           |
|-------------------------|-----------------------------------------------------------------------|-----------------------------------|
| `INCOME`                | SALARY, SELF_EMPLOYMENT, PENSION, BENEFITS, RENTAL, INVESTMENT, OTHER | amount, currency, frequency       |
| `RECURRING_OBLIGATION`  | LOAN, CREDIT_CARD, RENT, CONTRACTUAL_PAYMENT, OTHER                   | amount, currency, frequency, outstanding |
| `OBLIGATION_DISCLOSURE` | ALL_RECURRING_OBLIGATIONS                                             | nothing: the statement that the list is complete |
| `EMPLOYMENT`            | EMPLOYED, SELF_EMPLOYED, UNEMPLOYED, RETIRED, STUDENT, OTHER          | start date                        |

Every observation also has:
- a `series_id` (all observations of the same underlying thing),
- `status` ACTIVE or ENDED,
- `applies_from` (optional),
- `provenance`, `source` and `evidence_reference`,
- `verifies_fact_id` (optional),
- `effective_at`, `recorded_at` and the recorder.

Types are CHECK-constrained lists, so adding a new type needs a new migration.

A change is a new observation in the same series. Examples: a new salary, an ended obligation, or a verification. Rows are never updated or deleted.
Loans held at W itself are not recorded here. They come from the lending history (`PointInTimeService.borrowerAt`).

## 2. Provenance and verification

| provenance | meaning                                   | stored as                                  |
|------------|-------------------------------------------|--------------------------------------------|
| DECLARED   | stated by the borrower or an officer      | a row                                      |
| VERIFIED   | checked against evidence                  | a row; `evidence_reference` is required    |
| DERIVED    | computed by W from other facts            | only in outputs, e.g. months since employment start |
| UNKNOWN    | no observation exists                     | the absence of a row; never a default value |

A verification is a new VERIFIED row in the same series, with `verifies_fact_id` pointing at the fact it verifies.
The database enforces this:
- VERIFIED rows need evidence.
- A verification must be in the same series as its target and recorded after it.
- A series never changes party or kind.

`GET /financial-facts/{id}/provenance` returns the whole series.

## 3. Temporal visibility

A fact is visible at `(asOf, knownAt)` iff `effective_at ≤ asOf` **and** `recorded_at ≤ knownAt`.
The rules around this:
- `effective_at ≤ recorded_at` is a database CHECK, so nothing is recorded as having become true in the future.
- `recorded_at` is always the bank's clock.
- Within a series, the current observation is the latest by `effective_at`, then `recorded_at`, then `seq`.

A fact can be known now but only apply later. Example: a contract signed today for a job starting next month.
Such a fact is **visible** (it is effective now) but carries `applies_from` > asOf. The calculation lists it under
`futureIncomeExcluded` / `futureObligationsExcluded` and never counts it.

## 4. Affordability calculation (`AFFORDABILITY_V1`)

The calculation is pure. It takes an `Input`:
- the version,
- asOf and knownAt,
- the currency,
- the proposed monthly payment,
- the visible current facts,
- W's own active or defaulted loans as of asOf, excluding the subject loan.

All amounts are `long` minor units. The rounding rules are fixed, and each one is conservative:

| quantity | rule |
|---|---|
| monthly income from annual | `floor(annual / 12)` (never overstate income) |
| monthly obligation from annual | `ceil(annual / 12)` (never understate obligations) |
| W's own loans | the annuity instalment of their terms (`AnnuitySchedule.instalment`) |
| debt-service ratio | `ceil(total × 10000 / income)` in basis points, computed exactly with `BigInteger` |
| residual capacity | `income − total` (may be negative) |

Two bases are reported:
- **VERIFIED_INCOME** uses verified income only.
- **DECLARED_INCLUSIVE** uses verified plus declared income.

Each basis is `DETERMINATE` or `INDETERMINATE`. When it is indeterminate, the explicit reasons are:
- `NO_VERIFIED_INCOME` / `NO_INCOME`
- `ZERO_INCOME`
- `OBLIGATIONS_NOT_KNOWN_COMPLETE`
- `CURRENCY_MISMATCH` (no currency conversion is attempted)

An indeterminate basis has no ratio.

## 5. Missing information

- Absent income makes the income state `NONE`, and its value is `null`, **not zero**.
- External obligations are `KNOWN_COMPLETE` only if an `OBLIGATION_DISCLOSURE` is visible.
- Without a disclosure, obligations are `PARTIALLY_KNOWN` (some recorded) or `UNKNOWN` (none recorded). In both cases the
  external total is `null`. The recorded sum is reported separately as a lower bound (`recordedExternalIsLowerBound`).
- Completeness:
  - **COMPLETE** means verified income > 0 and obligations are `KNOWN_COMPLETE`.
  - **INSUFFICIENT** means no income of any provenance.
  - **PARTIAL** covers everything else.
  - `missing` names each unmet requirement.
  - Completeness is a statement about information, not about risk.

## 6. Decision snapshots v2

Every decision (approve, decline or default declaration) now does two things:
- It records an `affordability_assessment` with purpose `DECISION`.
- It writes a `credit-decision-snapshot/v2` containing a `financialInformation` section. That section holds:
  - the calculation version, asOf and knownAt (both the decision time)
  - every visible observation
  - the input with its hash
  - the output with its hash

The v2 snapshot is written in the same transaction as the decision.

Version-1 snapshots are not modified. They have no `financialInformation`, and readers report `NOT_CAPTURED`.

## 7. Phase 6 compatibility

`DecisionFacts` gains three fields: `verifiedMonthlyIncomeMinor`, `debtServiceRatioBps` and `informationCompleteness`.
- They are omitted from JSON when null. A v1 snapshot therefore produces byte-identical counterfactual input, and hashes identical to Phase 6.
- `Phase6CompatibilityGoldenTest` pins the input and output hashes captured on the Phase 6 commit.
- The two new policy rules are `DEBT_SERVICE_RATIO_WITHIN_LIMIT` and `INFORMATION_COMPLETE`. They exist only in policies that set `maxDebtServiceRatioBps` / `requireCompleteInformation`.
- `LENDING_CREDIT_POLICY` v1 is unchanged.
- Under an information-requiring policy, a v1 snapshot is never PASS. Its missing fact gives INDETERMINATE for the ratio rule and FAIL for the completeness rule.

## 8. Research

`GET /api/v1/research/information-report?label&decidedFrom&decidedTo[&knownAt]` counts, over credit decisions in the window:
- snapshot schemas, and decisions with information not captured
- income state
- obligation state
- completeness
- basis determinacy
- indeterminate reasons

It also returns one row per decision.

The dataset (`/api/v1/history/datasets/credit-decisions`, now schema `credit-decision-dataset/v2`) adds an
`informationState` block to each row. For v1 snapshots the block is `captured=false`.

The **leakage experiment** in the same report recomputes each decision at its own asOf, but with knowledge moved to `knownAt`.
It counts the decisions whose information state would change. It is labelled `INVALID_FOR_RESEARCH`, because its
only purpose is to show how much hindsight would contaminate a naive analysis.

## 9. Reproducibility

An assessment stores:
- the version
- asOf and knownAt
- the canonical input (the fact ids and full fact values are part of it)
- the input SHA-256
- the output
- the output SHA-256, which covers the output plus the input hash

`GET /affordability-assessments/{id}/reproduction` recomputes from the stored input alone and compares the hashes.

## 10. What the system cannot know

- Whether a DECLARED amount is true, or whether a VERIFIED document was genuine.
- Anything not recorded. An empty obligation list without a disclosure means "unknown", never "none".
- Obligations at other lenders beyond what was disclosed. A disclosure is itself only DECLARED or VERIFIED.
- Income volatility, household expenses, taxes or living costs. None are modelled.
- Values in other currencies. They are reported as conflicts, not converted.
- Whether a more complete decision would have been a *better* one. No causal or predictive claim is made.

## API

| method | path | purpose |
|---|---|---|
| POST | `/api/v1/parties/{partyId}/financial-facts` | record an observation (amounts are decimal strings) |
| GET | `/api/v1/parties/{partyId}/financial-facts?asOf&knownAt` | visible observations, current per series, count not yet visible |
| GET | `/api/v1/financial-facts/{id}/provenance` | the full series |
| POST | `/api/v1/parties/{partyId}/affordability-assessments` | compute and record (`ENQUIRY`); knownAt may not be in the future |
| GET | `/api/v1/affordability-assessments/{id}` | stored assessment |
| GET | `/api/v1/affordability-assessments/{id}/reproduction` | recompute and compare hashes |
| GET | `/api/v1/history/decisions/{decisionId}/financial-information` | the snapshot section as captured, plus the integrity check |
| GET | `/api/v1/research/information-report` | descriptive counts and the leakage experiment |
