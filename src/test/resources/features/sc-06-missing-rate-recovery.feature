@SC-06 @feature:missing-rate-recovery
Feature: Missing rate recovery (end-to-end)

  This file replays only the scenario(s) that `90-stories/story-map.md` §6.7 assigns "yes" to the e2e
  project for sc-06-missing-rate-recovery.feature — see README.md for the full scope boundary. Every
  other scenario of that feature is covered by 2-Interest Servicing's, 1-Contract & Pricing Manager's and
  3-Interest Calculation's own internal Cucumber ITs (TS-06-*), not replayed here.

  # NOTE — the Shared Index (`catalogue.rate_index`) referenced by a FLOATING Slab has NO REST feed and NO
  # seed data on the live platform (confirmed empty by direct query) — a SIXTH instance of the reference-data
  # gap documented in README.md, bridged here the same narrow way (ReferenceDataBridge.ensureRateIndex). Its
  # `external_index_code` is always something WireMock's CARTHAGE stubs do NOT recognise
  # (`carthage-rate-fixing.json` only matches EURIBOR-3M / ESTR / SOFR): the live read-back this suite does
  # not control 404s deterministically, on any Value Date, so a Missing Rate is produced without depending on
  # whether a real Index happens to have a fixing for today already.
  #
  # These two fixes, both verified live before this file was built (see README.md), are what make this
  # journey reachable at all: WireMock's CARTHAGE/MCR stubs matched the wrong path/query param (fixed in
  # c-ice-platform commit 36531e9), and 1-CP's getConditionsBundle ignored the retry's own fallback=true
  # parameter (fixed in contract-pricing-manager commit 55246ae). IS_RETRY_WINDOW / IS_DEADLINE_AFTER_CUTOFF
  # are shortened to PT20S / PT40S in c-ice-platform's docker-compose.yml for this suite's own sake — the
  # retry channel's own nack-wait is a hardcoded 30s (RateLookupRetryListener), so the retry is actually
  # observed roughly 30-40s after the deferral, never the production 14h/21h.
  @UC-28 @AC-28.1 @nominal
  Scenario: A Missing Rate defers the Work Item to RETRY_PENDING
    Given a fresh Contract is OPEN and priced with a validated Derogation on the Charge "CREFL" at a FLOATING rate on a fresh Index with day basis "360"
    When C-CLIPS delivers for that Account the real certified Balance of today, amount "1080000.00" EUR, on the Missing Rate Contract
    Then no Snapshot of the Charge "CREFL" for today's Value Date appears within 10 seconds

  # NOTE — AC-29.2/AC-30.1/AC-30.2: no earlier fixing is registered for the target Value Date itself (the
  # first read, fallback=false, still finds nothing — as above), but one 5 days earlier is, so the retry's
  # fallback=true read (BR-230) resolves on it instead of refusing. See README.md for whether this ran green:
  # 3-Interest Calculation's Rate Decision Rule table (`catalogue.rate_decision_rule`, BR-216) is a further,
  # newly-discovered gap on the live platform — empty, no feed, no seed — this scenario's own first real use
  # of a FLOATING Slab (sc-04/sc-05 only ever priced a FIXED one) may be blocked on it downstream of the
  # Fallback Rate resolving correctly.
  @UC-29 @AC-29.2 @UC-30 @AC-30.1 @AC-30.2 @wiring
  Scenario: When the retry still finds nothing the Fallback Rate applies and opens a Provisional Charge
    Given a fresh Contract is OPEN and priced with a validated Derogation on the Charge "CREFL" at a FLOATING rate on a fresh Index with day basis "360"
    And a Rate Fixing of "2%" is registered for that Index 5 days before today
    When C-CLIPS delivers for that Account the real certified Balance of today, amount "1080000.00" EUR, on the Missing Rate Contract
    Then within 90 seconds the Snapshot of the Charge "CREFL" for today's Value Date is provisional
    And within 15 seconds a Provisional Charge for that Contract is OPEN with the Fallback Rate

  # NOTE — AC-29.1 is explicitly "no" in story-map.md §6.7 ("in-service; the exhausted leg is the E2E"), so
  # this scenario is not required e2e coverage; built anyway on request, sharing the Rate Decision Rule risk
  # noted above.
  @UC-29 @AC-29.1 @wiring
  Scenario: The single retry finds the Rate Fixing and resumes as an ordinary run without a Provisional Charge
    Given a fresh Contract is OPEN and priced with a validated Derogation on the Charge "CREFL" at a FLOATING rate on a fresh Index with day basis "360"
    When C-CLIPS delivers for that Account the real certified Balance of today, amount "1080000.00" EUR, on the Missing Rate Contract
    And a Rate Fixing of "2%" is registered for that Index for today before the retry fires
    Then within 90 seconds the Snapshot of the Charge "CREFL" for today's Value Date is not provisional
    And no Provisional Charge exists for that Contract
