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
  - [x] US-10-1 — A due MATURITY Settlement starts with its identity and cycle bounds (1st leg of "due date to SettlementExecuted") (verified green against the live platform on 2026-09-26, after five real defects found and fixed live — see "Settlement execution (sc-10)" below; folded into the same scenario as US-10-4 below, matching US-10-1's own DoD note "no E2E scenario of its own")
  - [x] US-10-4 — The Settlement is persisted in one step with its proof and published once (nominal) (verified green against the live platform on 2026-09-26, twice in a row with fresh test data each time — scoped to interestNet, not the tax-adjusted netToSettle, see below)
  - [ ] US-10-5 — The Contract closes after its CLOSURE Settlement (last leg of "closure to last Settlement") (built this session — feature scenarios and step definitions exist in the working tree, NOT committed: blocked live on a platform-wide gap, one real defect already fixed — see "Closure to last Settlement (sc-10 US-10-5)" below)
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

## Settlement execution (sc-10): five real defects found and fixed live (2026-09-26)

Building the settlement journey (US-10-1/US-10-4, the natural next step once a real Snapshot chain
existed via sc-04/05/06) hit **five distinct, real, previously-invisible defects**, in sequence, each
blocking the whole settlement path for every Contract on this platform, not just this suite's own test
data. Four were small, clearly-scoped, high-confidence fixes and were made directly in the owning
service repo (each its own commit there, verified with that service's own unit tests plus a full
rebuild/redeploy of the live container before re-verifying here); the fifth is a genuine, deeper finding
documented but deliberately not fixed. This is exactly the class of bug this project exists to catch —
every one of them is invisible to a service's own internal Cucumber ITs, which stub the peer instead of
calling it for real.

1. **`SchedulePlanner`/`Schedule.dueDate` (1-Contract & Pricing Manager) can insert two PENDING
   `schedule_entry` rows with the same Due Date, crashing account opening.** 1-CP's schedule
   regeneration always covers a 12-month rolling horizon regardless of which Chosen Periodicity a
   Contract picks (`SchedulePlanner.regenerate`, `generationDate.plusMonths(12)`), and `Schedule.dueDate`
   shifts every SETTLEMENT Theoretical Date landing on a Saturday or a Sunday forward to the SAME
   following Monday (BR-130) — so a naive `CALENDAR 1 DAYS` SETTLEMENT Periodicity (the fastest possible
   test cycle) produces two or three PENDING rows sharing one Monday Due Date within that horizon,
   violating `uq_schedule_due` (`attachment_id, charge_code, rhythm_type, due_date`) and dead-lettering
   the whole `evt-account-lifecycle.v1` CREATED event. **Not fixed** (the correct behaviour — dedupe? cap
   to one entry per Due Date? shift further?) is a real domain design decision, not a one-line fix, so it
   is documented and worked around instead: `DailyAccrualFixture.openContractWithFixedRateChargeAndFastSettlement`
   uses a WEEKLY (`CALENDAR 7 DAYS`) Periodicity anchored on a Business Day instead — a multiple of 7 days
   recurs on the same day-of-week forever, so it never lands on a weekend and never collides, while still
   producing an immediately-due cycle on its first Theoretical Date. Two dead-lettered
   `interest-account-intake` events from this session's own diagnosis are a harmless, expected side
   effect (fresh test accountIds, never retried since the consumer dead-letters rather than retries
   forever unlike the balance-intake gap documented below).
2. **1-CP's `settlementDue` answer never carried `allocationRows` at all — every Settlement on this
   platform was permanently parked with `ERR-324 NO_ALLOCATION_AGREEMENT_IN_FORCE`, regardless of whether
   a real Allocation Agreement existed.** `SettlementDueService.decide` (1-CP) and the `SettlementDue`
   domain record had no such field; 2-Interest Servicing's own client (`ContractPricingClient.
   toSettlementDue`) and `SettleIfDueService` already expected and checked one (built ahead of the
   dependency), and a stale mirrored copy of the API in `interest-servicing`'s own resources
   (`1-contract-pricing-manager-api.yaml`) incorrectly documented it as an already-shipped "increment 9 /
   TS-09-3.1/TS-09-3.2" additive field — the REAL entrypoint `openapi.yaml` never had it, and neither did
   the domain logic. Confirmed live: a real Contract Agreement `PUT` through the real REST feed
   (`/api/profiles/{id}/allocation-agreements`), persisted and visible in `contract.allocation_agreement`,
   still made every `settlementDue` read answer with no `allocationRows` at all. **Fixed** in
   `contract-pricing-manager`: `SettlementDueService.decide` now resolves the Contract Agreement in force
   at the cycle end, else the Bank's House Agreement, else empty (BR-324/BR-329), reusing the
   already-existing, already-tested `AllocationAgreementRepository.contractAgreementInForce`/
   `houseAgreementInForce`; `SettlementDue` gained the field (with a wither, old 8-arg call sites and
   tests untouched); `ContractApiMapper`/`openapi.yaml` (`SettlementDue.allocationRows`, reusing the
   existing `AllocationAgreementRow` schema) expose it on both `settlementDue` and `settlementDueBulk`.
   Domain unit tests green; verified live (`curl .../settlement-due?processDate=...` now answers a
   populated `allocationRows`).
3. **2-Interest Servicing's own `SettlementItem` sent to 4-Settlement Computation never carried a
   Charge's direction at all — every real settlement was refused as malformed.** `SettlementItem.charges`
   did not exist as a field; `SettleIfDueService.build` always sent `List.of()` where 4-SC's own contract
   expects `charges: [{chargeCode, direction}]` (BR-345). 4-SC's `SettlementItemValidator.
   checkChargeDirections` refuses the whole item (400) the first time any Charge is involved. The data
   was already available, unused: 1-CP's own `ConditionsBundle.ResolvedCharge` already carries
   `family`/`eligibility` (BR-103's "the traits a Product Charge carries are those of the Charge",
   confirmed against `catalogue.charge_type.eligibility`, default `CREDIT_POSITIVE`). **Fixed** in
   `interest-servicing`: added `SettlementItem.ChargeDirection(chargeCode, direction)` and a `charges`
   field, populated in `SettleIfDueService.build` from `bundle.charges()` via BR-345's own words ("CREDIT
   for credit interest and bonus, DEBIT for debit interest"): a BONUS-family Charge is always CREDIT,
   else `DEBIT_NEGATIVE` eligibility is DEBIT, else CREDIT. Domain unit tests green; verified live.
4. **`ApplySettlementResultService` (2-IS, TX2) expected an `allocationId` on every settlement ENTRY that
   4-Settlement Computation's own contract never sends — every real Settlement failed
   `settlement_entry.allocation_id NOT NULL` at persistence.** 4-SC's `SettlementResultMapper.entry()`
   only ever sets `allocationId` on a LINE (`SettlementLine.allocationId`, matching the api's own
   `lines[].allocationId`), never on an Entry — `ResultEntry.allocationId()` is therefore always `null` on
   every real response. **Fixed** in `interest-servicing`: `ApplySettlementResultService.build` now looks
   an Entry's `allocationId` up by `lineRef` against its own Line's (already-correct) one, falling back to
   the Entry's own field only if a future contract version ever sends it. Domain unit tests green;
   verified live (this is the fix that finally let a Settlement actually persist).
5. **An EXEMPT Client can never be settled on this platform at all — NOT fixed, a genuine dead end
   found live, documented for a follow-up.** 4-SC's `SettlementItemValidator.checkTaxScheme` refuses any
   settlement item whose Tax Conditions list is empty (`ERR-343 TAX_SCHEME_MISSING_IN_ITEM`),
   unconditionally — no exemption carve-out. But 1-CP's `GetConditionsBundleService.getConditionsBundle`
   deliberately skips resolving ANY Tax Condition for an exempt Client (`if (client != null &&
   !client.taxExempt())`), so an exempt Client's Tax Conditions list is *always* empty by design. These
   two rules are mutually exclusive for every exempt Client, platform-wide — not specific to this suite.
   Worked around in this suite's own fixture by NOT exempting its dedicated test Client and asserting the
   real (non-exempt) outcome instead of relying on exemption to stay tax-free.
6. **A WHT rate-unit mismatch, found as a side effect of (5)'s workaround — NOT fixed, documented.**
   Registering a 25% WHT (`catalogue.tax.rate = 25`, matching 1-CP's own `checkPercentage`'s `]0, 100]`
   validation range, i.e. a percentage) on a non-exempt test Client produced a live `taxLines` amount of
   `1500.0000` (`25 × 60`, i.e. `rate` treated as a raw multiplier/fraction downstream, not divided by 100
   anywhere in the chain) and a `net_to_settle` of `-1440.0000` in `interestservicing.settlement` — wildly
   wrong for a 25% WHT on a 60.00 gross interest (expected 45.00 net). The wrongly-computed amount was
   **not** posted to any ledger entry (`getSettlements`'s own `entries` list stayed correct: one CREIN
   CREDIT of 60.00, no WHT entry at all) — only the internal `taxLines`/`net_to_settle` figures are wrong,
   and neither is reachable from 2-Interest Servicing's own `Settlement` REST schema (`interestNet` is the
   only net figure it exposes; there is no `netToSettle` field on it at all, entrypoint `openapi.yaml`).
   Not investigated further or fixed: this is a genuine computation-correctness question spanning 1-CP's
   percentage convention and 4-SC's own reading of it (`catalogue.tax.rate`/`Tax.rate` vs `Pct`/`Money`
   internals), squarely the kind of thing 4-SC's own internal Cucumber ITs should already cover for a
   real (non-e2e-only) Tax Scheme, and genuinely out of scope for a quick fix here. `sc-10-settlement-
   execution.feature`'s own scenario therefore asserts `interestNet` (the CREIN gross amount, unaffected)
   rather than any tax-adjusted figure.

**The two e2e-scoped scenarios of `story-map.md` §6.10** (US-10-1's "starts with its identity and cycle
bounds", folded into the same scenario per its own DoD note "no E2E scenario of its own"; US-10-4's
"persisted in one step with its proof and published once") ran green against the live platform, twice in
a row with fresh `E2E`-prefixed (`CLI-E2E-STL-*` Client, `E2E-TS-XE` Tax Scheme, country `XE`) test data
each time (8 scenarios / 37 steps for the whole suite including sc-02/04/05/06, 0 failures both times).
This is also the first journey to consume a real outbox topic (`interest-settlement-output`) rather than
only produce to one, and the first to use a real Tax Scheme (bridged the same narrow, idempotent,
non-colliding way as the other reference-data gaps below).

## Closure to last Settlement (sc-10 US-10-5): one real defect fixed live, two more found and BLOCKING — not committed

Building US-10-5 (`UC-14`/`UC-52`, "the Contract closes after its CLOSURE Settlement") hit three more
distinct, real, previously-invisible defects in the same family as the five sc-10 found before it. One is
fixed and verified live; the other two are genuine, deeper findings that together make **it currently
impossible for any Contract to ever close on this platform**, regardless of this suite's own scenario
design — documented here in full rather than forced, per this project's own rule. The journey's own code
(the feature scenarios, the new `ContractPricingManagerClient.preClose`/`closeProfile`/
`listRecalculationDemands`, `AccountIntakeEvents.closedAccount`, `SettlementExecutionSteps`' new step
definitions) is written and compiles, but **is not committed**: it cannot be run green against the live
platform until defect 2 below is fixed upstream, and this project's own rule is to never commit a journey
it has not actually run green. It stays in the working tree for whoever picks this batch up next.

1. **Fixed and verified live: `RecalcTriggerSender` (1-Contract & Pricing Manager) never sent the
   `X-Service-Id` header 2-Interest Servicing's own `triggerRecalculation` requires (BR-235).**
   `RecalculationController.triggerRecalculation` (2-IS) reads `request.getHeader("X-Service-Id")` and
   refuses `ERR-235` ("permission: the service identity of the Contracts context") whenever it does not
   equal 2-IS's own configured `contracts-service-id` (default `CONTRACTS-SVC`, `IS_CONTRACTS_SERVICE_ID`).
   `RecalcTriggerSender`'s `POST /api/work/recalc` never set this header at all — confirmed live:
   `contract.outbox` rows of type `RECALC_TRIGGER` were `FAILED` after exactly 1 attempt with
   `last_error = "2-IS refused the recalculation trigger: 403 FORBIDDEN"` for every Recalculation Demand
   this application had ever raised (this suite's own CLOSURE demands, and by the same code path every
   CONDITION_CHANGE/PRODUCT_CHANGE/RATE_FIXING demand too — a platform-wide gap, not specific to closure).
   Since ERR-235 answers 403 (not a 5xx), `RecalcTriggerSender`'s own Javadoc rule ("another 4xx is a
   refusal") means the row is never retried: the demand stays `PENDING` forever. Invisible to both
   services' own internal Cucumber ITs, which stub/mock the peer instead of asserting this header
   (confirmed reading `DerogationSteps`/`OutboxRelaySteps` in 1-CP's own test suite: their WireMock stubs
   match on path only). **Fixed** in `contract-pricing-manager` (commit `93881b5`, branch
   `feature/spec-completion`): a new `contract-pricing.identity.service-id` property (`CPM_SERVICE_ID`,
   default `CONTRACTS-SVC`, matching 2-IS's own default) is now sent as `X-Service-Id` on every targeted
   call. Rebuilt, redeployed, `http://localhost:9101/actuator/health` confirmed 200; re-verified live —
   the same outbox row now shows `status = SENT`, `attempts = 0`. This fix alone is real, correct, and
   already deployed, independent of the two findings below; **not** scoped down or worked around, a
   straight fix.

2. **The root blocker — NOT fixed, a genuine platform-wide gap: nothing in `contract-pricing-manager` ever
   marks a `SETTLEMENT` `schedule_entry` row `DONE` once 2-Interest Servicing actually executes the real
   Settlement.** Confirmed by reading every write path that touches `contract.schedule_entry.status`:
   `SchedulePlanner.regenerate`/`preClose` only ever write `PENDING` or `CANCELLED`
   (`ScheduleJdbcAdapter.updateStatus`, the only `UPDATE ... SET status` in the codebase) — no code path
   anywhere sets `status = 'DONE'`. 1-Contract & Pricing Manager does not even consume
   `interest-settlement-output` (`evt-settlement-executed.v1`, the event 2-Interest Servicing actually
   publishes once a Settlement executes) — confirmed by an exhaustive grep for that topic/event name across
   1-CP's own source: zero matches outside a single unrelated Javadoc reference. Reproduced live end to end
   with a real Contract (`PRF-000114`): a real MATURITY Settlement executed and persisted correctly
   (`evt-settlement-executed.v1` observed on the real topic, confirmed by this suite's own already-passing
   scenario), yet `SELECT * FROM contract.schedule_entry WHERE due_date = '2026-09-25'` still shows that
   SETTLEMENT entry `status = PENDING` — never transitioned. Two concrete, independently-observed
   consequences of this single gap:
   - **`SettlementDueService.cycleStart`/`cycleStartOf`** (BR-340's own cycle-bounds resolution) filters
     schedule entries for `status = DONE` to find the start of the NEXT cycle; finding none, ever, it always
     falls back to the Product Attachment's own `fromDate` (the Contract's opening date). For a Contract's
     very FIRST cycle this fallback is coincidentally correct (which is exactly why the already-passing
     MATURITY scenario, and the missing-rate/accrual journeys before it, never surfaced this) — but for a
     SECOND cycle it is wrong: confirmed live, `GET /api/profiles/PRF-000114/settlement-due?processDate=
     2026-09-26` (a Contract already past one executed MATURITY Settlement on 2026-09-25) answers
     `cycleStart: 2026-09-25` instead of `2026-09-26`, so the second cycle's own gather wrongly re-includes
     the FIRST cycle's already-`SETTLED` Snapshot. 2-Interest Servicing's own `SettleIfDueService` correctly
     detects this as an internally-inconsistent answer and parks the whole attempt rather than settle
     something wrong (`ERR-330 SETTLEMENT_CYCLE_NOT_READY`, `AnomalyPolicy.REPORT`, confirmed live: "Cycle
     2026-09-25 → 2026-09-26 of PRF-000114 holds an already settled snapshot (84); the settlement is
     parked") — the SAFETY NET works, but the underlying cause is this gap, and the Settlement never
     executes at all as a result.
   - **`CloseContractService.close`** reads `schedule.lastSettlementDone(contractId, termDate)` — the SAME
     "no row is ever `DONE`" gap — to decide `lastSettlementExecuted` for BR-134's own closure guard
     (`Contract.close`'s `if (!lastSettlementExecuted) throw ERR_164`). Since this is always empty,
     **`lastSettlementExecuted` is always `false`, so `Contract.close` always throws `ERR-164` and no
     Contract can ever reach `CLOSED` on this platform today**, independent of everything else in UC-14/UC-52
     being otherwise correctly wired (the Pre-closure, the CLOSED event, the demand, the CLOSURE Settlement
     itself all worked correctly in this session's own live tracing once defect 1 was fixed and the race in
     finding 3 below was designed around).

   Worth raising as a follow-up: 1-CP needs a way to learn a Settlement executed — most plausibly consuming
   `evt-settlement-executed.v1` on `interest-settlement-output` and marking the matching `schedule_entry`
   `DONE` with its `settlement_id` (the column already exists and is already read, just never written) — a
   real architectural addition, not a one-line fix, and squarely 1-CP's own domain decision to make.

3. **A second, independent defect found while tracing why demand completion still didn't self-heal defect
   2's own guard refusal — NOT fixed, a genuine Spring transaction-propagation bug.**
   `CompleteRecalculationDemandService.complete` (1-CP) — the handler UC-52's own E1 relies on to replay the
   closure once a demand completes — calls `close.close(contract.id())` as a best-effort side attempt,
   wrapped in `try { ... } catch (BusinessRuleViolation deferredAgain) { /* BR-134: defers, never blocks */ }`.
   Both `complete` and `close` are proxied use cases (`TransactionalUseCases.wrap`, `PROPAGATION_REQUIRED`
   `TransactionTemplate`s) — since `close.close(...)` is called FROM WITHIN `complete`'s own already-active
   transaction, it PARTICIPATES in (joins) that same physical transaction rather than starting a new one.
   When `close.close(...)` throws `ERR_164` (defect 2 above guarantees it always does), Spring marks the
   PARTICIPATING transaction rollback-only before re-throwing — a local `catch` cannot undo that. `complete`'s
   own code swallows the exception and returns normally, but its outer transaction then fails to commit:
   confirmed live, every one of this session's own `interest-recalculation-events` records was dead-lettered
   with `org.springframework.transaction.UnexpectedRollbackException: Transaction silently rolled back
   because it has been marked as rollback-only` (`RecalculationEventsListener`'s own log). The practical
   effect is strictly worse than BR-134's own intent ("the closure defers, it never blocks"): the ENTIRE
   completion event is lost (dead-lettered, not retried), which rolls back not just the optimistic `close()`
   attempt but the Recalculation Demand's own `COMPLETED` transition and the `processed_event` dedup row too
   — confirmed live, `contract.recalculation_demand.status` stayed `PENDING` forever and
   `contract.processed_event` never gained a single row for this channel, even though 2-Interest Servicing's
   own `recalculation_request` row genuinely reached `COMPLETED` and its own completion event was genuinely
   `SENT`. Would very likely still surface even after defect 2 is fixed, the day a demand's own re-evaluated
   closure attempt is refused for any OTHER BR-134 reason (e.g. a second PENDING demand, E14) — worth fixing
   independently, most plausibly by calling `close.close(...)` through a `REQUIRES_NEW` propagation (or
   catching further out, after `complete`'s own transaction has already committed) so a deferred closure can
   never poison the demand-completion transaction that is supposed to survive it.

**Byproduct on the shared platform**: every attempt this session made at this journey left its own test
Contract permanently `CLOSING_IN_PROGRESS` (`PRF-000084`, `085`, `094`, `095`, `104`, `105`, `114`, `115` —
all `E2E`-prefixed Banks/Accounts, per this suite's own hygiene convention) since closure can never actually
complete until defect 2 above is fixed. Harmless (no destructive operation, nothing a human tester's own
running example could collide with) but left as-is rather than force-closed, matching this project's own
rule never to perform a write this suite's real flows would not themselves perform.

**How this was diagnosed** (verified by tracing, not guessed, per this project's own rule): every claim
above is backed by a live reproduction — direct, read-only `psql` queries against the shared PostgreSQL
(`contract.outbox`, `contract.schedule_entry`, `contract.recalculation_demand`, `contract.processed_event`,
`interestservicing.recalculation_request`, `interestservicing.work_item`, `interestservicing.outbox`), a
real `GET .../settlement-due` call, and the two services' own container logs
(`docker logs c-ice-contract-pricing-manager-1`). No source file was modified for findings 2 or 3 beyond
reading them.

**Test design tried and ruled out, for the next session's benefit**: the fixture originally used a single
one-day cycle (Term Date equal to the fixture's own first Settlement due date) to sidestep needing any prior
Balance at all — this actually raced with `TriggerRecalculationService`'s own `ProfileMutex`, which locks on
the Account/Pool id in `ProcessBalanceIntakeService` but on the profileId everywhere else (including
`TriggerRecalculationService` itself), so the two never actually exclude each other (BR-212's own "never
interleaved" promise silently broken — a fourth, real, minor concurrency defect, also not fixed, also
documented here for completeness, though moot once the fixture below sidesteps it). Redesigning the fixture
to a two-day shape (an ordinary first Balance and its own MATURITY Settlement, THEN the Pre-closure with Term
Date the day after) avoided both that race and a related `TriggerRecalculationService` 404 (it needs an
existing `ProfileProgress` ledger row, refused `NOT_FOUND` — permanently, never retried — for a Contract
that never received any Balance yet) — but then hit defect 2 above, which no test-side fixture redesign can
work around.

**Update (follow-up session): the three 1-CP defects above are now fixed and verified; the journey is
still blocked, by a fourth, different, newly-found defect — the fixture is not committed.**
`contract-pricing-manager` (branch `develop`) now has, each its own commit: (1) a real
`interest-settlement-output` consumer (`SettlementExecutedListener` → `ApplySettlementExecutedService`)
that marks the matching `schedule_entry` row DONE once `evt-settlement-executed.v1` arrives — defect 2
above, closed; (2) `CompleteRecalculationDemandService`'s transaction-propagation bug fixed by moving the
best-effort closure replay out of its own transaction, into the `interest-recalculation-events` listener,
called strictly after `complete`'s own transaction commits — defect 3 above, closed (a first fix using
`PROPAGATION_REQUIRES_NEW` was tried and reverted: it broke a different, already-passing 1-CP scenario for
a genuine transaction-visibility reason, see that repo's own commit message); (3) the double-PENDING
`schedule_entry` collision of the earlier sc-10 session (line 209 above) also fixed, by design decision
(keep the latest colliding Theoretical Date per Due Date) — unrelated to closure directly but the same
batch. Full `contract-pricing-manager` reactor verified green (431/431 non-`@UC-63` Cucumber scenarios, all
unit tests, `ArchitectureTest`) before rebuilding and redeploying its live container.

Popping the stash (after resolving one trivial package-rename merge conflict from the meanwhile-landed
rename commit) and rerunning `sc-10-settlement-execution.feature`'s two closure scenarios against the
rebuilt container found and fixed one more real defect first: `SettlementExecutionSteps` published the
CLOSED account event and returned immediately (Kafka, asynchronous), with nothing awaiting 1-CP having
actually applied it before delivering the closure-date Balance right behind it — a race that could let
2-Interest Servicing's own synchronous, one-shot settle-if-due evaluation of that Balance see a stale
(not-yet-CLOSURE) `settlement-due` answer. Fixed by polling `GET .../settlement-due` for `reason=CLOSURE`
before proceeding (`ContractPricingManagerClient.settlementDue`, `SettlementExecutionSteps
.awaitClosureAppliedOn1Cp`), mirroring the existing `theContractIsWithTermDateEqual...` polling pattern
already used one step earlier in the same journey.

That fix alone was not enough — both scenarios still fail the same way (`No CLOSURE Settlement ... within
90s`) with fresh Contracts. Traced to the real root cause, **not fixable from either side without a
fixture redesign**: `SettleIfDueService.settleIfDue` (2-IS) decides whether anything is due by calling
`contractPricing.settlementDue(item.profileId(), clock.processDate())` — `clock` is 2-IS's own
`SystemBusinessClock` (the real OS wall-clock date in the live container), **not** the work item's own
Value Date and not any date derived from the Balance just processed. Confirmed live: `curl
.../settlement-due?processDate=2026-09-29` (the fixture's own Closure Date, "the day after" the cycle's
Value Date) answers `due:true, reason:CLOSURE` correctly — 1-CP's own side is completely correct — but
`interestservicing.settlement` never gains a CLOSURE row, because 2-IS is asking about
`clock.processDate()` = the real "today" (one day *before* the fixture's Closure Date) the entire time the
test runs, and the real calendar date cannot roll over inside a 90-second run. `settleIfDue`'s own comment
confirms the intended remedy is out of scope here too: `if (!due.due()) return; // ... the next Daily Run
asks again` — no scheduler in `SchedulersConfiguration` retries a cleanly-"not yet due" work item; only
parked balances and a Deadline watch are polled. This is exactly why the fixture picks "the day after" in
the first place (documented above): the *same-day* alternative was already ruled out because it races
`TriggerRecalculationService`'s own `ProfileMutex` bug (a confirmed, separate, still-unfixed 2-IS
concurrency defect) — so the fixture is caught between two independent, unfixed 2-IS-side gaps, and no
change on 1-CP's side (nor a small fixture tweak) resolves either. A genuine redesign of this journey's
timing strategy — e.g., a way to advance 2-IS's own process date for the test, or to trigger its
settle-if-due check for an explicit date rather than relying on its wall clock — is needed, and is 2-IS's
own domain decision to make, the same way defect 2 above was 1-CP's.

Per this project's own rule, the journey is **still not committed** — the stash was updated (dropped and
re-pushed, same three files plus the new `ContractPricingManagerClient.settlementDue` addition) with this
message rather than the original one, so the await-for-CLOSED-event fix is preserved for whoever continues
this next; the underlying 2-IS timing gap is not something this repository can work around on its own.

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

**One verified-green journey, settlement execution**: US-10-1/US-10-4, "A due MATURITY Settlement starts
with its identity and cycle bounds and is persisted in one step with its proof, published once"
(`sc-10-settlement-execution.feature`) — see "Settlement execution (sc-10)" above for the five real
defects this uncovered (four fixed live in `contract-pricing-manager`/`interest-servicing`, one
documented). Real HTTP-delivered Contract with a weekly SETTLEMENT Periodicity anchored on a Business
Day, a real Kafka balance intake triggering 2-Interest Servicing's own synchronous settle-if-due step
(right after TX1 commits, no separate trigger call), a real call to 4-Settlement Computation's
`/api/v1/settle`, the Settlement persisted in TX2 and published exactly once — observed through both a
real HTTP read (`getSettlements`) and a real consumed record on `interest-settlement-output`
(`evt-settlement-executed.v1`), the first journey of this suite to consume a real outbox topic. Run
twice in a row against the live platform on 2026-09-26, both green (8 scenarios / 37 steps for the whole
suite, 0 failures both times), each with fresh test data (dedicated `CLI-E2E-STL-*` Client ids, country
`XE`, Tax Scheme `E2E-TS-XE`). Scoped to `interestNet` (not the tax-adjusted `netToSettle`, which has no
REST field at all and whose live WHT computation is a separate, undiagnosed finding — see point 6 above).

## Next batch (suggested)

sc-06-missing-rate-recovery.feature (US-06-1/US-06-2) and sc-10-settlement-execution.feature (US-10-1/
US-10-4) are done — see their own sections above. US-10-5 (closure to last Settlement) was attempted this
session: its code is written (feature scenarios, step definitions, client methods — see "Closure to last
Settlement (sc-10 US-10-5)" above) but **not committed**, blocked live on a platform-wide gap in
`contract-pricing-manager` (nothing ever marks a `SETTLEMENT` schedule entry `DONE`, so `Contract.close`
always refuses `ERR-164` — no Contract can close on this platform today). The natural next slices, roughly
in order of expected effort:

- **US-10-5 (closure to last Settlement) — RESUME ONCE THE PLATFORM GAP IS FIXED, don't rebuild from
  scratch.** The working tree already has everything: `sc-10-settlement-execution.feature`'s two new
  scenarios, `SettlementExecutionSteps`' new step definitions (including the two-day fixture shape that
  sidesteps the OTHER two findings — the `ProfileMutex` race and the `TriggerRecalculationService` 404),
  `ContractPricingManagerClient.preClose`/`closeProfile`/`listRecalculationDemands`,
  `AccountIntakeEvents.closedAccount`. Once 1-CP is wired to mark a `schedule_entry` `DONE` on
  `evt-settlement-executed.v1` (or however the platform owner decides to close that gap), rebuild/redeploy
  `contract-pricing-manager`, re-run this suite, and — if genuinely green — commit it, updating this
  README's own checklist and "What is covered so far".
- **US-08-1 (back value to next Generation)** — `DailyAccrualFixture` and `BalanceIntakeEvents` should
  mostly carry over (a `backValue: true` Balance instead of a fresh one). Unaffected by anything found in
  this session's own closure investigation (a different code path).
- **sc-12 (reference-data-and-external-feeds)** — likely a light extension of the already-verified US-02-1
  journey asserted from the Account Replica side instead (worth checking whether it needs its own
  scenario at all, per the story-map's own note).

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

**A sixth table, found while building sc-10-settlement-execution.feature**: `catalogue.tax_scheme`
(`scheme_code` PK, `country_code` with a `uq_scheme_country` UNIQUE constraint — one scheme per country
ever) has no creation endpoint either: `POST /api/taxes` (`upsertTax`) is a genuine feed for a TAX ROW of
an EXISTING scheme, but refuses `ERR-449 UNKNOWN_TAX_SCHEME` outright when the scheme does not already
exist (confirmed reading `UpsertTaxService.upsert`'s first line), and nothing else creates that row.
`support.ReferenceDataBridge.ensureTaxScheme`/`ensureTax` bridge both (the Tax row too, not just the
scheme: `catalogue.tax` keys on `(tax_code, from_date)` with a plain INSERT, no upsert semantics, so a
rerun on the same calendar day would collide on that primary key with no clean 409 to tolerate through
the real feed — bridged with `ON CONFLICT DO NOTHING` and a fixed, far-past `fromDate` instead). Given
the one-per-country constraint, this suite uses a dedicated country (`XE`) exclusively for the settlement
journey's own Client, never the shared `ZZ` every other journey's no-tax scope relies on and never a real
country a human tester's own running example might still need (BANK-FR/FR's own TS-FR) — see
`DailyAccrualFixture.openContractWithFixedRateChargeAndFastSettlement`'s Javadoc for the full reasoning,
including why an EXEMPT Client was tried first and ruled out (a genuine, separate platform dead end, see
"Settlement execution (sc-10)" point 5 above).

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

## Known gap: the CARTHAGE client stub — RESOLVED (2026-09-26), with one new dead end found

`GetConditionsBundleService.resolveClient` (1-Contract & Pricing Manager) reads Client facts — country,
tax-exemption — with a LIVE call to CARTHAGE (BR-435, "never replicated, read fresh") for every bundle
resolution that has at least one resolved Charge. `c-ice-platform/wiremock/mappings/` had stubs for
CARTHAGE's rate-fixing endpoints (`carthage-rate-fixing*.json`) but none for its client endpoint,
confirmed by every client lookup 404-ing (`resolveClient` treats a miss as "no Client, no tax exemption
known", silently returning an empty Tax Conditions list — no error, no anomaly).

**Resolved this session** while building sc-10-settlement-execution.feature, which needed a resolved
Client at all (4-Settlement Computation refuses a settlement item with no `clientId`, unlike the accrual
path which tolerates a missing Client silently): added `carthage-client.json`, a wildcard stub (the same
pattern as the existing `mcr-limit.json`) answering `countryCode: "FR"`, `category: "RETAIL"`, `taxExempt:
false`, `usPerson: false` for any Client id — this also benefits the spec's own running example (CLI-4471,
BANK-FR) the next time it is exercised manually through the UI, which previously got the same 404/no-tax
silence. A second, higher-priority mapping (`carthage-client-settlement.json`) answers ONLY this suite's
own `CLI-E2E-STL-*` Client ids with a dedicated country (`XE`) instead, so this suite's own settlement
journey never claims `catalogue.tax_scheme`'s one-per-country slot for the real `FR` (see "Known gap:
reference-data seeding", sixth table, above).

**One new dead end found while wiring this up**: an EXEMPT Client (`taxExempt: true`) can never actually
be settled on this platform — see "Settlement execution (sc-10)" point 5 above for the full account
(1-CP skips resolving any Tax Condition for an exempt Client by design; 4-SC's own validator
unconditionally refuses an empty Tax Conditions list regardless of exemption). Not fixed; this suite's own
settlement journey works around it by using a non-exempt test Client instead.

## Project layout

```
src/test/java/com/bnpparibas/cib/cice/e2e/
  clients/   one HTTP client class per service (ContractPricingManagerClient, InterestServicingClient,
             InterestCalculationClient, SettlementComputationClient, RestitutionClient)
  support/   Config (base URLs, env-overridable), HttpSupport + ApiResponse (java.net.http wrapper,
             exact-decimal JSON parsing), JsonSupport/JsonObject (Jackson), KafkaSupport (real
             producer/consumer helpers), AccountIntakeEvents / BalanceIntakeEvents (build real
             evt-account-lifecycle.v1 / evt-balance-intake.v1 payloads), ReferenceDataBridge (the
             documented SQL exceptions above), DailyAccrualFixture (shared Contract+Derogation setup for
             the sc-04/05/06/10 journeys, including the settlement-specific fast-cycle/Tax Scheme variant)
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
banks, `ACC-E2E-...` accounts, `CLI-E2E-...` clients, country `ZZ`; the settlement journey additionally
uses `CLI-E2E-STL-...` clients, country `XE` and Tax Scheme `E2E-TS-XE` — a second, disjoint
reserved-for-private-use country code, kept apart from the shared `ZZ` precisely so it never adds tax to
every other journey's no-tax scope) so it is trivially recognisable and filterable, and this suite never
performs a destructive operation (no `DELETE`, no `DROP`, no `TRUNCATE`) — only the ordinary creates a
real journey would perform, plus the narrowly-scoped reference-data inserts documented above.
