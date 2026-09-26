package com.bnpp.itg.tas.ido.cice.e2e.steps;

import com.bnpp.itg.tas.ido.cice.e2e.clients.ContractPricingManagerClient;
import com.bnpp.itg.tas.ido.cice.e2e.support.AccountIntakeEvents;
import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.JsonObject;
import com.bnpp.itg.tas.ido.cice.e2e.support.KafkaSupport;
import com.bnpp.itg.tas.ido.cice.e2e.support.ReferenceDataBridge;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.apache.kafka.clients.producer.KafkaProducer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Step definitions of sc-02-contract-set-up-and-lifecycle.feature's e2e scenario(s) — UC-08 "Open a
 * contract". One fresh instance per scenario (Cucumber's default object factory), so instance fields
 * safely carry state from Given through Then with no cross-scenario leakage.
 */
public class ContractSetupSteps {

    private static final String PROPOSER = "e2e-proposer";
    private static final String VALIDATOR = "e2e-validator";
    private static final String CHARGE_CODE = "CREIN";

    private final ContractPricingManagerClient cp = new ContractPricingManagerClient();

    private String testId;
    private String countryCode;
    private String bankCode;
    private String productCode;
    private String accountId;
    private LocalDate openingDate;
    private String contractId;

    @Given("a validated Default Product for a fresh E2E Bank and Country, offering the Charge {string} with a validated Default Condition")
    public void aValidatedDefaultProductForAFreshBankAndCountry(String chargeCode) {
        // 8 hex chars: compact enough to fit contract.bank.bank_code (varchar(11)) with the "E2E" prefix,
        // and catalogue.product.product_code (varchar(20)) with the "E2E-DDA-" prefix, while staying
        // distinctive enough not to collide across repeated runs of this suite.
        testId = String.format("%08X", System.nanoTime() & 0xFFFFFFFFL);
        countryCode = "ZZ";
        bankCode = "E2E" + testId;
        productCode = "E2E-DDA-" + testId;

        // Real REST: the Country and Currency feeds 1-CP actually exposes (UC-61).
        ApiResponse countries = cp.deliverCountries(countryCode, "E2E Test Country");
        require(countries, 200, "deliverCountries");
        ApiResponse currencies = cp.deliverCurrencies("EUR", 2);
        require(currencies, 200, "deliverCurrencies");

        // Known gap (no REST feed for Bank / Default Product) — see ReferenceDataBridge's javadoc.
        ReferenceDataBridge.ensureBank(bankCode, countryCode, "E2E Test Bank", "Europe/Paris", "CIB_GB", LocalDate.of(2030, 12, 31));

        // Known gap: catalogue.charge_type is shared vocabulary with no REST feed — see ReferenceDataBridge's javadoc.
        ReferenceDataBridge.ensureChargeType(chargeCode, "Credit Interest", "INTEREST");

        // Real REST: product creation, charges, offered periodicities, four-eyes propose/validate.
        require(cp.createProduct(productCode, "E2E nominal DDA " + testId, PROPOSER), 201, "createProduct");

        Map<String, Object> productCharge = Map.of(
                "chargeCode", chargeCode,
                "family", "INTEREST",
                "label", chargeCode + " (e2e)",
                "taxCodes", List.of(),
                "benchmarkable", false);
        require(cp.declareCharges(productCode, PROPOSER, List.of(productCharge)), 200, "declareCharges");

        List<Map<String, Object>> options = List.of(
                Map.of("rhythmType", "CALCULATION", "anchor", "CALENDAR", "intervalMonthsDays", 1, "monthsOrDays", "DAYS"),
                Map.of("rhythmType", "SETTLEMENT", "anchor", "CALENDAR", "intervalMonthsDays", 1, "monthsOrDays", "MONTHS"));
        require(cp.declareFrequencyOptions(productCode, chargeCode, PROPOSER, options), 200, "declareFrequencyOptions");

        require(cp.proposeProduct(productCode, PROPOSER), 200, "proposeProduct");
        require(cp.validateProduct(productCode, VALIDATOR), 200, "validateProduct");

        // Real REST: the Default Condition (catalogue tariff) priced FIXED 1%, four-eyes propose/validate.
        ApiResponse condition = cp.createCondition(productCode, chargeCode, "EUR", true, PROPOSER);
        require(condition, 201, "createCondition");
        String conditionId = condition.string("conditionId");

        JsonObject slab = JsonObject.of()
                .with("slabOrder", 1)
                .with("fromAmount", 0)
                .with("toAmount", null)
                .with("rateKind", "FIXED")
                .with("fixedRate", 0.01);
        JsonObject version = JsonObject.of()
                .with("fromDate", "2020-01-01")
                .with("computationModel", "SIMPLE")
                .with("negativeRateAllowed", false)
                .with("slabs", List.of(slab));
        ApiResponse proposed = cp.proposeConditionVersion(conditionId, PROPOSER, version);
        require(proposed, 201, "proposeConditionVersion");
        int versionNo = proposed.intValue("versionNo");
        require(cp.validateConditionVersion(conditionId, versionNo, VALIDATOR), 200, "validateConditionVersion");

        // Known gap: designates the Default Product of (country, CURRENT) — see ReferenceDataBridge's javadoc.
        ReferenceDataBridge.ensureDefaultProduct(countryCode, "CURRENT", productCode);
    }

    @When("C-CLIPS publishes the real account event CREATED for a fresh Account at that Bank")
    public void cClipsPublishesTheRealAccountEventCreated() {
        accountId = "ACC-E2E-" + testId;
        openingDate = LocalDate.now();
        String payload = AccountIntakeEvents.createdAccount(accountId, bankCode, "CLI-E2E-" + testId, "EUR", "CURRENT", openingDate);

        try (KafkaProducer<String, String> producer = KafkaSupport.producer()) {
            KafkaSupport.publish(producer, "interest-account-intake", accountId, payload);
        }
    }

    @Then("within {int} seconds the Contract for that Account is OPEN at that Bank with the opening date of the event")
    public void withinSecondsTheContractIsOpen(int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        ApiResponse reference = null;
        while (Instant.now().isBefore(deadline)) {
            reference = cp.getProfileByAccount(accountId);
            if (reference.status() == 200) {
                break;
            }
            sleep(1000);
        }
        if (reference == null || reference.status() != 200) {
            throw new AssertionError("No Contract opened for account " + accountId + " within " + timeoutSeconds + "s (last status: "
                    + (reference == null ? "none" : reference.status()) + ")");
        }
        contractId = reference.string("profileId");
        assertEquals(bankCode, reference.string("bankId"), "bankId of the profile reference");
        assertEquals("OPEN", reference.string("status"), "status of the profile reference");

        ApiResponse profile = cp.getProfile(contractId);
        require(profile, 200, "getProfile");
        assertEquals(accountId, profile.string("accountId"), "accountId of the profile");
        assertEquals(bankCode, profile.string("bankId"), "bankId of the profile");
        assertEquals("EUR", profile.string("currency"), "currency of the profile");
        assertEquals(openingDate.toString(), profile.string("openingDate"), "openingDate of the profile");
    }

    @Then("the Contract is equipped with the Default Product from the opening date with no end date")
    public void theContractIsEquippedWithTheDefaultProduct() {
        ApiResponse profile = cp.getProfile(contractId);
        require(profile, 200, "getProfile");
        var attachments = profile.body().get("productAttachments");
        if (attachments == null || !attachments.isArray() || attachments.isEmpty()) {
            throw new AssertionError("Expected at least one Product Attachment on Contract " + contractId);
        }
        var attachment = attachments.get(0);
        assertEquals(productCode, attachment.get("productCode").asText(), "attached productCode");
        assertEquals(openingDate.toString(), attachment.get("fromDate").asText(), "attachment fromDate");
        var toDate = attachment.get("toDate");
        if (toDate != null && !toDate.isNull()) {
            throw new AssertionError("Expected no end date on the opening attachment, got " + toDate.asText());
        }
    }

    private static void require(ApiResponse response, int expectedStatus, String operation) {
        if (response.status() != expectedStatus) {
            throw new AssertionError(operation + " expected HTTP " + expectedStatus + " but got " + response.status()
                    + " — body: " + response.rawBody());
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
