@SC-10 @feature:settlement-execution
Feature: Settlement execution (end-to-end)

  This file replays only the scenario(s) that `90-stories/story-map.md` §6.10 assigns "yes" to the e2e
  project for sc-10-settlement-execution.feature — see README.md for the full scope boundary. Every
  other scenario of that feature (netting several Charges, rounding, the allocation matrix, the
  empty-cycle/parked-settlement/redelivery guards) is covered by 1-Contract & Pricing Manager's,
  2-Interest Servicing's and 4-Settlement Computation's own internal Cucumber ITs (TS-10-*), not
  replayed here.

  # NOTE — scoped down like sc-04/sc-05/sc-06 before it, plus real, live-found gaps this journey is the
  # first to reach. See README.md "Settlement execution (sc-10): five real defects found and fixed live"
  # for the full account; summarised here:
  # - A single CREIN Charge, one-day cycle instead of the spec's own calendar month
  #   (DailyAccrualFixture.openContractWithFixedRateChargeAndFastSettlement declares the SETTLEMENT
  #   rhythm's Chosen Periodicity as CALENDAR every 7 DAYS, anchored on the most recent Business Day —
  #   see the fixture's own Javadoc for why 7 DAYS, not 1, and why the Business Day anchor).
  # - A 100%-to-self Allocation Agreement, set through the real PUT
  #   /api/profiles/{profileId}/allocation-agreements feed before the Balance is delivered — 1-CP never
  #   actually returned this in its settlementDue answer until this session's fix (README.md).
  # - A real Client and a real (if minimal) WHT Tax Scheme are now involved, dedicated to this journey's
  #   own CLI-E2E-STL-* Client ids (never the shared ZZ country, never a real one) — 4-Settlement
  #   Computation refuses a settlement item with no resolved Client, and separately refuses one with no
  #   Tax Scheme for the Client's Country AT ALL, even for a Charge that carries no tax code (BR-347,
  #   ERR-343) — seemingly even for an EXEMPT Client, which the fixture's own Javadoc explains is
  #   actually a dead end on this platform today (not fixed, documented).
  # - This scenario therefore asserts interestNet (the CREIN gross amount, unaffected by tax) rather than
  #   a tax-adjusted "Net to Settle": getSettlements has no REST field for the latter at all, and the WHT
  #   this journey's own Tax Scheme registers computed to a wildly wrong amount live (a rate-unit
  #   mismatch between 1-CP's percentage convention and 4-Settlement Computation's own reading of it,
  #   README.md) — a real, separate finding, deliberately NOT fixed here (see README.md for why) and
  #   orthogonal to the wiring this scenario exists to prove.
  #
  # This proves the real wiring TS-10-4.3 calls out: 2-Interest Servicing's own settle-if-due step
  # (SettleIfDueService, right after the balance intake's own TX1 commits) reading 1-CP's real
  # settlement-due decision (BR-340) with its real identity and cycle bounds (US-10-1's "starts with its
  # identity and cycle bounds" — 1-CP's own DoD notes it has no separate e2e scenario of its own, folded
  # into this one instead), calling 4-Settlement Computation's real /api/v1/settle, and persisting the
  # Settlement in TX2 with its proof (US-10-4's "persisted in one step with its proof and published
  # once") — observed here through a real HTTP read (getSettlements) AND a real consumed record on the
  # real interest-settlement-output Kafka topic (evt-settlement-executed.v1), exactly what no single
  # service's own internal ITs can prove.
  @UC-46 @UC-50 @UC-51 @AC-46.1 @AC-50.1 @AC-51.1 @nominal
  Scenario: A due MATURITY Settlement starts with its identity and cycle bounds and is persisted in one step with its proof, published once
    Given a fresh Contract is OPEN with a 1-day SETTLEMENT cycle, priced with a validated Derogation on the Charge "CREIN" at a fixed rate of 2% with day basis "360"
    When C-CLIPS delivers for that Account the real certified Balance of the cycle's Value Date, amount "1080000.00" EUR
    Then within 60 seconds a MATURITY Settlement exists for that Contract with cycle bounds equal to the cycle's Value Date and an interestNet of "60.00"
    And its entries carry a CREDIT entry for Charge "CREIN"
    And the event "evt-settlement-executed.v1" is observed exactly once on the real settlement-output topic
    And the Snapshot of the Charge "CREIN" for the cycle's Value Date is "SETTLED"
