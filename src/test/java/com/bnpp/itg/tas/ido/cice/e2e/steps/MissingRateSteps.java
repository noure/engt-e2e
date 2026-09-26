package com.bnpp.itg.tas.ido.cice.e2e.steps;

import com.bnpp.itg.tas.ido.cice.e2e.clients.ContractPricingManagerClient;
import com.bnpp.itg.tas.ido.cice.e2e.clients.InterestServicingClient;
import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.BalanceIntakeEvents;
import com.bnpp.itg.tas.ido.cice.e2e.support.DailyAccrualFixture;
import com.bnpp.itg.tas.ido.cice.e2e.support.KafkaSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.apache.kafka.clients.producer.KafkaProducer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Step definitions of sc-06-missing-rate-recovery.feature's e2e scenario(s) — UC-28 "Defer a Work Item on a
 * Missing Rate", UC-29 "Retry once", UC-30 "Apply the Fallback Rate". Reuses DailyAccrualFixture /
 * BalanceIntakeEvents from sc-04/sc-05, but on a FLOATING Slab referencing a Shared Index this class
 * registers fresh for every scenario (see DailyAccrualFixture's Javadoc for why it is never one of WireMock's
 * three known CARTHAGE codes) so the Missing Rate is deterministic and does not depend on which real-world
 * Value Date happens to already have a fixing.
 */
public class MissingRateSteps {

    private final InterestServicingClient is = new InterestServicingClient();
    private final ContractPricingManagerClient cp = new ContractPricingManagerClient();

    private DailyAccrualFixture.OpenedContract contract;
    private String indexCode;
    private LocalDate valueDate;

    @Given("a fresh Contract is OPEN and priced with a validated Derogation on the Charge {string} at a FLOATING rate on a fresh Index with day basis {string}")
    public void aFreshContractIsOpenAndPricedOnAFloatingRate(String chargeCode, String dayBasis) {
        String testId = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        this.indexCode = "E2E-IDX-" + testId;
        String externalIndexCode = "E2E-EXT-" + testId;
        this.contract = DailyAccrualFixture.openContractWithFloatingRateCharge(chargeCode, indexCode, externalIndexCode, BigDecimal.ZERO, dayBasis);
        this.valueDate = contract.openingDate();
    }

    @Given("a Rate Fixing of {string} is registered for that Index {int} days before today")
    public void aRateFixingIsRegisteredEarlier(String rateLiteral, int daysBefore) {
        require(cp.registerRateFixing(indexCode, valueDate.minusDays(daysBefore), parseRate(rateLiteral), "CARTHAGE"), 201, "registerRateFixing(earlier)");
    }

    @When("C-CLIPS delivers for that Account the real certified Balance of today, amount {string} EUR, on the Missing Rate Contract")
    public void cClipsDeliversForThatAccountTheRealCertifiedBalanceOnTheMissingRateContract(String amountLiteral) {
        BigDecimal amount = new BigDecimal(amountLiteral);
        String eventId = "E2E-EVT-" + UUID.randomUUID();
        String payload = BalanceIntakeEvents.certifiedNominalBalance(eventId, contract.accountId(), contract.bankId(), valueDate, amount, "EUR", valueDate);
        try (KafkaProducer<String, String> producer = KafkaSupport.producer()) {
            KafkaSupport.publish(producer, "interest-balance-intake", contract.accountId(), payload);
        }
    }

    @When("a Rate Fixing of {string} is registered for that Index for today before the retry fires")
    public void aRateFixingIsRegisteredForToday(String rateLiteral) {
        require(cp.registerRateFixing(indexCode, valueDate, parseRate(rateLiteral), "CARTHAGE"), 201, "registerRateFixing(target)");
    }

    @Then("no Snapshot of the Charge {string} for today's Value Date appears within {int} seconds")
    public void noSnapshotAppearsWithin(String expectedChargeCode, int seconds) {
        Instant deadline = Instant.now().plusSeconds(seconds);
        while (Instant.now().isBefore(deadline)) {
            JsonNode snapshot = findSnapshot(expectedChargeCode);
            if (snapshot != null) {
                throw new AssertionError("Expected no Snapshot yet for " + expectedChargeCode + " on " + valueDate
                        + " (a Missing Rate should defer the Work Item, not compute one) — got " + snapshot);
            }
            sleep(1000);
        }
    }

    @Then("within {int} seconds the Snapshot of the Charge {string} for today's Value Date is provisional")
    public void withinSecondsTheSnapshotIsProvisional(int timeoutSeconds, String expectedChargeCode) {
        JsonNode snapshot = awaitSnapshot(expectedChargeCode, timeoutSeconds);
        if (!snapshot.path("provisional").asBoolean(false)) {
            throw new AssertionError("Expected the Snapshot to be provisional (computed on the Fallback Rate, BR-230) — got " + snapshot);
        }
    }

    @Then("within {int} seconds the Snapshot of the Charge {string} for today's Value Date is not provisional")
    public void withinSecondsTheSnapshotIsNotProvisional(int timeoutSeconds, String expectedChargeCode) {
        JsonNode snapshot = awaitSnapshot(expectedChargeCode, timeoutSeconds);
        if (snapshot.path("provisional").asBoolean(false)) {
            throw new AssertionError("Expected the Snapshot NOT to be provisional (the ordinary retry found the Rate Fixing) — got " + snapshot);
        }
    }

    @Then("within {int} seconds a Provisional Charge for that Contract is OPEN with the Fallback Rate")
    public void withinSecondsAProvisionalChargeIsOpen(int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        JsonNode found = null;
        while (Instant.now().isBefore(deadline) && found == null) {
            found = findProvisionalCharge();
            if (found == null) {
                sleep(1000);
            }
        }
        if (found == null) {
            throw new AssertionError("Expected an OPEN Provisional Charge for Contract " + contract.contractId() + " within " + timeoutSeconds + "s");
        }
        if (found.path("indexCode").asText(null) == null || found.path("fallbackRate").isMissingNode()) {
            throw new AssertionError("Expected the Provisional Charge to carry indexCode and fallbackRate (BR-230 proof) — got " + found);
        }
    }

    @Then("no Provisional Charge exists for that Contract")
    public void noProvisionalChargeExists() {
        JsonNode found = findProvisionalCharge();
        if (found != null) {
            throw new AssertionError("Expected no Provisional Charge for Contract " + contract.contractId() + " — got " + found);
        }
    }

    private JsonNode findProvisionalCharge() {
        ApiResponse charges = is.listProvisionalCharges("OPEN", contract.bankId());
        require(charges, 200, "listProvisionalCharges");
        for (JsonNode charge : charges.body()) {
            if (contract.contractId().equals(charge.path("profileId").asText())) {
                return charge;
            }
        }
        return null;
    }

    private JsonNode awaitSnapshot(String chargeCode, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            JsonNode snapshot = findSnapshot(chargeCode);
            if (snapshot != null) {
                return snapshot;
            }
            sleep(1000);
        }
        throw new AssertionError("No " + chargeCode + " Snapshot for Contract " + contract.contractId() + " on Value Date " + valueDate + " within " + timeoutSeconds + "s");
    }

    private JsonNode findSnapshot(String chargeCode) {
        ApiResponse snapshots = is.getSnapshots(contract.contractId());
        if (snapshots.status() != 200) {
            return null;
        }
        for (JsonNode snapshot : snapshots.body()) {
            if (chargeCode.equals(snapshot.path("chargeCode").asText()) && valueDate.toString().equals(snapshot.path("valueDate").asText())) {
                return snapshot;
            }
        }
        return null;
    }

    private static BigDecimal parseRate(String literal) {
        String trimmed = literal.trim();
        if (trimmed.endsWith("%")) {
            return new BigDecimal(trimmed.substring(0, trimmed.length() - 1)).divide(BigDecimal.valueOf(100));
        }
        return new BigDecimal(trimmed);
    }

    private static void require(ApiResponse response, int expectedStatus, String operation) {
        if (response.status() != expectedStatus) {
            throw new AssertionError(operation + " expected HTTP " + expectedStatus + " but got " + response.status()
                    + " — body: " + response.rawBody());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
