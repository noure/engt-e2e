package com.bnpparibas.cib.cice.e2e.steps;

import com.bnpparibas.cib.cice.e2e.clients.InterestServicingClient;
import com.bnpparibas.cib.cice.e2e.support.ApiResponse;
import com.bnpparibas.cib.cice.e2e.support.BalanceIntakeEvents;
import com.bnpparibas.cib.cice.e2e.support.DailyAccrualFixture;
import com.bnpparibas.cib.cice.e2e.support.KafkaSupport;
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
 * Step definitions of sc-05-daily-accrual-computation.feature's e2e scenario â€” UC-23 "Resolve the
 * applicable terms", UC-25 "Compute the charge", UC-26 "Persist Snapshot with proof". Distinct wording
 * from BalanceIntakeSteps' Given/When to avoid Cucumber step-definition ambiguity, sharing the same
 * DailyAccrualFixture / BalanceIntakeEvents helpers as sc-04 (see the feature file's own NOTE for the
 * WHT/SOLID tax gap this class does not attempt to work around).
 */
public class AccrualComputationSteps {

    private final InterestServicingClient is = new InterestServicingClient();

    private DailyAccrualFixture.OpenedContract contract;
    private LocalDate valueDate;

    @Given("a fresh Contract is OPEN and priced with a validated Derogation on the Charge {string} at a fixed rate of {double}% with day basis {string}")
    public void aFreshContractIsOpenAndPriced(String chargeCode, double ratePercent, String dayBasis) {
        BigDecimal fixedRate = BigDecimal.valueOf(ratePercent).divide(BigDecimal.valueOf(100));
        this.contract = DailyAccrualFixture.openContractWithFixedRateCharge(chargeCode, fixedRate, dayBasis);
        this.valueDate = contract.openingDate();
    }

    @When("C-CLIPS delivers for that Account the real certified Balance of today, amount {string} EUR")
    public void cClipsDeliversForThatAccountTheRealCertifiedBalance(String amountLiteral) {
        BigDecimal amount = new BigDecimal(amountLiteral);
        String eventId = "E2E-EVT-" + UUID.randomUUID();
        String payload = BalanceIntakeEvents.certifiedNominalBalance(eventId, contract.accountId(), contract.bankId(), valueDate, amount, "EUR", valueDate);
        try (KafkaProducer<String, String> producer = KafkaSupport.producer()) {
            KafkaSupport.publish(producer, "interest-balance-intake", contract.accountId(), payload);
        }
    }

    @Then("within {int} seconds the Snapshot of the Charge {string} for today's Value Date has Raw Amount {string} Generation {int} and status {string}")
    public void withinSecondsTheSnapshotHasRawAmountGenerationAndStatus(int timeoutSeconds, String chargeCode, String expectedAmountLiteral, int expectedGeneration, String expectedStatus) {
        BigDecimal expectedAmount = new BigDecimal(expectedAmountLiteral);
        JsonNode snapshot = awaitSnapshot(chargeCode, timeoutSeconds);
        assertEquals(0, expectedAmount.compareTo(snapshot.get("amount").decimalValue()), "Raw Amount of the " + chargeCode + " Snapshot");
        assertEquals(expectedGeneration, snapshot.get("generation").asInt(), "generation of the " + chargeCode + " Snapshot");
        assertEquals(expectedStatus, snapshot.get("status").asText(), "status of the " + chargeCode + " Snapshot");
        this.lastSnapshot = snapshot;
    }

    @Then("the Snapshot carries its proof: day basis {int} and at least one slab detail")
    public void theSnapshotCarriesItsProof(int expectedDayBasis) {
        assertEquals(expectedDayBasis, lastSnapshot.get("appliedDayBasis").asInt(), "appliedDayBasis of the Snapshot");
        JsonNode slabDetails = lastSnapshot.get("slabDetails");
        if (slabDetails == null || !slabDetails.isArray() || slabDetails.isEmpty()) {
            throw new AssertionError("Expected at least one slab detail on the Snapshot's proof â€” got " + lastSnapshot);
        }
        if (lastSnapshot.path("conditionVersionId").asText(null) == null) {
            throw new AssertionError("Expected the Snapshot's proof to name the priced tariff version (conditionVersionId) â€” got " + lastSnapshot);
        }
    }

    @Then("the position of the Contract shows today's Value Date with Balance {string} EUR, not carried")
    public void thePositionShowsTodaysValueDate(String expectedAmountLiteral) {
        BigDecimal expected = new BigDecimal(expectedAmountLiteral);
        ApiResponse positions = is.getPositions(contract.contractId());
        require(positions, 200, "getPositions");
        for (JsonNode point : positions.body()) {
            if (valueDate.toString().equals(point.get("valueDate").asText())) {
                assertEquals(0, expected.compareTo(point.get("balance").decimalValue()), "balance of the position");
                assertEquals(false, point.get("carried").asBoolean(), "carried flag of the position");
                return;
            }
        }
        throw new AssertionError("No position found for Contract " + contract.contractId() + " on Value Date " + valueDate);
    }

    private JsonNode lastSnapshot;

    private JsonNode awaitSnapshot(String chargeCode, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            ApiResponse snapshots = is.getSnapshots(contract.contractId());
            if (snapshots.status() == 200) {
                for (JsonNode snapshot : snapshots.body()) {
                    if (chargeCode.equals(snapshot.path("chargeCode").asText()) && valueDate.toString().equals(snapshot.path("valueDate").asText())) {
                        return snapshot;
                    }
                }
            }
            sleep(1000);
        }
        throw new AssertionError("No " + chargeCode + " Snapshot for Contract " + contract.contractId() + " on Value Date " + valueDate + " within " + timeoutSeconds + "s");
    }

    private static void require(ApiResponse response, int expectedStatus, String operation) {
        if (response.status() != expectedStatus) {
            throw new AssertionError(operation + " expected HTTP " + expectedStatus + " but got " + response.status()
                    + " â€” body: " + response.rawBody());
        }
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Mismatch on " + what + ": expected <" + expected + "> but got <" + actual + ">");
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
