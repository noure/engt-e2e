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
  - [x] US-04-1 — A certified Nominal Balance is recorded, a Work Item is created, EVT-BalanceReceived is emitted (verified green against the live platform on 2026-09-26, after the `findProfileByAccount` fix — see "What is covered so far"; EVT-BalanceReceived itself is not observed directly, see the feature file's own NOTE)
  - [x] US-04-1 — A redelivered event whose Work Item is PERSISTED is a no-op (at-least-once redelivery) (verified green against the live platform on 2026-09-26)
  - [ ] US-04-5 — A parked Balance is attached and computed when the Contract of its Account opens
- **sc-05-daily-accrual-computation.feature** (4)
  - [x] US-05-1 — The terms of a Value Date are resolved fresh (nominal "intake to Snapshot") (verified green against the live platform on 2026-09-26; scoped down to one Charge, no WHT/SOLID — see the feature file's own NOTE on the CARTHAGE client stub gap)
  - [x] US-05-1 — The credit day produces CREIN / WHT / SOLID at the amounts of the spec's running example (scoped down: CREIN only, matches the running example's own 60.0000000000/day; WHT/SOLID deferred, see above)
  - [x] US-05-1 — The three Snapshots are persisted with identifiers, Generation 1, and their full proof (scoped down to one Snapshot — see above)
  - [ ] US-05-1 — Persistence updates Running Totals, Posted Deltas, Last Processed Value Date, Work Item PERSISTED (deferred: needs the accrual-pull endpoint, which belongs to the later US-05-7/US-11-1 batch — see the feature file's own NOTE)
- **sc-06-missing-rate-recovery.feature** (3, plus one built beyond the story-map's own e2e scope — see below)
  - [x] US-06-1 — A Missing Rate defers the Work Item to RETRY_PENDING (1st leg of "missing rate to fallback") (verified green against the live platform on 2026-09-26, twice — see "What is covered so far")
  - [x] US-06-2 — When the retry still finds nothing, the Fallback Rate applies (verified green, twice)
  - [x] US-06-2 — The Fallback Rate computes provisional Snapshots and opens a Provisional Charge (last leg) (verified green, twice)
  - [x] US-06-2 (AC-29.1, built on request though story-map.md §6.7 marks it "no" for e2e — "in-service; the exhausted leg is the E2E") — The single retry finds the Rate Fixing and resumes as an ordinary run without a Provisional Charge (verified green, twice)
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

## Blocking defect found this session (2026-09-26): every balance intake crashes — RESOLVED

**Resolved later the same session (2026-09-26).** `interest-servicing` commit `e15e9a5`, "Fix:
findProfileByAccount's missing second hop caused every balance intake to NPE", was merged and the
`c-ice-interest-servicing-1` container was rebuilt and redeployed from the branch carrying the fix,
confirmed healthy (`http://localhost:9102/actuator/health` → 200) before re-verification. The
daily-accrual chain (`sc-04-daily-balance-intake.feature`, `sc-05-daily-accrual-computation.feature`)
was then re-run against the live platform, twice, both green with fresh `E2E`-prefixed test data each
time (14 steps then 18 steps including the full suite, 0 failures) — see "What is covered so far" for
the verified detail. **The two permanently-retrying poisoned `interest-balance-intake` offsets flagged
below are a separate, still-open concern** — they are pre-existing messages from before the fix, not
something this session's re-verification could clear (Kafka topics are append-only); still worth the
platform owner's attention. The original diagnosis is kept below verbatim for the audit trail.

**The entire daily-accrual chain (US-04-1, US-05-1, and by extension US-06-1/06-2) was blocked on the
build prior to the fix above.** This is exactly the class of bug this project exists to catch — invisible to
each service's own internal Cucumber ITs, which mock the peer instead of calling it for real — so it is
reported here in full rather than silently worked around.

**Root cause**: `2-interest-servicing/provider/.../client/ContractPricingClient.findProfileByAccount`
(`provider/src/main/java/com/bnpp/itg/tas/ido/cice/interestservicing/provider/client/ContractPricingClient.java`,
the `findProfileByAccount` method) calls 1-CP's real `GET /api/profiles/by-account/{id}` and maps the
answer straight through `toProfile(JsonNode)` — the SAME mapper `findProfile(profileId)` uses for `GET
/api/profiles/{id}`. But the two endpoints answer different shapes: `by-account` returns 1-CP's narrow
`ProfileReference` (`profileId`, `bankId`, `status` only — confirmed against `1-contract-pricing-manager-api.yaml`
and against `ProfileController.getProfileByAccount`, which explicitly returns `ProfileReference`), while
`toProfile` unconditionally reads `currency` and calls `Objects.requireNonNull` on it inside
`ContractProfile`'s constructor. Every real balance intake therefore throws a `NullPointerException:
currency` inside `RecordBalanceIntakeService.record` step 4 (BR-204), for every Account, on the very
first Balance ever delivered for it — not something specific to this suite's fresh `E2E`/`ZZ` test data.
`interest-servicing/CLAUDE.md` (around the UC-64 entry) itself describes `findProfileByAccount` as a
"two-hop path" — implying the intended design already knew a second call to `GET /api/profiles/{id}`
(the endpoint that DOES carry `currency`) was needed to get the full picture; the implementation is
missing that second hop.

**Impact observed live**: `BalanceIntakeListener`'s error handler (`KafkaConfiguration`, by design —
"retries forever with backoff", `ExponentialBackOff` capped at 60s, NFR-40) means the exception does not
drop the message: the partition seeks back to the same offset and retries it forever. Two of this
session's test runs each published one real `interest-balance-intake` record before this was diagnosed;
both are now permanently retrying every 60s on the live `interest-servicing` container until the bug is
fixed and the container restarted (or the consumer group's offset is advanced past them) — flagging this
so the platform's owner is aware; not something this project can undo (Kafka topics are append-only, and
restarting the shared container was judged too disruptive to do unilaterally, and would not clear the
poisoned offset either — the messages would simply be retried again from the same point).

**What this session did NOT do**: modify `interest-servicing`'s source. That repo is not this project's
and had unrelated work in progress in its own working tree at the time (`git status` showed uncommitted
changes to `SettleIfDueService`/`SnapshotRepository` and others, on `feature/spec-completion`) — fixing
`findProfileByAccount` belongs to that repo's own maintainers, not to a same-session drive-by edit from
the e2e suite.

**Everything else needed no further changes once the fix landed**: `sc-04-daily-balance-intake.feature`
and `sc-05-daily-accrual-computation.feature`, their step definitions (`steps/BalanceIntakeSteps.java`,
`steps/AccrualComputationSteps.java`), the shared `support/DailyAccrualFixture.java` (opens a fresh
Contract and prices one Charge via a validated Derogation — see its own Javadoc for why not a catalogue
Default Condition), `support/BalanceIntakeEvents.java` (the real `evt-balance-intake.v1` payload
builder) and the `registerDerogation`/`listDerogations`/reference-data additions to the clients below —
all ran green, unmodified, on the first re-run after the platform was redeployed. Per this project's own
rule ("never commit a journey you haven't actually run green"), this was held back until that
re-verification; it is now committed (see "What is covered so far").

## Missing-rate recovery (sc-06): two more upstream fixes verified, journey built and verified green (2026-09-26)

Two blockers this project's own prior research had identified were fixed upstream between sessions,
verified live before building on them:

1. **WireMock's CARTHAGE/MCR stubs matched the wrong path/query param** (`/carthage/rate-fixings/...?date=`
   instead of the real `/carthage/api/rates/...?valueDate=`, same pattern for MCR) — every live rate/limit
   read-back 404'd regardless of index/account. Fixed in `c-ice-platform` commit `36531e9`. Verified live:
   `curl http://localhost:8089/carthage/api/rates/ESTR?valueDate=2026-09-01` now returns
   `{"rate":"0.03125000"}`.
2. **`contract-pricing-manager`'s `getConditionsBundle` ignored the retry's own `fallback=true` parameter** —
   `interest-servicing` already sent it on a retry's conditions-bundle read, but 1-CP's contract had no such
   parameter and always refused with `RATE_UNAVAILABLE`, so the entire "retry still missing -> apply
   Fallback Rate -> provisional Snapshot" half of BR-230 was dead. Fixed in `contract-pricing-manager`
   commit `55246ae` (branch `feature/spec-completion`): `getConditionsBundle` now honors `fallback=true`,
   resolving the nearest earlier Rate Fixing with `provenance=FALLBACK` when the live read still misses, or
   `ERR-461` if no earlier fixing exists either. Rebuilt, redeployed, `http://localhost:9101/actuator/health`
   confirmed 200 before this session's own re-verification.

**Timing**: production defaults for `IS_RETRY_WINDOW` / `IS_DEADLINE_AFTER_CUTOFF` (PT14H / PT21H, BR-227,
OQ-18/19) make the retry unobservable in a real-time e2e run. Both were already wired in
`interest-servicing`'s own `application.yml` but never passed through `c-ice-platform/docker-compose.yml`.
Added to the `interest-servicing` service's `environment:` block there (`IS_RETRY_WINDOW: PT20S`,
`IS_DEADLINE_AFTER_CUTOFF: PT40S`), redeployed, health confirmed — see `c-ice-platform` commit `dc2a659`.
The retry channel's own nack-wait is a hardcoded 30s (`RateLookupRetryListener`, not env-configurable), so
the retry is actually observed roughly 30-40s after the deferral regardless of how far below that
`IS_RETRY_WINDOW` is set — a 90s polling timeout is used throughout this journey's steps for margin.

**Two reference-data gaps found while building this journey** (both bridged the same narrow,
non-business-logic way as the four already documented under "Known gap: reference-data seeding" below):

- `catalogue.rate_index` (the Shared Index vocabulary a FLOATING/BENCHMARK Slab's `indexCode` must exist
  in, BR-113) has no REST feed and was empty on the live platform. Bridged with
  `ReferenceDataBridge.ensureRateIndex`, always picking an `external_index_code` WireMock's CARTHAGE stub
  does not recognise (`carthage-rate-fixing.json` only matches `EURIBOR-3M`/`ESTR`/`SOFR`) so the Missing
  Rate this journey needs is deterministic and independent of which Value Date is used — see
  `DailyAccrualFixture`'s Javadoc.
- **Not a gap, but a real trap**: `benchmarkable` is `catalogue.charge_type.benchmarkable_flag` itself
  (BR-103 — "the traits a Product Charge carries are those of the Charge"), NOT a per-product override, even
  though `declareProductCharges`'s request body accepts its own `benchmarkable` field (silently ignored by
  the live check, `ERR-142`). Reusing `CREIN` (already seeded `false` forever by every FIXED-rate journey,
  and `ON CONFLICT DO NOTHING`) for a FLOATING Slab always answers `409 CHARGE_NOT_BENCHMARKABLE` — this
  journey uses its own Charge code, `CREFL`, seeded benchmarkable from the start
  (`ReferenceDataBridge.ensureChargeType`'s new fourth parameter).

**One permission gap found**: `GET /api/provisional-charges` answers `403 FORBIDDEN` (`ERR-235`) without an
`X-Permissions: provisional-charge.read` header — `X-User-Id` alone is not enough. `ProvisionalChargeController`'s
own Javadoc documents this as the header Apigee would set in production; `InterestServicingClient.listProvisionalCharges`
now sends both headers. Worth remembering for any future US-06-4 (Operations closing a case) journey too.

**Confirmed NOT a gap** (initially suspected one while reading the code, disproved by actually running the
journey — per this project's own rule of verifying against the live platform rather than trusting static
analysis alone): `catalogue.rate_decision_rule` (BR-216's Rate Decision Rule table) is empty on the live
platform with no feed either, but this is by design — `RateDecisionTable.of` in 3-Interest Calculation
falls back to its own hardcoded nine normative rows "when the bundle carries none... the spec default of
this service" (`RateDecisionTable`'s own Javadoc). No SQL bridge was needed or attempted for it.

**The three e2e-scoped scenarios of `story-map.md` §6.7** (`sc-06-missing-rate-recovery.feature`) —
Missing Rate defers to `RETRY_PENDING` (US-06-1), the Fallback Rate applies and opens a Provisional Charge
(US-06-2, AC-29.2/AC-30.1/AC-30.2) — plus one more built on explicit request even though the story-map
marks it "no" for e2e (AC-29.1, "the single retry finds it, resumes normally") — all ran green against the
live platform, twice in a row with fresh `E2E`-prefixed test data each time (7 scenarios / 31 steps for the
whole suite including sc-02/04/05, 0 failures both times).

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

**Two verified-green journeys, the daily-accrual chain**: US-04-1, "A certified Nominal Balance is
recorded and its Work Item runs to completion" plus its at-least-once-redelivery companion
(`sc-04-daily-balance-intake.feature`), and US-05-1, "The credit day resolves fresh terms, computes the
Charge and persists the Snapshot with its proof" (`sc-05-daily-accrual-computation.feature`) — the
walking-skeleton proof the mandate calls out: a real HTTP-delivered Contract, a real Kafka balance
intake, 2-Interest Servicing reading 1-Contract & Pricing Manager's real ConditionsBundle, 2-IS calling
3-Interest Calculation's real `/api/v1/compute`, and the result persisted back in 2-IS, all observed
purely through real HTTP reads and a real Kafka publish.

Blocked earlier this session on `interest-servicing`'s `ContractPricingClient.findProfileByAccount`
NPE-ing on every real balance intake (see "Blocking defect found this session" — now resolved). Re-run
against the live platform on 2026-09-26 after the fix (`interest-servicing` commit `e15e9a5`) was
deployed: green twice in a row (14 steps, then 18 steps for the full suite including US-02-1, 0
failures), each with fresh `E2E`-prefixed test data. Scoped down per the feature files' own NOTEs: one
Charge (CREIN) only, no WHT/SOLID (blocked on the separate CARTHAGE client stub gap below), and no
assertion on Running Totals/Posted Deltas/Work Item PERSISTED (needs the accrual-pull endpoint, a later
US-05-7/US-11-1 batch).

## Next batch (suggested)

sc-06-missing-rate-recovery.feature (US-06-1/US-06-2) is now done — see "Missing-rate recovery" above.
With a real Snapshot chain now provable through both the ordinary path (sc-04/sc-05) and the
deferral/fallback path (sc-06), the next natural slices are **US-08-1 (back value to next Generation)** —
`DailyAccrualFixture` and `BalanceIntakeEvents` should mostly carry over (a `backValue: true` Balance
instead of a fresh one) — or **US-10-1/10-4 (settlement)**, unblocked since a real Snapshot chain exists to
settle.

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

**A fourth table, found while building the daily-accrual journeys**: `interestservicing.bank_reference`
— 2-Interest Servicing's OWN Bank/Cut-off replica, separate from `contract.bank` above, table comment
"fed by the reference feed; seeded until 1-CP serves it" (`02-ledger-tables.yaml`). No feed, no seed data
either (confirmed by reading the changelog — no `INSERT`). A balance intake for a Bank absent here is
refused `UNKNOWN_BANK` even when `contract.bank` already knows it — observed live before this was added.
`support.ReferenceDataBridge.ensureInterestServicingBankReference` bridges it the same narrow, idempotent
way as the other three.

**A fifth table, found while building sc-06-missing-rate-recovery.feature**: `catalogue.rate_index` — the
Shared Index vocabulary a FLOATING/BENCHMARK Slab's `indexCode` must exist in (BR-113, `ERR-130` on
`registerDerogation`, `ERR-437` on `registerRateFixing`/`getFallbackRate`). No REST feed anywhere in
`1-contract-pricing-manager-api.yaml`, confirmed empty by direct query. `support.ReferenceDataBridge.ensureRateIndex`
bridges it the same way, always choosing an `external_index_code` outside WireMock's three known CARTHAGE
codes (`EURIBOR-3M`/`ESTR`/`SOFR`) so the Missing Rate that journey needs is deterministic. See
`DailyAccrualFixture`'s Javadoc.

## Known gap: the Work Item status endpoint

`GET /api/work/{workId}` (2-Interest Servicing) is unusable with its own documented work identifier
format (`eventId + "/" + profileId`, `WorkId.value()`): a literal `/` does not match the single-segment
`{workId}` path template (404, confirmed live — Spring routes it as two path segments against a
one-segment template) and an encoded `%2F` is rejected by Tomcat before routing at all (400 "invalid
character", confirmed live). The route itself is reachable — a slash-free id correctly reaches
`WorkId.parse` and answers `400 VALIDATION_FAILED` — so this is not a deployment issue, it is the
endpoint's own path design: it can never be called with a real two-part identifier over plain HTTP
(would need the id URL-encoded with a server configured to accept encoded slashes, or a query parameter,
or two separate path segments). This suite observes intake and computation outcomes through
`GET /api/profiles/{id}/positions` and `GET /api/profiles/{id}/snapshots` instead — both real, working,
off-hot-path reads that do not need this endpoint. Worth raising as a follow-up: either enable
`ALLOW_ENCODED_SLASH` for this route, or change the path to take `eventId` and `profileId` as two
segments or query parameters.

## Known gap: the CARTHAGE client stub

`GetConditionsBundleService.resolveClient` (1-Contract & Pricing Manager) reads Client facts — country,
tax-exemption — with a LIVE call to CARTHAGE (BR-435, "never replicated, read fresh") for every bundle
resolution that has at least one resolved Charge. `c-ice-platform/wiremock/mappings/` has stubs for
CARTHAGE's rate-fixing endpoints (`carthage-rate-fixing*.json`) but **none for its client endpoint** — no
`/carthage/clients/...` mapping exists at all, so every client lookup 404s. `resolveClient` treats a miss
as "no Client, no tax exemption known" and silently returns an empty Tax Conditions list — no error, no
anomaly — so a Charge taxed by the spec (CREIN's WHT/SOLID in the running example) computes with no tax
at all on the live platform today, for every contract, not only this suite's fresh test data. Worth
raising as a follow-up: add a `carthage-client.json` wildcard stub (the same pattern as the existing
`mcr-limit.json`) answering a country code and `taxExempt: false` for any client id.

## Project layout

```
src/test/java/com/bnpp/itg/tas/ido/cice/e2e/
  clients/   one HTTP client class per service (ContractPricingManagerClient, InterestServicingClient,
             InterestCalculationClient, SettlementComputationClient, RestitutionClient)
  support/   Config (base URLs, env-overridable), HttpSupport + ApiResponse (java.net.http wrapper,
             exact-decimal JSON parsing), JsonSupport/JsonObject (Jackson), KafkaSupport (real
             producer/consumer helpers), AccountIntakeEvents / BalanceIntakeEvents (build real
             evt-account-lifecycle.v1 / evt-balance-intake.v1 payloads), ReferenceDataBridge (the
             documented SQL exceptions above), DailyAccrualFixture (shared Contract+Derogation setup
             for the sc-04/sc-05 journeys, not yet verified green — see "Blocking defect")
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
