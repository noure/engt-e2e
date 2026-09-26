package com.bnpp.itg.tas.ido.cice.e2e.support;

import com.bnpp.itg.tas.ido.cice.e2e.clients.ContractPricingManagerClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.producer.KafkaProducer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Shared setup for the daily-accrual chain journeys (sc-04-daily-balance-intake.feature,
 * sc-05-daily-accrual-computation.feature, sc-06-missing-rate-recovery.feature): opens a fresh Contract from a
 * real account event, the same way ContractSetupSteps (Journey 1, sc-02) does, then prices one Charge either
 * with a validated FIXED-rate Derogation (sc-04/sc-05) or a validated FLOATING-rate Derogation on a Shared
 * Index (sc-06) instead of a catalogue Default Condition.
 *
 * <p>Why a Derogation, not a Default Condition: {@code catalogue.day_basis_rule} has NO row and NO feed for
 * scope DEFAULT (Country, Currency) — see README.md "Known gap: the daily accrual Day Basis". A Derogation's
 * own {@code dayBasis} (real REST, {@code POST /api/profiles/{id}/derogations}) writes the DEROGATION scope
 * instead, which {@code getConditionsBundle} checks first (BR-139) — the only real-HTTP path available to a
 * fresh {@code ZZ} test country. It also "wins outright over the catalogue" (per the spec's own running
 * example), so no catalogue Condition is needed at all for these journeys.
 */
public final class DailyAccrualFixture {

    private static final String PROPOSER = "e2e-proposer";
    private static final String VALIDATOR = "e2e-validator";

    private DailyAccrualFixture() {
    }

    /** Everything a balance-intake / accrual scenario needs to know about the Contract it just opened. */
    public record OpenedContract(String testId, String countryCode, String bankId, String productCode,
                                  String accountId, LocalDate openingDate, String contractId, String attachmentId) {
    }

    public static OpenedContract openContractWithFixedRateCharge(String chargeCode, BigDecimal fixedRate, String dayBasis) {
        ContractPricingManagerClient cp = new ContractPricingManagerClient();
        Common common = openCommon(cp, chargeCode, false);
        registerAndValidateFixedDerogation(cp, common.contractId, chargeCode, fixedRate, dayBasis, common.openingDate);
        return common.opened();
    }

    /**
     * sc-06-missing-rate-recovery.feature: a Contract priced on a FLOATING Slab referencing a Shared Index
     * this call registers fresh (BR-113 ERR-130/ERR-142 need it to exist and the Charge to be benchmarkable —
     * see README.md "Known gap: the Shared Index catalogue"), with NO Rate Fixing of its own registered for
     * any Value Date: the caller decides, after this returns, whether and when to call
     * {@link com.bnpp.itg.tas.ido.cice.e2e.clients.ContractPricingManagerClient#registerRateFixing} to
     * simulate the Index feed catching up (the "retry finds it" / Fallback Rate branches), or to leave it
     * missing entirely (the plain Deferral branch).
     *
     * <p>{@code externalIndexCode} is deliberately never one of WireMock's known CARTHAGE codes
     * ({@code EURIBOR-3M}, {@code ESTR}, {@code SOFR}, {@code carthage-rate-fixing.json}) so the live
     * read-back this suite does not control always 404s (the platform's own
     * {@code carthage-rate-fixing-unknown.json} catch-all) — the Missing Rate is deterministic and does not
     * depend on which Value Date happens to be used, unlike reusing a real Index CARTHAGE always answers for.
     */
    public static OpenedContract openContractWithFloatingRateCharge(String chargeCode, String indexCode, String externalIndexCode,
                                                                      BigDecimal spread, String dayBasis) {
        ContractPricingManagerClient cp = new ContractPricingManagerClient();
        ReferenceDataBridge.ensureRateIndex(indexCode, chargeCode + " index (e2e)", "CARTHAGE", externalIndexCode, "OVERNIGHT");
        Common common = openCommon(cp, chargeCode, true);
        registerAndValidateFloatingDerogation(cp, common.contractId, chargeCode, indexCode, spread, dayBasis, common.openingDate);
        return common.opened();
    }

    /** Everything shared between the FIXED and FLOATING setups, up to (not including) the Derogation itself. */
    private record Common(String testId, String countryCode, String bankId, String productCode, String accountId,
                           LocalDate openingDate, String contractId, String attachmentId) {
        OpenedContract opened() {
            return new OpenedContract(testId, countryCode, bankId, productCode, accountId, openingDate, contractId, attachmentId);
        }
    }

    private static Common openCommon(ContractPricingManagerClient cp, String chargeCode, boolean benchmarkable) {
        String testId = String.format("%08X", System.nanoTime() & 0xFFFFFFFFL);
        String countryCode = "ZZ";
        String bankId = "E2E" + testId;
        String productCode = "E2E-ACR-" + testId;

        require(cp.deliverCountries(countryCode, "E2E Test Country"), 200, "deliverCountries");
        require(cp.deliverCurrencies("EUR", 2), 200, "deliverCurrencies");
        ReferenceDataBridge.ensureBank(bankId, countryCode, "E2E Test Bank", "Europe/Paris", "CIB_GB", LocalDate.of(2030, 12, 31));
        // 2-Interest Servicing keeps its OWN Bank/Cut-off replica (interestservicing.bank_reference), separate
        // from contract.bank above — see ReferenceDataBridge's Javadoc. A balance intake for a Bank absent
        // here is refused UNKNOWN_BANK even when 1-CP already knows it.
        ReferenceDataBridge.ensureInterestServicingBankReference(bankId, "Europe/Paris", java.time.LocalTime.of(18, 30), "CIB_GB");
        ReferenceDataBridge.ensureChargeType(chargeCode, chargeCode + " (e2e)", "INTEREST", benchmarkable);

        require(cp.createProduct(productCode, "E2E accrual " + testId, PROPOSER), 201, "createProduct");
        Map<String, Object> productCharge = Map.of(
                "chargeCode", chargeCode,
                "family", "INTEREST",
                "label", chargeCode + " (e2e)",
                "taxCodes", List.of(),
                "benchmarkable", benchmarkable);
        require(cp.declareCharges(productCode, PROPOSER, List.of(productCharge)), 200, "declareCharges");
        List<Map<String, Object>> options = List.of(
                Map.of("rhythmType", "CALCULATION", "anchor", "CALENDAR", "intervalMonthsDays", 1, "monthsOrDays", "DAYS"),
                Map.of("rhythmType", "SETTLEMENT", "anchor", "CALENDAR", "intervalMonthsDays", 1, "monthsOrDays", "MONTHS"));
        require(cp.declareFrequencyOptions(productCode, chargeCode, PROPOSER, options), 200, "declareFrequencyOptions");
        require(cp.proposeProduct(productCode, PROPOSER), 200, "proposeProduct");
        require(cp.validateProduct(productCode, VALIDATOR), 200, "validateProduct");
        ReferenceDataBridge.ensureDefaultProduct(countryCode, "CURRENT", productCode);

        String accountId = "ACC-E2E-" + testId;
        LocalDate openingDate = LocalDate.now();
        String accountEvent = AccountIntakeEvents.createdAccount(accountId, bankId, "CLI-E2E-" + testId, "EUR", "CURRENT", openingDate);
        try (KafkaProducer<String, String> producer = KafkaSupport.producer()) {
            KafkaSupport.publish(producer, "interest-account-intake", accountId, accountEvent);
        }

        String contractId = awaitOpenContract(cp, accountId);
        ApiResponse profile = cp.getProfile(contractId);
        require(profile, 200, "getProfile");
        JsonNode attachments = profile.body().get("productAttachments");
        if (attachments == null || !attachments.isArray() || attachments.isEmpty()) {
            throw new AssertionError("Expected at least one Product Attachment on Contract " + contractId);
        }
        String attachmentId = attachments.get(0).get("attachmentId").asText();

        return new Common(testId, countryCode, bankId, productCode, accountId, openingDate, contractId, attachmentId);
    }

    private static String awaitOpenContract(ContractPricingManagerClient cp, String accountId) {
        Instant deadline = Instant.now().plusSeconds(30);
        ApiResponse reference = null;
        while (Instant.now().isBefore(deadline)) {
            reference = cp.getProfileByAccount(accountId);
            if (reference.status() == 200 && "OPEN".equals(reference.string("status"))) {
                return reference.string("profileId");
            }
            sleep(1000);
        }
        throw new AssertionError("No OPEN Contract for account " + accountId + " within 30s (last status: "
                + (reference == null ? "none" : reference.status()) + ")");
    }

    /** Registers a NEGOTIATED tariff (UC-15) and validates it under four-eyes (UC-18) — it prices nothing until validated. */
    private static void registerAndValidateFixedDerogation(ContractPricingManagerClient cp, String contractId, String chargeCode,
                                                             BigDecimal fixedRate, String dayBasis, LocalDate effectiveDate) {
        JsonObject slab = JsonObject.of()
                .with("slabOrder", 1)
                .with("fromAmount", 0)
                .with("toAmount", null)
                .with("rateKind", "FIXED")
                .with("fixedRate", fixedRate);
        registerAndValidateDerogation(cp, contractId, chargeCode, slab, dayBasis, effectiveDate);
    }

    /** Same as above, on a FLOATING Slab referencing a Shared Index instead of a fixed rate (BR-113, sc-06). */
    private static void registerAndValidateFloatingDerogation(ContractPricingManagerClient cp, String contractId, String chargeCode,
                                                                String indexCode, BigDecimal spread, String dayBasis, LocalDate effectiveDate) {
        JsonObject slab = JsonObject.of()
                .with("slabOrder", 1)
                .with("fromAmount", 0)
                .with("toAmount", null)
                .with("rateKind", "FLOATING")
                .with("indexCode", indexCode)
                .with("spread", spread);
        registerAndValidateDerogation(cp, contractId, chargeCode, slab, dayBasis, effectiveDate);
    }

    private static void registerAndValidateDerogation(ContractPricingManagerClient cp, String contractId, String chargeCode,
                                                        JsonObject slab, String dayBasis, LocalDate effectiveDate) {
        JsonObject derogationRequest = JsonObject.of()
                .with("chargeCode", chargeCode)
                .with("effectiveDate", effectiveDate.toString())
                .with("computationModel", "SIMPLE")
                .with("negativeRateAllowed", false)
                .with("slabs", List.of(slab))
                .with("dayBasis", dayBasis);
        require(cp.registerDerogation(contractId, derogationRequest, PROPOSER), 201, "registerDerogation");

        ApiResponse derogations = cp.listDerogations(contractId);
        require(derogations, 200, "listDerogations");
        String conditionId = null;
        for (JsonNode d : derogations.body()) {
            if (chargeCode.equals(d.path("chargeCode").asText())) {
                conditionId = d.path("conditionId").asText();
            }
        }
        if (conditionId == null) {
            throw new AssertionError("No Derogation found for Charge " + chargeCode + " on Contract " + contractId + " — body: " + derogations.rawBody());
        }
        // A Derogation just registered is a brand new Condition: its first (and only) proposed version is always versionNo 1.
        require(cp.validateConditionVersion(conditionId, 1, VALIDATOR), 200, "validateConditionVersion(derogation)");
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
