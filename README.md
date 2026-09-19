# Bank 4.0 Core Financial System (research phases 1–8: ledger, banking domain, payments, lending, point-in-time history, counterfactual evaluation, credit information, outcome research)

A first-principles **research** core banking foundation built in **Java 21**, **Spring Boot**, and **PostgreSQL**.
It is not production banking software; see [docs/ledger.md](docs/ledger.md) §10–12 for what is simplified and what is missing.

---

## 1. Architectural Philosophy & First Principles

Bank 4.0 is designed as a living financial system. Consequential financial state must be governed by strict accounting invariants, absolute correctness, deterministic transaction ordering, idempotency, and complete auditability.

### Key Financial Primitives & Invariants

1. **Explicit Integer Money (`Money` & `CurrencyUnit`)**
   - Floating-point arithmetic (`float`, `double`) is strictly forbidden for financial balances.
   - Money is represented as explicit 64-bit integer counts of minor units (e.g., cents for USD/EUR, paise for INR, whole units for JPY) tied to ISO-4217 currency definitions.
   - Arithmetic uses exact overflow detection (`Math.addExact`, `Math.subtractExact`).

2. **Authoritative Double-Entry Ledger as System of Record**
   - Balances are **not** arbitrary mutable numbers. All value movements are authoritative, balanced `JournalEntry` records composed of two or more immutable `Posting` legs.
   - Money is neither created nor destroyed: for every transaction, total debits equal total credits ($\sum \text{debits} - \sum \text{credits} = 0$).
   - Reconciliations prove system consistency via direct SQL checks (`LedgerReconciliationService`).

3. **Separation of Banking Products and Ledger Accounts**
   - Products (such as `DepositAccount`) represent legal contracts with customers (e.g., overdraft limits, product code, status).
   - Deposit accounts do **not** hold balance fields. Instead, each deposit account owns exactly one `LIABILITY` ledger account. Balances are transactional derived projections (`LedgerAccountBalance`).

4. **Database-Level Invariants & Append-Only Triggers**
   - The PostgreSQL database enforces business and accounting invariants directly:
     - `wbank_forbid_mutation`: Rejects `UPDATE` or `DELETE` on append-only posting tables.
     - `journal_entry_guard`: Ensures journal entries are immutable apart from reversal linkage.
     - `posting_entry_must_balance`: Deferred constraint trigger validating $\sum \text{signed\_amount\_minor} = 0$ at transaction commit.
     - `balance_respects_floor`: Database check constraint preventing unauthorized negative balances or overdraft breaches.

   - `posting_chain_guard` / `balance_must_match_ledger` (V6): each posting's running balance is verified against its predecessor, and the balance projection must equal the tip of that chain at commit, so a balance cannot be set without the postings that justify it.

5. **Request Idempotency & Provenance Audit Outbox**
   - Journal requests carry optional idempotency keys, bound to a SHA-256 fingerprint of the request: a replay returns the original entry, a key reused for a different request is rejected (409). Concurrent duplicates are serialised with a PostgreSQL advisory lock; `journal_entry_idempotency_key_unique` is the database backstop. (`idempotency_record` is reserved and currently unused.)
   - Every financial state change emits a structured event into an append-only `audit_event` transactional outbox with correlation IDs and actor attribution (`HUMAN`, `SYSTEM`, `SERVICE`, `AGENT`).

---

## 2. Technology Stack & Rationale

- **Java 21+**: Strong type safety, records for immutable domain models, virtual threads, high-performance concurrency primitives.
- **Spring Boot 3.x**: Production-ready REST framework, dependency injection, and declarative transaction management (`@Transactional`).
- **PostgreSQL 16+**: System of record supporting ACID transactions, serializable isolation semantics, check constraints, JSONB audit structures, and deferred constraint triggers.
- **Flyway**: Versioned, reproducible SQL migrations (`V1__reference_data.sql` through `V13__outcome_research.sql`).
- **Modular Monolith**: Enforces strong domain boundaries (Platform, Customer, Deposit, Ledger, Payments) within a single compile-time target, leaving clear seams for future microservice extraction if needed.

---

## 3. Deliberate Phase 1 Exclusions

To maintain focus on core financial correctness, the following layers are deliberately excluded from Phase 1 and reserved for subsequent research phases:
- AI agents, LLMs, and autonomous decision-making loops
- Kafka / asynchronous message brokers (the `audit_event` outbox pattern supports future event bus streaming without core rework)
- Neo4j knowledge graphs & ontology services
- Machine learning risk models & simulation engines

---

## 4. API Endpoints

Lifecycle changes are named actions (`POST .../activate`), never a writable status field. See [docs/domain.md](docs/domain.md).

### Parties (`/api/v1/parties`)
- `POST /persons`, `POST /organizations`: Register a legal entity (no relationship, no money)
- `GET /{id}`

### Customers (`/api/v1/customers`)
- `POST /` `{partyId}`: Open a relationship (starts `PENDING`)
- `POST /{id}/activate` | `/suspend` | `/reactivate` | `/close` (close requires all accounts closed)
- `GET /{id}`, `GET /`

### Products (`/api/v1/products`)
- `GET /`: Catalogue; `POST /{code}/withdraw`: stop new sales

### Accounts (`/api/v1/accounts`)
- `POST /` `{customerId, productCode, currency, overdraftLimit?}`: Open a deposit account (`PENDING`, no ledger position yet)
- `POST /{id}/activate` | `/freeze` | `/unfreeze` | `/close` (status mirrored into the ledger account)
- `GET /{id}`: Account with its ledger-derived balance; `GET /api/v1/customers/{id}/accounts`
- `POST /{id}/cash-deposits`, `POST /{id}/cash-withdrawals` (`Idempotency-Key` required)

### Loans (`/api/v1/loans`)
See [docs/lending.md](docs/lending.md). No endpoint edits terms, balances, schedules or repayment status.
- `POST /`: Apply (terms only; no money moves)
- `POST /{id}/approve` | `/decline` (recorded credit decisions with rationale) | `/cancel`
- `POST /{id}/disburse`: Originate and release principal; the schedule becomes binding
- `POST /{id}/repayments` (`Idempotency-Key` required): Amounts due, or the exact early-settlement amount
- `POST /{id}/default`: Declare default (only at ≥ 90 days past due)
- `POST /servicing?asOf=`: End-of-day interest accrual and delinquency evaluation for the book
- `GET /{id}`: Contract, schedule vs actual, contractual vs ledger positions, delinquency, decisions

### Lending history (`/api/v1/history`, `/api/v1/credit-policies`)
See [docs/lending-history.md](docs/lending-history.md). Point-in-time: `asOf` = business time, `knownAt` = knowledge cut-off (defaults to `asOf`).
- `GET /history/loans/{id}?asOf=&knownAt=`: The loan as it stood at `asOf`, as known at `knownAt` (built only from the immutable event history)
- `GET /history/loans/{id}/events?knownAt=`: The ordered history
- `GET /history/parties/{partyId}/loans?asOf=`: A borrower's loans at a point in time
- `GET /history/decisions/{decisionId}?outcomeKnownAt=`: Evidence → decision → action, plus an explicitly separate outcome
- `GET /history/datasets/credit-decisions?horizon=P180D&knownAt=`: Reproducible, leakage-safe decision/outcome dataset (with SHA-256)
- `POST /history/events/{eventId}/corrections`: Append a correction (never rewrites history)
- `GET|POST /credit-policies`: Versioned credit rules (append-only, never retroactive)
- Loan `approve`/`decline` accept `{"rationale", "evidence": {...}}`; every decision stores a hashed snapshot and cites its policy version

### Counterfactual research (`/api/v1/research`)
See [docs/counterfactual.md](docs/counterfactual.md). A research instrument: it reads the banking record and writes only immutable `research_*` records. It never changes a decision, outcome or policy, and never scores or recommends policies.
- `POST /policies`: Publish a human-defined research policy version (never in force for live lending)
- `POST /counterfactuals` `{decisionId, policyCode, policyVersion}`: What that policy would have decided, from the decision's verified snapshot only
- `GET /counterfactuals/{id}/reproduction`: Recompute from the recorded input and compare hashes
- `POST /policy-replays` `{label, decidedFrom, decidedTo, policyCode, policyVersion}`, `GET /policy-replays/{id}`: Actual vs counterfactual for a labelled population
- `POST /policy-replays/{id}/outcome-evaluations` `{horizon, outcomeKnownAt}`: Observed outcomes (approved only), censoring, unknowable declined outcomes; causal conclusion always `NOT_ESTABLISHED`

- `GET /information-report?label&decidedFrom&decidedTo[&knownAt]`: What the bank knew at each decision (income, obligations, completeness), plus a leakage experiment labelled `INVALID_FOR_RESEARCH`

### Outcome research (`/api/v1/research`)
See [docs/outcome-research.md](docs/outcome-research.md). Descriptive only: what was observed after decisions, within explicit horizons, knowledge cutoffs and censoring. No prediction, ranking, recommendation or causal claim; declined applicants' outcomes are never inferred; a counterfactual decision's outcome is always `UNOBSERVED`.
- `POST /outcome-definitions`, `POST /cohorts`, `GET /cohorts/{code}/{version}/membership`
- `GET /decisions/{id}/outcome`, `GET /decisions/{id}/outcome-timeline`
- `POST /outcome-reports`, `GET /outcome-reports/{id}`, `GET /outcome-reports/{id}/reproduction`, `GET /cohorts/{code}/{version}/knowledge-comparison`
- `POST /policy-replays/{id}/outcome-boundary`

### Credit information (`/api/v1`)
See [docs/credit-information.md](docs/credit-information.md). Append-only, bitemporal borrower facts with provenance (DECLARED / VERIFIED; absent = UNKNOWN), and a deterministic affordability calculation that is INDETERMINATE when information is missing.
- `POST /parties/{partyId}/financial-facts`, `GET /parties/{partyId}/financial-facts?asOf&knownAt`, `GET /financial-facts/{id}/provenance`
- `POST /parties/{partyId}/affordability-assessments`, `GET /affordability-assessments/{id}`, `GET /affordability-assessments/{id}/reproduction`
- `GET /history/decisions/{decisionId}/financial-information`: The snapshot's information section as captured (v1 snapshots: `NOT_CAPTURED`)

### Payments (`/api/v1/payments`)
See [docs/payments.md](docs/payments.md). Account-to-account movement exists only as a payment.
- `POST /` `{debtorAccountId, creditorAccountId, amount, currency, remittanceInformation?, executeImmediately?}` + `Idempotency-Key`: record, validate, authorise (hold funds) and by default settle. Refused instructions are recorded as `REJECTED`.
- `GET /{id}`: instruction, status, full event trail, hold, settlement and ledger postings
- `POST /{id}/execute` | `/cancel` | `/reversal`; `POST /expirations?asOf=`
- `GET /api/v1/accounts/{id}/balances`: ledger, reserved and available balance

### Ledger Domain (`/api/v1/ledger`)
- `GET /api/v1/ledger/accounts/{id}`: Get chart of accounts item
- `POST /api/v1/ledger/accounts`: Open an internal (bank-owned) ledger account
- `GET /api/v1/ledger/accounts/{id}/balance`: Balance projection plus a from-scratch recomputation from postings
- `POST /api/v1/ledger/entries`: Post a balanced ADJUSTMENT journal (`Idempotency-Key` header required; amounts as decimal strings)
- `GET /api/v1/ledger/entries/{id}`: Get journal entry & postings
- `POST /api/v1/ledger/entries/{id}/reversal`: Reverse a manual ADJUSTMENT entry (product entries are corrected through their module)
- `GET /api/v1/ledger/reconciliation`: Run system-wide ledger consistency report

### Audit Domain (`/api/v1/audit`)
- `GET /api/v1/audit/events`: Query audit outbox events by aggregate or correlation ID

---

## 5. Verification & Running Locally

### Prerequisites
- Docker & Docker Compose
- JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`)
- Maven 3.8+

### Quickstart

1. **Start Infrastructure (PostgreSQL)**:
   ```bash
   docker compose up -d
   ```

2. **Run Full Test Suite** (needs Docker: every database test runs against a disposable PostgreSQL 16 Testcontainer, not the compose database):
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn clean test
   ```

3. **Start Core Banking Backend**:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn spring-boot:run
   ```

4. **Verify Health Endpoint**:
   ```bash
   curl http://localhost:8080/actuator/health
   ```
