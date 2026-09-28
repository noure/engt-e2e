package com.bnpparibas.cib.cice.e2e.support;

import com.bnpparibas.cib.cice.e2e.clients.ContractPricingManagerClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.producer.KafkaProducer;

import java.math.BigDecimal;
import java.time.DayOfWeek;
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
 * scope DEFAULT (Country, Currency) â€” see README.md "Known gap: the daily accrual Day Basis". A Derogation's
 * own {@code dayBasis} (real REST, {@code POST /api/profiles/{id}/derogations}) writes the DEROGATION scope
 * instead, which {@code getConditionsBundle} checks first (BR-139) â€” the only real-HTTP path available to a
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
     * this call registers fresh (BR-113 ERR-130/ERR-142 need it to exist and the Charge to be benchmarkable â€”
     * see README.md "Known gap: the Shared Index catalogue"), with NO Rate Fixing of its own registered for
     * any Value Date: the caller decides, after this returns, whether and when to call
     * {@link com.bnpparibas.cib.cice.e2e.clients.ContractPricingManagerClient#registerRateFixing} to
     * simulate the Index feed catching up (the "retry finds it" / Fallback Rate branches), or to leave it
     * missing entirely (the plain Deferral branch).
     *
     * <p>{@code externalIndexCode} is deliberately never one of WireMock's known CARTHAGE codes
     * ({@code EURIBOR-3M}, {@code ESTR}, {@code SOFR}, {@code carthage-rate-fixing.json}) so the live
     * read-back this suite does not control always 404s (the platform's own
     * {@code carthage-rate-fixing-unknown.json} catch-all) â€” the Missing Rate is deterministic and does not
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

    /**
     * sc-10-settlement-execution.feature: a Contract priced exactly like {@link #openContractWithFixedRateCharge},
     * but with the SETTLEMENT rhythm's offered (and therefore Chosen, BR-122 "the first-declared Offered
     * Periodicity per Charge and Rhythm") Periodicity set to {@code CALENDAR every 7 DAYS} instead of every
     * 1 MONTH, and a 100%-to-self Allocation Agreement already in force ({@code PUT
     * /api/profiles/{profileId}/allocation-agreements}, real REST, BR-324) â€” both needed for 2-Interest
     * Servicing's {@code SettleIfDueService} (the synchronous settle-if-due step right after a balance
     * intake's own TX1 commits) to actually persist a MATURITY Settlement rather than merely defer or park
     * one: an empty {@code allocationRows} answer parks the whole attempt (ERR-324
     * NO_ALLOCATION_AGREEMENT_IN_FORCE, ContractPricingManager's own
     * {@code /api/profiles/{profileId}/allocation-agreements} PUT is a real feed, no reference-data bridge
     * needed, unlike the gaps documented in README.md).
     *
     * <p><b>Why 7 DAYS, not 1 DAY</b> â€” a genuine defect found live while building this fixture (see
     * README.md "Blocking defect found this session: SETTLEMENT schedule regeneration can insert two
     * PENDING entries with the same Due Date"): 1-CP's own 12-month regeneration horizon
     * ({@code SchedulePlanner.regenerate}, always {@code generationDate.plusMonths(12)} regardless of which
     * anchor date this fixture picks) always spans several weekends, and {@code Schedule.dueDate} shifts
     * EVERY SETTLEMENT Theoretical Date that falls on a Saturday or a Sunday forward to the SAME following
     * Monday (BR-130) â€” so a {@code CALENDAR 1 DAYS} SETTLEMENT Periodicity always produces two or three
     * PENDING rows sharing one Monday Due Date sooner or later, which collides with the
     * {@code uq_schedule_due} unique constraint on {@code (attachment_id, charge_code, rhythm_type, due_date)}
     * and dead-letters the whole account-opening event. A weekly ({@code CALENDAR 7 DAYS}) interval anchored
     * on a Business Day recurs on that SAME day-of-week forever (7 is a multiple of the week), so it never
     * lands on a weekend and never collides â€” while still producing an immediately-due cycle on its very
     * first (and only, for this scenario) Theoretical Date.
     *
     * <p>The Contract's opening date (and therefore the Chosen Periodicity's {@code fromDate}, and therefore
     * its one-day cycle's Theoretical/Due Date) is deliberately the most recent BUSINESS day on or before
     * real "today" (Mon-Fri only â€” this suite's fresh E2E bank carries no extra Bank Holiday) instead of
     * {@code LocalDate.now()} verbatim, unlike {@link #openContractWithFixedRateCharge}: 2-Interest
     * Servicing's own {@code SettleIfDueService.settleIfDue} asks 1-CP's {@code settlementDue} with ITS OWN
     * real wall-clock "now" as the Process Date ({@code clock.processDate()}) â€” NOT the Balance's own Value
     * Date â€” so on a weekend the Due Date (always shifted forward to a Business Day, BR-130) would otherwise
     * land on the coming Monday, AFTER a Process Date of Saturday/Sunday, and never fire. Anchoring on the
     * most recent Business Day instead keeps the single-day cycle due immediately regardless of which day of
     * the week this suite happens to run on.
     *
     * <p><b>The Client and its Tax Scheme</b> â€” 4-Settlement Computation refuses a settlement item with no
     * resolved Client at all (400 "clientInfo.clientId must not be null") AND, once resolved, one with an
     * empty Tax Conditions list (ERR-343 TAX_SCHEME_MISSING_IN_ITEM, {@code SettlementItemValidator.
     * checkTaxScheme}) â€” both found live building this fixture. A THIRD wrinkle ruled out an EXEMPT Client as
     * the easy way to keep this journey tax-free: {@code GetConditionsBundleService.getConditionsBundle}
     * skips resolving any Tax Condition at all for an exempt Client by design ({@code
     * !client.taxExempt()}), so an exempt Client's Tax Conditions list is ALWAYS empty â€” which
     * {@code checkTaxScheme} then unconditionally refuses anyway (no exemption carve-out there). An exempt
     * Client can therefore never be settled on this platform today (worth raising as a follow-up); this
     * fixture instead resolves its own Client (only for {@code CLI-E2E-STL-*} ids, see {@code
     * carthage-client-settlement.json}, a higher-priority WireMock mapping than the generic {@code
     * carthage-client.json} every other journey's Client resolves through) to a dedicated country â€” never
     * {@code ZZ} (the shared Bank/Country of every other journey, which must stay tax-free) and never a real
     * country (so this suite never claims {@code catalogue.tax_scheme}'s one-per-country slot a human
     * tester's own running example, e.g. BANK-FR/FR, might still need) â€” NOT exempt, with one real WHT Tax
     * bridged for it ({@code catalogue.tax_scheme} has no creation feed either, see {@code
     * ReferenceDataBridge.ensureTaxScheme}'s own Javadoc), and asserts the real net-of-WHT amount instead of
     * a clean, tax-free one. {@code XE} is one of ISO 3166-1's own reserved-for-private-use codes, exactly
     * the same rationale as {@code ZZ} elsewhere in this suite, just a second, disjoint one.
     */
    private static final String SETTLEMENT_CLIENT_COUNTRY = "XE";
    private static final String SETTLEMENT_TAX_SCHEME = "E2E-TS-XE";

    public static OpenedContract openContractWithFixedRateChargeAndFastSettlement(String chargeCode, BigDecimal fixedRate, String dayBasis) {
        ContractPricingManagerClient cp = new ContractPricingManagerClient();
        LocalDate settlementAnchor = mostRecentBusinessDay(LocalDate.now());
        List<Map<String, Object>> fastSettlementOptions = List.of(
                Map.of("rhythmType", "CALCULATION", "anchor", "CALENDAR", "intervalMonthsDays", 1, "monthsOrDays", "DAYS"),
                Map.of("rhythmType", "SETTLEMENT", "anchor", "CALENDAR", "intervalMonthsDays", 7, "monthsOrDays", "DAYS"));
        // See this method's own Javadoc ("The Client and its Tax Scheme") for why a real, non-exempt WHT is
        // bridged here rather than an exempt Client used as a shortcut.
        ReferenceDataBridge.ensureTaxScheme(SETTLEMENT_TAX_SCHEME, SETTLEMENT_CLIENT_COUNTRY);
        ReferenceDataBridge.ensureTax("E2E-WHT", LocalDate.of(2000, 1, 1), SETTLEMENT_TAX_SCHEME, "e2e WHT (25%, on GROSS_POSITIVE_CREDIT_INTEREST)",
                new BigDecimal("25"), "GROSS_POSITIVE_CREDIT_INTEREST", 1);
        Common common = openCommon(cp, chargeCode, false, settlementAnchor, fastSettlementOptions, "CLI-E2E-STL-");
        registerAndValidateFixedDerogation(cp, common.contractId, chargeCode, fixedRate, dayBasis, common.openingDate);
        Map<String, Object> hundredPercentToSelfRow = new java.util.LinkedHashMap<>();
        hundredPercentToSelfRow.put("chargeCode", "All");
        hundredPercentToSelfRow.put("taxCode", "none");
        hundredPercentToSelfRow.put("direction", "all");
        hundredPercentToSelfRow.put("cycleCurrentFlag", "all");
        hundredPercentToSelfRow.put("postingAccountId", null);
        hundredPercentToSelfRow.put("pctAllocation", 100);
        hundredPercentToSelfRow.put("label", "e2e 100% to self");
        require(cp.setAllocationAgreements(common.contractId, settlementAnchor, List.of(hundredPercentToSelfRow)), 200, "setAllocationAgreements");
        return common.opened();
    }

    private static LocalDate mostRecentBusinessDay(LocalDate date) {
        LocalDate d = date;
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** Everything shared between the FIXED and FLOATING setups, up to (not including) the Derogation itself. */
    private record Common(String testId, String countryCode, String bankId, String productCode, String accountId,
                           LocalDate openingDate, String contractId, String attachmentId) {
        OpenedContract opened() {
            return new OpenedContract(testId, countryCode, bankId, productCode, accountId, openingDate, contractId, attachmentId);
        }
    }

    private static final List<Map<String, Object>> DEFAULT_FREQUENCY_OPTIONS = List.of(
            Map.of("rhythmType", "CALCULATION", "anchor", "CALENDAR", "intervalMonthsDays", 1, "monthsOrDays", "DAYS"),
            Map.of("rhythmType", "SETTLEMENT", "anchor", "CALENDAR", "intervalMonthsDays", 1, "monthsOrDays", "MONTHS"));

    private static Common openCommon(ContractPricingManagerClient cp, String chargeCode, boolean benchmarkable) {
        return openCommon(cp, chargeCode, benchmarkable, LocalDate.now(), DEFAULT_FREQUENCY_OPTIONS, "CLI-E2E-");
    }

    /**
     * @param openingDate the Contract's opening date AND the {@code fromDate} every offered Periodicity's
     *                    default Chosen row inherits (BR-122) â€” {@code LocalDate.now()} for every journey
     *                    except the fast-settlement one, which anchors on the most recent Business Day instead
     *                    (see {@link #openContractWithFixedRateChargeAndFastSettlement}'s own Javadoc).
     * @param frequencyOptions the Product's offered Periodicities per (Charge, Rhythm) â€” the first-declared
     *                         one per Rhythm becomes the Contract's default Chosen Periodicity at opening.
     * @param clientIdPrefix distinguishes which WireMock CARTHAGE client mapping resolves this journey's own
     *                       Client (see {@link #openContractWithFixedRateChargeAndFastSettlement}'s Javadoc) â€”
     *                       {@code CLI-E2E-} for every other journey, {@code CLI-E2E-STL-} for settlement.
     */
    private static Common openCommon(ContractPricingManagerClient cp, String chargeCode, boolean benchmarkable,
                                      LocalDate openingDate, List<Map<String, Object>> frequencyOptions, String clientIdPrefix) {
        String testId = String.format("%08X", System.nanoTime() & 0xFFFFFFFFL);
        String countryCode = "ZZ";
        String bankId = "E2E" + testId;
        String productCode = "E2E-ACR-" + testId;

        require(cp.deliverCountries(countryCode, "E2E Test Country"), 200, "deliverCountries");
        require(cp.deliverCurrencies("EUR", 2), 200, "deliverCurrencies");
        ReferenceDataBridge.ensureBank(bankId, countryCode, "E2E Test Bank", "Europe/Paris", "CIB_GB", LocalDate.of(2030, 12, 31));
        // 2-Interest Servicing keeps its OWN Bank/Cut-off replica (interestservicing.bank_reference), separate
        // from contract.bank above â€” see ReferenceDataBridge's Javadoc. A balance intake for a Bank absent
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
        require(cp.declareFrequencyOptions(productCode, chargeCode, PROPOSER, frequencyOptions), 200, "declareFrequencyOptions");
        require(cp.proposeProduct(productCode, PROPOSER), 200, "proposeProduct");
        require(cp.validateProduct(productCode, VALIDATOR), 200, "validateProduct");
        ReferenceDataBridge.ensureDefaultProduct(countryCode, "CURRENT", productCode);

        String accountId = "ACC-E2E-" + testId;
        String accountEvent = AccountIntakeEvents.createdAccount(accountId, bankId, clientIdPrefix + testId, "EUR", "CURRENT", openingDate);
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

    /** Registers a NEGOTIATED tariff (UC-15) and validates it under four-eyes (UC-18) â€” it prices nothing until validated. */
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
            throw new AssertionError("No Derogation found for Charge " + chargeCode + " on Contract " + contractId + " â€” body: " + derogations.rawBody());
        }
        // A Derogation just registered is a brand new Condition: its first (and only) proposed version is always versionNo 1.
        require(cp.validateConditionVersion(conditionId, 1, VALIDATOR), 200, "validateConditionVersion(derogation)");
    }

    private static void require(ApiResponse response, int expectedStatus, String operation) {
        if (response.status() != expectedStatus) {
            throw new AssertionError(operation + " expected HTTP " + expectedStatus + " but got " + response.status()
                    + " â€” body: " + response.rawBody());
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
