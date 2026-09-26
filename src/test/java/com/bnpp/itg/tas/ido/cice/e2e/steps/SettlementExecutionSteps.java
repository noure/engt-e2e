package com.bnpp.itg.tas.ido.cice.e2e.steps;

import com.bnpp.itg.tas.ido.cice.e2e.clients.InterestServicingClient;
import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.BalanceIntakeEvents;
import com.bnpp.itg.tas.ido.cice.e2e.support.DailyAccrualFixture;
import com.bnpp.itg.tas.ido.cice.e2e.support.KafkaSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Step definitions of sc-10-settlement-execution.feature's e2e scenario — UC-46 "Trigger settlement on
 * its due date", UC-50/UC-51 "Persist the Settlement with its proof, published once". Distinct wording
 * from AccrualComputationSteps' / BalanceIntakeSteps' Given/When to avoid Cucumber step-definition
 * ambiguity (the same convention MissingRateSteps already follows), sharing DailyAccrualFixture and
 * BalanceIntakeEvents.
 *
 * <p>This is the first journey of this suite to consume a real outbox topic rather than only produce to
 * one — {@code KafkaSupport.newConsumerAtEnd}/{@code awaitRecord} existed since the scaffold but every
 * prior journey only observed outcomes through REST reads (see README.md "Known gap: the Work Item
 * status endpoint" and sc-04's own NOTE on {@code EVT-BalanceReceived} not being observed directly).
 */
public class SettlementExecutionSteps {

    private static final String SETTLEMENT_OUTPUT_TOPIC = "interest-settlement-output";

    private final InterestServicingClient is = new InterestServicingClient();

    private DailyAccrualFixture.OpenedContract contract;
    private LocalDate cycleDate;
    private KafkaConsumer<String, String> settlementOutputConsumer;
    private JsonNode lastSettlement;

    @Given("a fresh Contract is OPEN with a 1-day SETTLEMENT cycle, priced with a validated Derogation on the Charge {string} at a fixed rate of {double}% with day basis {string}")
    public void aFreshContractIsOpenWithAOneDaySettlementCycle(String chargeCode, double ratePercent, String dayBasis) {
        BigDecimal fixedRate = BigDecimal.valueOf(ratePercent).divide(BigDecimal.valueOf(100));
        this.contract = DailyAccrualFixture.openContractWithFixedRateChargeAndFastSettlement(chargeCode, fixedRate, dayBasis);
        this.cycleDate = contract.openingDate();
        // Assigned BEFORE the balance intake below (KafkaSupport.newConsumerAtEnd's own contract) so only the
        // record this scenario's own settlement produces is visible to the later "exactly once" check.
        this.settlementOutputConsumer = KafkaSupport.newConsumerAtEnd(SETTLEMENT_OUTPUT_TOPIC);
    }

    @When("C-CLIPS delivers for that Account the real certified Balance of the cycle's Value Date, amount {string} EUR")
    public void cClipsDeliversForThatAccountTheRealCertifiedBalanceOfTheCyclesValueDate(String amountLiteral) {
        BigDecimal amount = new BigDecimal(amountLiteral);
        String eventId = "E2E-EVT-" + UUID.randomUUID();
        String payload = BalanceIntakeEvents.certifiedNominalBalance(eventId, contract.accountId(), contract.bankId(), cycleDate, amount, "EUR", cycleDate);
        try (KafkaProducer<String, String> producer = KafkaSupport.producer()) {
            KafkaSupport.publish(producer, "interest-balance-intake", contract.accountId(), payload);
        }
    }

    @Then("within {int} seconds a MATURITY Settlement exists for that Contract with cycle bounds equal to the cycle's Value Date and an interestNet of {string}")
    public void withinSecondsAMaturitySettlementExists(int timeoutSeconds, String expectedInterestNetLiteral) {
        BigDecimal expectedInterestNet = new BigDecimal(expectedInterestNetLiteral);
        JsonNode settlement = awaitSettlement(timeoutSeconds);
        assertEquals("MATURITY", settlement.path("reason").asText(), "reason of the Settlement");
        assertEquals(cycleDate.toString(), settlement.path("cycleStart").asText(), "cycleStart of the Settlement");
        assertEquals(cycleDate.toString(), settlement.path("cycleEnd").asText(), "cycleEnd of the Settlement");
        // NOTE — interestNet (the CREIN gross amount, unaffected by WHT) is what getSettlements actually
        // exposes; the tax-adjusted netToSettle has no REST field at all (Settlement schema, entrypoint
        // openapi.yaml) — see README.md "Found live: a WHT rate-unit mismatch..." for why this scenario does
        // not attempt to assert the tax-adjusted amount at all, only that a Settlement with correct identity,
        // cycle bounds and gross interest exists.
        if (!settlement.hasNonNull("interestNet")) {
            throw new AssertionError("Expected the Settlement to carry interestNet — got " + settlement);
        }
        BigDecimal interestNet = settlement.get("interestNet").decimalValue();
        assertEquals(0, expectedInterestNet.compareTo(interestNet), "interestNet of the Settlement");
        this.lastSettlement = settlement;
    }

    @Then("its entries carry a CREDIT entry for Charge {string}")
    public void itsEntriesCarryACreditEntryForCharge(String chargeCode) {
        JsonNode entries = lastSettlement.path("entries");
        boolean found = false;
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                String entryCharge = entry.path("chargeCode").asText();
                String direction = entry.path("direction").asText();
                if (chargeCode.equals(entryCharge) && "CREDIT".equalsIgnoreCase(direction)) {
                    found = true;
                }
            }
        }
        if (!found) {
            throw new AssertionError("Expected a CREDIT entry for Charge " + chargeCode + " — got " + lastSettlement);
        }
    }

    @Then("the event {string} is observed exactly once on the real settlement-output topic")
    public void theEventIsObservedExactlyOnceOnTheRealSettlementOutputTopic(String eventVersion) {
        ConsumerRecord<String, String> first = KafkaSupport.awaitRecord(settlementOutputConsumer, Duration.ofSeconds(30),
                r -> matches(r, eventVersion));
        // Poll a further short window: a duplicate here would show the same at-least-once/redelivery bug class
        // sc-04's own "redelivered event is a no-op" scenario guards against on the intake side.
        int extraMatches = 0;
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = settlementOutputConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (matches(record, eventVersion)) {
                    extraMatches++;
                }
            }
        }
        if (extraMatches > 0) {
            throw new AssertionError("Expected " + eventVersion + " exactly once for " + contract.contractId()
                    + " but observed it " + (1 + extraMatches) + " times (first at offset " + first.offset() + ")");
        }
    }

    @Then("the Snapshot of the Charge {string} for the cycle's Value Date is {string}")
    public void theSnapshotOfTheChargeForTheCyclesValueDateIs(String chargeCode, String expectedStatus) {
        Instant deadline = Instant.now().plusSeconds(30);
        JsonNode snapshot = null;
        while (Instant.now().isBefore(deadline)) {
            ApiResponse snapshots = is.getSnapshots(contract.contractId());
            if (snapshots.status() == 200) {
                for (JsonNode candidate : snapshots.body()) {
                    if (chargeCode.equals(candidate.path("chargeCode").asText()) && cycleDate.toString().equals(candidate.path("valueDate").asText())) {
                        snapshot = candidate;
                    }
                }
            }
            if (snapshot != null && expectedStatus.equals(snapshot.path("status").asText())) {
                return;
            }
            sleep(1000);
        }
        throw new AssertionError("Expected the " + chargeCode + " Snapshot of " + cycleDate + " to be " + expectedStatus
                + " within 30s — last seen: " + snapshot);
    }

    private boolean matches(ConsumerRecord<String, String> record, String eventVersion) {
        return contract.contractId().equals(record.key()) && record.value() != null && record.value().contains(eventVersion);
    }

    private JsonNode awaitSettlement(int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            ApiResponse settlements = is.getSettlements(contract.contractId());
            if (settlements.status() == 200) {
                for (JsonNode settlement : settlements.body()) {
                    if (cycleDate.toString().equals(settlement.path("cycleStart").asText())) {
                        return settlement;
                    }
                }
            }
            sleep(1000);
        }
        throw new AssertionError("No Settlement for Contract " + contract.contractId() + " with cycleStart " + cycleDate + " within " + timeoutSeconds + "s");
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
