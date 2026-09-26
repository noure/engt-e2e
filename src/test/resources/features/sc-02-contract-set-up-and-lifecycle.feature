@SC-02 @feature:contract-set-up-and-lifecycle
Feature: Contract set-up and lifecycle (end-to-end)

  This file replays only the scenario(s) that `90-stories/story-map.md` §6.2 assigns "yes" to the
  e2e project for sc-02-contract-set-up-and-lifecycle.feature — see README.md for the full scope
  boundary. Every other scenario of that feature (52 in total) is covered by 1-Contract & Pricing
  Manager's own internal Cucumber ITs (TS-02-*), not replayed here.

  Each scenario creates its own distinctive test data (an "E2E-" / "ACC-E2E-" prefixed Bank,
  Product and Account) so it can run repeatedly against the same shared platform without colliding
  with another run or with a human tester's manual data.

  # NOTE — AC-08.2 (the opening Chosen Periodicities) is not asserted here: at the time this suite
  # was written, api/v14.2/1-contract-pricing-manager-api.yaml exposes no GET that reads a Contract's
  # Chosen Periodicities back (setFrequencySettings is PUT-only; getConditionsBundle answers a priced
  # bundle for given value dates, not the raw choice). Flagged as a follow-up: a
  # GET /api/profiles/{profileId}/frequency-settings would let this journey also replay AC-08.2.
  @UC-08 @AC-08.1 @TC-137 @nominal
  Scenario: A CREATED account event opens the Contract with the Default Product and the opening Chosen Periodicities
    Given a validated Default Product for a fresh E2E Bank and Country, offering the Charge "CREIN" with a validated Default Condition
    When C-CLIPS publishes the real account event CREATED for a fresh Account at that Bank
    Then within 30 seconds the Contract for that Account is OPEN at that Bank with the opening date of the event
    And the Contract is equipped with the Default Product from the opening date with no end date
