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
 * Step definitions of sc-04-daily-balance-intake.feature's e2e scenario(s) â€” UC-19 "Receive the end-of-day
 * Balances". See the feature file's own NOTE for the Work Item status observability gap this class works
 * around (getPositions / getSnapshots instead of the unusable {@code GET /api/work/{workId}}).
 */
public class BalanceIntakeSteps {

    private final InterestServicingClient is = new InterestServicingClient();

    private DailyAccrualFixture.OpenedContract contract;
    private String chargeCode;
    private String eventId;
    private LocalDate valueDate;
    private BigDecimal amount;
    private String payload;

    @Given("a Contract is OPEN for a fresh E2E Account, equipped with a validated Derogation pricing the Charge {string} at a fixed rate of {double}% with day basis {string}")
    public void aContractIsOpenWithAValidatedDerogation(String chargeCode, double ratePercent, String dayBasis) {
        this.chargeCode = chargeCode;
        BigDecimal fixedRate = BigDecimal.valueOf(ratePercent).divide(BigDecimal.valueOf(100));
        this.contract = DailyAccrualFixture.openContractWithFixedRateCharge(chargeCode, fixedRate, dayBasis);
        this.valueDate = contract.openingDate();
    }

    @When("C-CLIPS delivers the real certified Balance of today for that Account, amount {string} EUR")
    public void cClipsDeliversTheRealCertifiedBalance(String amountLiteral) {
        deliverBalance(amountLiteral);
    }

    @Given("C-CLIPS delivered the real certified Balance of today for that Account, amount {string} EUR, and its Snapshot of the Charge {string} exists within {int} seconds")
    public void cClipsDeliveredAndItsSnapshotExists(String amountLiteral, String expectedChargeCode, int timeoutSeconds) {
        deliverBalance(amountLiteral);
        awaitSnapshot(expectedChargeCode, timeoutSeconds);
    }

    @When("C-CLIPS redelivers the exact same delivery")
    public void cClipsRedeliversTheExactSameDelivery() {
        try (KafkaProducer<String, String> producer = KafkaSupport.producer()) {
            KafkaSupport.publish(producer, "interest-balance-intake", contract.accountId(), payload);
        }
        // No new observable event marks a no-op: give the single-partition consumer time to process the
        // redelivery before asserting that nothing changed.
        sleep(5000);
    }

    @Then("within {int} seconds the position of the Contract shows today's Value Date with Balance {string} EUR")
    public void withinSecondsThePositionShowsTheBalance(int timeoutSeconds, String expectedAmountLiteral) {
        BigDecimal expected = new BigDecimal(expectedAmountLiteral);
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        JsonNode point = null;
        while (Instant.now().isBefore(deadline)) {
            point = findPosition(valueDate);
            if (point != null) {
                break;
            }
            sleep(1000);
        }
        if (point == null) {
            throw new AssertionError("No position found for Contract " + contract.contractId() + " on Value Date " + valueDate + " within " + timeoutSeconds + "s");
        }
        assertEquals(0, expected.compareTo(point.get("balance").decimalValue()), "balance of the position");
        assertEquals(false, point.get("carried").asBoolean(), "carried flag of the position (a fresh Balance, not carried forward)");
    }

    @Then("within {int} seconds a Snapshot of the Charge {string} for today's Value Date exists for the Contract")
    public void withinSecondsASnapshotExists(int timeoutSeconds, String expectedChargeCode) {
        awaitSnapshot(expectedChargeCode, timeoutSeconds);
    }

    @Then("the position of the Contract still shows exactly one Balance row for today's Value Date")
    public void thePositionStillShowsExactlyOneBalanceRow() {
        ApiResponse positions = is.getPositions(contract.contractId());
        require(positions, 200, "getPositions");
        int count = 0;
        for (JsonNode point : positions.body()) {
            if (valueDate.toString().equals(point.get("valueDate").asText())) {
                count++;
            }
        }
        assertEquals(1, count, "number of position rows for Value Date " + valueDate + " after redelivery");
    }

    @Then("exactly one Snapshot of the Charge {string} exists for today's Value Date at Generation {int}")
    public void exactlyOneSnapshotExistsAtGeneration(String expectedChargeCode, int expectedGeneration) {
        ApiResponse snapshots = is.getSnapshots(contract.contractId());
        require(snapshots, 200, "getSnapshots");
        int count = 0;
        for (JsonNode snapshot : snapshots.body()) {
            if (expectedChargeCode.equals(snapshot.path("chargeCode").asText()) && valueDate.toString().equals(snapshot.path("valueDate").asText())) {
                count++;
                assertEquals(expectedGeneration, snapshot.get("generation").asInt(), "generation of the Snapshot after redelivery");
            }
        }
        assertEquals(1, count, "number of " + expectedChargeCode + " Snapshots for Value Date " + valueDate + " after redelivery (BR-201 idempotence)");
    }

    private void deliverBalance(String amountLiteral) {
        amount = new BigDecimal(amountLiteral);
        eventId = "E2E-EVT-" + UUID.randomUUID();
        payload = BalanceIntakeEvents.certifiedNominalBalance(eventId, contract.accountId(), contract.bankId(), valueDate, amount, "EUR", valueDate);
        try (KafkaProducer<String, String> producer = KafkaSupport.producer()) {
            KafkaSupport.publish(producer, "interest-balance-intake", contract.accountId(), payload);
        }
    }

    private void awaitSnapshot(String expectedChargeCode, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            ApiResponse snapshots = is.getSnapshots(contract.contractId());
            if (snapshots.status() == 200) {
                for (JsonNode snapshot : snapshots.body()) {
                    if (expectedChargeCode.equals(snapshot.path("chargeCode").asText()) && valueDate.toString().equals(snapshot.path("valueDate").asText())) {
                        return;
                    }
                }
            }
            sleep(1000);
        }
        throw new AssertionError("No " + expectedChargeCode + " Snapshot for Contract " + contract.contractId() + " on Value Date " + valueDate + " within " + timeoutSeconds + "s");
    }

    private JsonNode findPosition(LocalDate date) {
        ApiResponse positions = is.getPositions(contract.contractId());
        if (positions.status() != 200) {
            return null;
        }
        for (JsonNode point : positions.body()) {
            if (date.toString().equals(point.get("valueDate").asText())) {
                return point;
            }
        }
        return null;
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
