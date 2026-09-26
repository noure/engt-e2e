@SC-04 @feature:daily-balance-intake
Feature: Daily balance intake (end-to-end)

  This file replays only the scenario(s) that `90-stories/story-map.md` §6.4 assigns "yes" to the e2e
  project for sc-04-daily-balance-intake.feature — see README.md for the full scope boundary. Every
  other scenario of that feature is covered by 2-Interest Servicing's own internal Cucumber ITs
  (TS-04-*), not replayed here.

  Continues directly from the Contract Journey 1 (sc-02) already opens: each scenario opens its own
  fresh Contract (a distinctive "E2E-" Bank, Product and "ACC-E2E-" Account) so it can run repeatedly
  against the same shared platform, then delivers a real Kafka balance-intake event.

  # NOTE — the Work Item created by UC-19 is not observed directly: GET /api/work/{workId} is unusable
  # with its own documented work identifier format (eventId + "/" + profileId, WorkId.value() on the
  # 2-IS side) — a literal "/" does not match the {workId} path template (404, confirmed against the
  # live service) and an encoded "%2F" is rejected by Tomcat before routing (400 "invalid character",
  # confirmed live). Confirmed the route itself works: a slash-free id answers 400 VALIDATION_FAILED
  # from WorkId.parse, so it is reachable, just not callable with a real two-part identifier. See
  # README.md "Known gap: the Work Item status endpoint" for the follow-up. EVT-BalanceReceived is a
  # second, independent gap: 2-Interest Servicing keeps it in the outbox for the audit trail only and
  # never publishes it (OutboxMessage's own Javadoc, InternalEventSender) — not a channel this suite
  # could consume even if it wanted to. This file instead observes UC-19's real effects through
  # GET /api/profiles/{id}/positions (the Balance Ledger projection) and GET /api/profiles/{id}/snapshots
  # (proof the Work Item ran all the way to PERSISTED) — both real, working, off-hot-path REST reads.
  @UC-19 @AC-19.1 @nominal
  Scenario: A certified Nominal Balance is recorded and its Work Item runs to completion
    Given a Contract is OPEN for a fresh E2E Account, equipped with a validated Derogation pricing the Charge "CREIN" at a fixed rate of 2% with day basis "360"
    When C-CLIPS delivers the real certified Balance of today for that Account, amount "1080000.00" EUR
    Then within 30 seconds the position of the Contract shows today's Value Date with Balance "1080000.00" EUR
    And within 30 seconds a Snapshot of the Charge "CREIN" for today's Value Date exists for the Contract

  @UC-19 @AC-19.8 @wiring
  Scenario: A redelivered event whose Work Item is PERSISTED is a no-op (at-least-once redelivery)
    Given a Contract is OPEN for a fresh E2E Account, equipped with a validated Derogation pricing the Charge "CREIN" at a fixed rate of 2% with day basis "360"
    And C-CLIPS delivered the real certified Balance of today for that Account, amount "1080000.00" EUR, and its Snapshot of the Charge "CREIN" exists within 30 seconds
    When C-CLIPS redelivers the exact same delivery
    Then the position of the Contract still shows exactly one Balance row for today's Value Date
    And exactly one Snapshot of the Charge "CREIN" exists for today's Value Date at Generation 1
