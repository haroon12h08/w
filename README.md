<p align="center">
  <img src="w-art.png" alt="W Banking Corporation" width="100%">
</p>

<h1 align="center">W Banking Corporation</h1>

<p align="center">
  <em>Est. on first principles &nbsp;·&nbsp; Every entry balanced &nbsp;·&nbsp; Nothing erased</em>
</p>

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-3b2f22?style=flat-square">
  <img alt="Spring Boot 3.5" src="https://img.shields.io/badge/Spring%20Boot-3.5-3b2f22?style=flat-square">
  <img alt="PostgreSQL 16" src="https://img.shields.io/badge/PostgreSQL-16-3b2f22?style=flat-square">
  <img alt="Flyway" src="https://img.shields.io/badge/migrations-Flyway-3b2f22?style=flat-square">
</p>

---

## A Word from the House

W is a core banking system in the tradition of the ledger houses. Its guiding principle is
that a bank's first duty is to keep an honest book.

Every sum it holds is entered twice and balances to the minor unit. No record, once written,
is altered: a correction is entered beside the original and never over it. Every decision the
house makes is sealed with the facts it knew at that hour, so that any later inquiry can see
exactly what was known, and when.

> W is a research foundation, not a licensed or production banking system. It is built to
> study correctness, memory and accountability in financial software. See
> [docs/ledger.md](docs/ledger.md) for what is deliberately simplified and what is absent.

---

## The Articles of the House

**I. Money is counted, never estimated.**
Amounts are 64-bit integers of an ISO-4217 currency's minor unit. Floating-point arithmetic is
not permitted anywhere near a balance, and every sum is checked for overflow.

**II. The ledger is the book of record.**
All value moves as balanced journal entries of immutable postings. Debits equal credits in
every entry, and the database refuses to commit any entry that does not balance. Account
balances are derived from the postings and verified against them. They are never simply set.

**III. What is written stays written.**
Postings, lending history, decision snapshots, borrower information and research records are
append-only, and PostgreSQL triggers enforce it. Errors are put right by reversal or by an
appended correction.

**IV. Every decision keeps its reasons.**
Each credit decision records the policy version in force, the rules evaluated, and a
hash-sealed snapshot of what the bank knew at that moment. It can be reconstructed exactly,
at any later date.

**V. The house distinguishes when a thing happened from when it was known.**
Lending history and borrower information are bitemporal. Any account, loan or borrower can be
viewed *as it stood* at a given moment *as it was known* at a given moment, without hindsight
leaking into the past.

**VI. Nothing is done twice by accident.**
Requests carry idempotency keys bound to a fingerprint of their content. A faithful repeat
returns the original result; a key reused for a different request is refused.

**VII. Every act is attributed.**
Every consequential change emits an audit event with its actor, the actor's kind (human,
system, service or agent) and a correlation identifier.

---

## The Departments

| Department | Business conducted |
|---|---|
| **Ledger** | Chart of accounts, balanced journals, reversals, verified balance projections, system-wide reconciliation |
| **Parties & Customers** | Persons and organisations, customer relationships, lifecycle by named action only |
| **Products & Accounts** | Deposit products and accounts, each backed by its own ledger account; opening, freezing and closure under rule |
| **Payments** | Instruction, validation, authorisation with funds held, settlement, cancellation, expiry and reversal, each step on record |
| **Lending** | Applications, policy-governed approval and decline, disbursement, annuity schedules, interest accrual, repayment allocation, delinquency, default and settlement |
| **Credit Policy** | Versioned, human-authored lending rules with effective dates. Every decision cites the version it was made under |
| **Lending History** | Bitemporal event history of every loan, point-in-time reconstruction, corrections appended beside the original |
| **Borrower Information** | Income, obligations and employment with provenance (declared or verified, with evidence) and deterministic affordability |
| **Research Office** | Counterfactual policy replay, outcome observation with censoring, research cohorts, and sensitivity analysis. It reads the book of record and never writes to it |
| **Audit** | An append-only outbox of every consequential change |

The Research Office is descriptive by charter. It does not predict, score, rank, recommend
policy or claim causation. It never assigns an outcome to a declined applicant or to a decision
that was never taken.

---

## Architecture

W is a **modular monolith**: one deployable, with firm boundaries between the departments
above.

```
backend/src/main/java/com/wbank/
├── platform      money, time, audit, request context, error handling
├── ledger        journals, postings, balances, reconciliation
├── party         persons and organisations
├── customer      customer relationships
├── product       product catalogue
├── account       accounts, funds and holds
├── deposit       deposit ledger postings
├── payment       payment lifecycle
├── obligation    lending, credit policy, lending history
├── information   borrower financial information and affordability
└── research      counterfactuals, outcomes, cohorts, sensitivity
```

| Concern | Choice |
|---|---|
| Language | Java 21: records for immutable values, strong typing |
| Framework | Spring Boot 3.5: REST, dependency injection, declarative transactions |
| Book of record | PostgreSQL 16: ACID, check constraints, deferred constraint triggers, JSONB |
| Schema | Flyway: every change a versioned, reviewed migration |
| Verification | JUnit 5 and Testcontainers against a disposable PostgreSQL, with migration regressions over production-like data |

Invariants live in two places, the domain code and the database, so that a defect in one is
caught by the other.

---

## Opening the Doors

### Requirements

- JDK 21
- Maven 3.8 or later
- Docker (for PostgreSQL and for the test suite)

### Start the vault

```bash
docker compose up -d
```

### Examine the books

Every database test runs against a disposable PostgreSQL 16 container, never the development
database.

```bash
cd backend
mvn clean test
```

### Open for business

```bash
cd backend
mvn spring-boot:run
curl http://localhost:8080/actuator/health
```

Migrations apply on startup. The service listens on port `8080`.

---

## The Counting House: API

All endpoints are under `/api/v1`.
- Amounts are decimal strings in the currency's major unit (for example `"125.50"`).
- Lifecycle changes are named actions, never writable status fields.
- Money-moving requests take an `Idempotency-Key` header.

| Area | Base path | Principal operations |
|---|---|---|
| Parties & customers | `/parties`, `/customers` | register, activate, suspend, close |
| Products & accounts | `/products`, `/accounts` | open, activate, freeze, close; balances (ledger, reserved, available) |
| Payments | `/payments` | instruct, execute, cancel, reverse; full event trail |
| Loans | `/loans` | propose, approve, decline, disburse, repay, service, declare default; schedule and quote |
| Credit policy | `/credit-policies` | publish versions; the version in force at any instant |
| Lending history | `/history` | point-in-time loan state, decision reconstruction, corrections, datasets |
| Borrower information | `/parties/{id}/financial-facts`, `/affordability-assessments` | record facts, view them as known at a time, trace provenance, assess and reproduce |
| Research | `/research` | counterfactuals, policy replays, outcome reports, cohorts, sensitivity analyses; every artefact reproducible by hash |
| Ledger | `/ledger` | accounts, journal entries, reversals, reconciliation |
| Audit | `/audit/events` | query by aggregate or correlation |

Each area's full contract is set out in its chapter of the documentation.

---

## The Archive

| Chapter | Subject |
|---|---|
| [Ledger](docs/ledger.md) | Double-entry model, invariants, reconciliation, known limitations |
| [Domain](docs/domain.md) | Parties, customers, products and accounts |
| [Payments](docs/payments.md) | Payment lifecycle, holds, settlement and reversal |
| [Lending](docs/lending.md) | Loans, schedules, accrual, allocation, delinquency and default |
| [Lending History](docs/lending-history.md) | Bitemporal history, credit policy versions, decision snapshots |
| [Counterfactual Evaluation](docs/counterfactual.md) | Replaying decisions under alternative policies |
| [Credit Information](docs/credit-information.md) | Borrower facts, provenance, affordability and completeness |
| [Outcome Research](docs/outcome-research.md) | Observation windows, censoring, cohorts and selection |
| [Research Sensitivity](docs/research-sensitivity.md) | How results depend on horizon, knowledge cutoff and population |

---

<p align="center">
  <sub><em>W Banking Corporation: a house that keeps its word by keeping its books.</em></sub>
</p>
