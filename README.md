# c-ice-e2e

The end-to-end Cucumber test project for C-ICE. It runs against the **real, already-running**
`c-ice-platform` docker-compose stack — real HTTP calls to the five services, a real Kafka broker,
a real PostgreSQL database — never an embedded/in-process fake. Its one job is to prove the five
services genuinely **wire together** correctly; it does not re-prove business rules, which is each
service's own internal Cucumber IT suite's job.

## Scope boundary

Per `spring-docs/specs/c-ice/90-stories/story-map.md` §6 (the test placement matrix, produced by the
`test-placement` skill): every one of the spec's 613 Gherkin scenarios/outlines has exactly one row
naming which service's internal Cucumber ITs run it. A small, explicitly-justified subset — **27
scenarios**, verified against the current spec (§6.15 "Placement summary") — is *also* replayed here,
because it is either:

- a **nominal path** that spans a real service boundary (HTTP call, Kafka topic, or both), or
- a **wiring-critical path** named in story-map.md §5 (account event → Contract, intake → Snapshot,
  missing rate → fallback, back value → next Generation, demand → completion, due date →
  SettlementExecuted, due date → Cash Entry, closure → last Settlement, pull-and-flip, anomaly →
  report).

No scenario is e2e-only: every scenario replayed here keeps its service-level IT coverage too, so a
red e2e run always points at a contract mismatch or a wiring/configuration defect, never at a
business rule (that's what the 613 service-level scenarios are for). Concurrency and matrix-only
test cases have no e2e row either — see story-map.md §6 for the full reasoning.

### The 27 journeys

One `.feature` file per story-map §6 subsection that has at least one `yes` row, named the same way.
Checked = implemented **and verified green against the live platform** (see "What is covered so
far"); unchecked = planned, not yet built.

- **sc-02-contract-set-up-and-lifecycle.feature** (3)
  - [x] US-02-1 — A CREATED account event opens the Contract with the Default Product (UC-08, AC-08.1)
  - [ ] US-02-6 — A Pre-closure sets the Term Date and raises the last-calculation demand (1st leg of "closure to last Settlement")
  - [ ] US-02-6 — A closure deferred on a PENDING demand is replayed and succeeds when the demand completes
- **sc-03-negotiated-pricing.feature** (2)
  - [ ] US-03-4 — A validated retroactive Derogation raises a PENDING Recalculation Demand and one targeted call to 2-IS
  - [ ] US-03-4 — The demand completes when Interest Servicing reports the request completed
- **sc-04-daily-balance-intake.feature** (3)
  - [ ] US-04-1 — A certified Nominal Balance is recorded, a Work Item is created, EVT-BalanceReceived is emitted
  - [ ] US-04-1 — A redelivered event whose Work Item is PERSISTED is a no-op (at-least-once redelivery)
  - [ ] US-04-5 — A parked Balance is attached and computed when the Contract of its Account opens
- **sc-05-daily-accrual-computation.feature** (4)
  - [ ] US-05-1 — The terms of a Value Date are resolved fresh (nominal "intake to Snapshot")
  - [ ] US-05-1 — The credit day produces CREIN / WHT / SOLID at the amounts of the spec's running example
  - [ ] US-05-1 — The three Snapshots are persisted with identifiers, Generation 1, and their full proof
  - [ ] US-05-1 — Persistence updates Running Totals, Posted Deltas, Last Processed Value Date, Work Item PERSISTED
- **sc-06-missing-rate-recovery.feature** (3)
  - [ ] US-06-1 — A Missing Rate defers the Work Item to RETRY_PENDING (1st leg of "missing rate to fallback")
  - [ ] US-06-2 — When the retry still finds nothing, the Fallback Rate applies
  - [ ] US-06-2 — The Fallback Rate computes provisional Snapshots and opens a Provisional Charge (last leg)
- **sc-08-recalculation-and-corrections.feature** (3)
  - [ ] US-08-1 — A Back Value raises a COMPLETED Recalculation Request over its range, with Generation 2 (nominal "back value to next Generation")
  - [ ] US-08-1 — The Reversal and the creation of Generation 2 happen in one step with their events
  - [ ] US-08-3 — The completion mirrored from a Recalculation Demand carries the demand id; Contracts marks it COMPLETED
- **sc-10-settlement-execution.feature** (3)
  - [ ] US-10-1 — A due MATURITY Settlement starts with its identity and cycle bounds (1st leg of "due date to SettlementExecuted")
  - [ ] US-10-4 — The Settlement is persisted in one step with its proof and published once (nominal)
  - [ ] US-10-5 — The Contract closes after its CLOSURE Settlement (last leg of "closure to last Settlement")
- **sc-11-outbound-restitution-and-reporting.feature** (5)
  - [ ] US-11-1 — Accrual Pull of 12,480 Snapshots served in three pages, TAX Snapshots never split from their base
  - [ ] US-11-2 — The Settlement Accounting Flow carries header, lines and Tax Lines ("due date to Cash Entry")
  - [ ] US-11-2 — One Cash Entry per non-zero Settlement Entry ("due date to Cash Entry")
  - [ ] US-11-2 — A REFUSED Posting Outcome moves the Cash Entry to REFUSED
  - [ ] US-11-6 — The report of a Process Date consolidates intake and settlement Anomalies
- **sc-12-reference-data-and-external-feeds.feature** (1)
  - [ ] US-12-1 — A CREATED account event creates the Account Replica and opens the Contract (same wiring as US-02-1, asserted from the replica side — likely a light extension of the US-02-1 journey rather than a new one)

## What is covered so far (this session)

**Scaffold**: Maven project (JDK 25 via `--release 21`, matching the other C-ICE service repos'
convention), Cucumber 7.18.1 + JUnit 5 platform (same pin as the other repos), `java.net.http.HttpClient`
for real HTTP (no Spring context needed), `kafka-clients` for a real producer/consumer against the
live broker, `postgresql` JDBC used only by `support.ReferenceDataBridge` (see "Known gap" below).

**One verified-green journey**: US-02-1, "A CREATED account event opens the Contract with the Default
Product" (UC-08 / AC-08.1 / TC-137) — `src/test/resources/features/sc-02-contract-set-up-and-lifecycle.feature`.
It, in order, over real HTTP and real Kafka against the live platform:

1. Delivers a Country and a Currency through 1-CP's real reference-data feed (`POST /api/countries`,
   `POST /api/currencies`).
2. Creates a Product, declares its Charge and offered Periodicities, proposes and validates it
   (four-eyes, two distinct `X-User-Id`s) — all real `POST`/`PUT` calls to 1-CP.
3. Creates a Default Condition and a FIXED-rate version, proposes and validates it (four-eyes).
4. Publishes a real `evt-account-lifecycle.v1` CREATED record to the real `interest-account-intake`
   Kafka topic — standing in for the C-CLIPS Account inventory producer that does not run in this
   environment (see `support.AccountIntakeEvents`).
5. Polls the real `GET /api/profiles/by-account/{accountId}` and `GET /api/profiles/{profileId}`
   until the Contract is OPEN, then asserts Bank, Currency, opening date and the Product Attachment.

Run twice in a row against the live platform on 2026-09-26 (see "How to run") — both green,
each with its own freshly-generated `E2E`-prefixed test data.

**Not asserted yet**: AC-08.2 (the opening Chosen Periodicities) — see the `NOTE` above that scenario
in the `.feature` file: the current `api/v14.2/1-contract-pricing-manager-api.yaml` has no `GET` that
reads a Contract's Chosen Periodicities back. Worth a follow-up once that read exists.

## Next batch (suggested)

The next natural slice is the **daily accrual chain**: US-04-1 (balance intake) → US-05-1 (accrual
computation, all four rows) → US-06-1/06-2 only if a missing-rate scenario is convenient to stage via
the WireMock CARTHAGE stub. That is the "create a product → equip a contract → balance arrives → the
daily run computes and persists a Snapshot" backbone the mandate calls out, continuing directly from
the Contract this batch already opens. US-10-1/10-4 (settlement) is the batch after that once a
Snapshot chain exists to settle.

## Known gap: reference-data seeding

Three tables have **no REST endpoint that writes them** in the current `1-contract-pricing-manager-api.yaml`,
confirmed by reading the contract and by probing the real service (`ERR-104 UNKNOWN_CHARGE`, `409
UNKNOWN_BANK`, and a `catalogue.default_product` outbound port that is read-only in 1-CP's own domain
code):

| Table | Why it has no feed | Evidence |
|---|---|---|
| `contract.bank` | `deliverBankCalendar` only appends a calendar fact to a bank that must already exist | 409 `UNKNOWN_BANK` observed live |
| `catalogue.charge_type` | "the Charge referential — shared vocabulary of every context" (table comment); `declareProductCharges` links an existing charge, it does not create one | 409 `UNKNOWN_CHARGE`, `ERR-104`, observed live |
| `catalogue.default_product` | Outbound port `DefaultProducts` is read-only; written only by 1-CP's own H2 Cucumber test seed harness (`Seed.defaultProduct(...)`) | source reading, `domain/.../port/out/DefaultProducts.java` |

At the time this project was started the shared PostgreSQL had **zero rows** in all three tables, so
no journey that opens a Contract from an account event — automated or through the manual UI — could
run at all yet. `support.ReferenceDataBridge` bridges this with idempotent, narrowly-scoped direct SQL
inserts, documented in its Javadoc, using only distinctive `E2E`-prefixed identifiers (`E2E<hex>` bank
codes, `ZZ` as a country code — ISO 3166 reserves `ZZ` for user assignment, so it can never collide
with a real country) so it can never collide with a real Bank a human tester creates once this admin
capability exists. Every other step of every journey goes through real HTTP or real Kafka; this is the
one deliberate, narrow exception, and it never writes a row that expresses business logic (a Product,
a Condition, a Contract) — only the reference lookup a real C-CLIPS feed would have supplied. Country
and Currency, which DO have a real feed (`POST /api/countries`, `POST /api/currencies`), go through it
instead of this bridge.

**Worth raising as a follow-up** (not done in this session, since this project's job is to prove
wiring, not to design 1-CP's admin surface): an admin/reference-data REST endpoint for Bank and for
Default Product designation, matching the one Country and Currency already have.

## Project layout

```
src/test/java/com/bnpp/itg/tas/ido/cice/e2e/
  clients/   one HTTP client class per service (ContractPricingManagerClient, InterestServicingClient,
             InterestCalculationClient, SettlementComputationClient, RestitutionClient)
  support/   Config (base URLs, env-overridable), HttpSupport + ApiResponse (java.net.http wrapper),
             JsonSupport/JsonObject (Jackson), KafkaSupport (real producer/consumer helpers),
             AccountIntakeEvents (builds real evt-account-lifecycle.v1 payloads),
             ReferenceDataBridge (the one documented SQL exception above)
  steps/     Cucumber step definitions, one class per .feature file
  runner/    CucumberSuite — the JUnit 5 Suite entry point (`mvn test`)
src/test/resources/features/   one .feature file per story-map §6 subsection with an e2e row
```

## How to run

**Prerequisite**: the `c-ice-platform` docker-compose stack must already be up and healthy —
`docker ps` should show all ten containers (`contract-pricing-manager`, `interest-servicing`,
`interest-calculation`, `settlement-computation`, `restitution`, `postgres`, `kafka`, `kafka-ui`,
`wiremock`, `c-ice-ui`) as `healthy`/`Up`. This project does not start or manage the platform.

```bash
cd D:/Developpement/projects/c-ice/c-ice-e2e
mvn test
```

Base URLs default to the platform's published host ports (`http://localhost:9101`...`9105`, Kafka
`localhost:9092`, PostgreSQL `localhost:5432/cice`, WireMock `http://localhost:8089`) and are
overridable with `E2E_CP_URL`, `E2E_IS_URL`, `E2E_IC_URL`, `E2E_SC_URL`, `E2E_RS_URL`,
`E2E_KAFKA_BOOTSTRAP`, `E2E_POSTGRES_URL`, `E2E_POSTGRES_USER`, `E2E_POSTGRES_PASSWORD`,
`E2E_WIREMOCK_URL` — see `support.Config`.

Before starting a `mvn test` run, check for and kill any leftover `java.exe` from a previous attempt
first (the platform's own JVMs — the five services — must NOT be killed; only a stray Maven/Surefire
JVM from an earlier interrupted run of this project).

### Test data hygiene

The PostgreSQL instance is shared with a human tester using the UI at `http://localhost:4200`. Every
piece of data this suite creates is prefixed distinctively (`E2E-` products/conditions, `E2E<hex>`
banks, `ACC-E2E-...` accounts, `CLI-E2E-...` clients, country `ZZ`) so it is trivially recognisable
and filterable, and this suite never performs a destructive operation (no `DELETE`, no `DROP`, no
`TRUNCATE`) — only the ordinary creates a real journey would perform, plus the narrowly-scoped
reference-data inserts documented above.
