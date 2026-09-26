@SC-05 @feature:daily-accrual-computation
Feature: Daily accrual computation (end-to-end)

  This file replays only the scenario(s) that `90-stories/story-map.md` §6.5 assigns "yes" to the e2e
  project for sc-05-daily-accrual-computation.feature — see README.md for the full scope boundary.
  Every other scenario of that feature is covered by 3-Interest Calculation's and 2-Interest Servicing's
  own internal Cucumber ITs (TS-05-*), not replayed here.

  This is the walking-skeleton proof the mandate calls out: a real HTTP-delivered Contract, a real
  Kafka balance intake, 2-Interest Servicing reading 1-Contract & Pricing Manager's real
  ConditionsBundle, 2-IS calling 3-Interest Calculation's real `/api/v1/compute`, and the result
  persisted back in 2-IS — observed here purely through real HTTP reads, exactly what no single
  service's own internal ITs can prove.

  # NOTE — WHT / SOLID (the spec's running example daily taxes on CREIN) are NOT asserted here: Tax
  # computation needs the Client's Tax Scheme, resolved by a LIVE call to CARTHAGE
  # (GetConditionsBundleService.resolveClient, BR-435 — "the Client facts are never replicated, read
  # fresh"), and the platform's WireMock stub set (c-ice-platform/wiremock/mappings/) has NO mapping for
  # CARTHAGE's client endpoint at all (only carthage-rate-fixing*.json exist) — every client lookup
  # 404s, so `client` resolves to null and `taxConditions` silently stays empty (GetConditionsBundleService,
  # no error raised). This is a platform-wide gap, not specific to this suite's fresh test data: even the
  # spec's own CLI-4471 running example would hit it today. Registering `catalogue.tax_scheme` /
  # `catalogue.tax` for a fresh test country would not fix this — the missing piece is the WireMock
  # mapping (an addition analogous to the existing `mcr-limit.json` wildcard stub). Flagged as a follow-up
  # in README.md "Known gap: the CARTHAGE client stub"; this scenario prices a single Charge (CREIN) with
  # no tax so its Raw Amount is fully verifiable against the real chain regardless.
  #
  # The Charge is priced by a validated Derogation, not a catalogue Default Condition — see
  # DailyAccrualFixture's Javadoc for why (catalogue.day_basis_rule has no DEFAULT-scope feed for a fresh
  # country). "Terms resolved fresh" (UC-23) is exercised implicitly: the computed Raw Amount below can
  # only match if 2-IS read the Derogation's real FIXED rate and real Day Basis at compute time, not a
  # cached or default value.
  #
  # Running Totals / Posted Deltas / Last Processed Value Date are NOT asserted here: reading them for
  # real requires either a second accrual day (to see the total move) or the accrual-pull endpoint
  # (`GET /api/accruals`), which FLIPS the Snapshot's own status as a side effect (ACCRUAL_IN_PROGRESS ->
  # ACCRUAL_DONE) — that transition belongs to the US-05-7 / US-11-1 pull-and-flip journey, a later batch,
  # not to this one. This scenario instead confirms the Balance Ledger projection (`getPositions`) that
  # the same intake produced, which sc-04 also asserts independently.
  @UC-23 @UC-25 @UC-26 @AC-23.1 @AC-25.1 @AC-26.1 @AC-26.2 @nominal
  Scenario: The credit day resolves fresh terms, computes the Charge and persists the Snapshot with its proof
    Given a fresh Contract is OPEN and priced with a validated Derogation on the Charge "CREIN" at a fixed rate of 2% with day basis "360"
    When C-CLIPS delivers for that Account the real certified Balance of today, amount "1080000.00" EUR
    Then within 30 seconds the Snapshot of the Charge "CREIN" for today's Value Date has Raw Amount "60.0000000000" Generation 1 and status "ACCRUAL_IN_PROGRESS"
    And the Snapshot carries its proof: day basis 360 and at least one slab detail
    And the position of the Contract shows today's Value Date with Balance "1080000.00" EUR, not carried
