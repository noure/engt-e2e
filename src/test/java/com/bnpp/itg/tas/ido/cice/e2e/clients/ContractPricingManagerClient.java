package com.bnpp.itg.tas.ido.cice.e2e.clients;

import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.Config;
import com.bnpp.itg.tas.ido.cice.e2e.support.HttpSupport;
import com.bnpp.itg.tas.ido.cice.e2e.support.JsonObject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Real HTTP client for 1-Contract &amp; Pricing Manager ({@code api/v14.2/1-contract-pricing-manager-api.yaml}).
 * Every method issues one real call to the live service — no in-process shortcut.
 */
public final class ContractPricingManagerClient {

    private final HttpSupport http;

    public ContractPricingManagerClient() {
        this(Config.contractPricingManagerUrl());
    }

    public ContractPricingManagerClient(String baseUrl) {
        this.http = new HttpSupport(baseUrl);
    }

    public ApiResponse health() {
        return http.get("/actuator/health");
    }

    // ------------------------------------------------------------------ reference-data feeds (UC-61)

    /** {@code POST /api/countries} — the real C-CLIPS Country feed surface; upserted key by key, never deleted. */
    public ApiResponse deliverCountries(String countryCode, String label) {
        List<Map<String, Object>> rows = List.of(Map.of("countryCode", countryCode, "label", label));
        return http.post("/api/countries", rows, Map.of());
    }

    /** {@code POST /api/currencies} — the real C-CLIPS Currency feed surface (BR-442). */
    public ApiResponse deliverCurrencies(String currencyCode, int minorUnits) {
        List<Map<String, Object>> rows = List.of(Map.of("currencyCode", currencyCode, "minorUnits", minorUnits));
        return http.post("/api/currencies", rows, Map.of());
    }

    // ------------------------------------------------------------------ products

    public ApiResponse createProduct(String productCode, String label, String requester) {
        JsonObject body = JsonObject.of()
                .with("productCode", productCode)
                .with("label", label)
                .with("poolBased", false)
                .with("termFlag", false)
                .with("backAttachFlag", false)
                .with("noCalculation", false);
        return http.post("/api/products", body, HttpSupport.userHeader(requester));
    }

    public ApiResponse declareCharges(String productCode, String requester, List<Map<String, Object>> charges) {
        return http.put("/api/products/" + productCode + "/charges", charges, HttpSupport.userHeader(requester));
    }

    public ApiResponse declareFrequencyOptions(String productCode, String chargeCode, String requester, List<Map<String, Object>> options) {
        return http.put("/api/products/" + productCode + "/charges/" + chargeCode + "/frequency-options", options, HttpSupport.userHeader(requester));
    }

    public ApiResponse proposeProduct(String productCode, String requester) {
        return http.post("/api/products/" + productCode + "/propose", null, HttpSupport.userHeader(requester));
    }

    public ApiResponse validateProduct(String productCode, String requester) {
        return http.post("/api/products/" + productCode + "/validate", null, HttpSupport.userHeader(requester));
    }

    public ApiResponse getProduct(String productCode) {
        return http.get("/api/products/" + productCode);
    }

    // ------------------------------------------------------------------ conditions (tariffs)

    public ApiResponse createCondition(String productCode, String chargeCode, String calculationCurrency, boolean defaultCondition, String requester) {
        JsonObject body = JsonObject.of()
                .with("productCode", productCode)
                .with("chargeCode", chargeCode)
                .with("calculationCurrency", calculationCurrency)
                .with("defaultCondition", defaultCondition);
        return http.post("/api/conditions", body, HttpSupport.userHeader(requester));
    }

    public ApiResponse proposeConditionVersion(String conditionId, String requester, Map<String, Object> version) {
        return http.post("/api/conditions/" + conditionId + "/versions", version, HttpSupport.userHeader(requester));
    }

    public ApiResponse validateConditionVersion(String conditionId, int versionNo, String requester) {
        return http.post("/api/conditions/" + conditionId + "/versions/" + versionNo + "/validate", null, HttpSupport.userHeader(requester));
    }

    // ------------------------------------------------------------------ derogations (negotiated pricing, UC-15)

    /**
     * {@code POST /api/profiles/{profileId}/derogations} — registers a NEGOTIATED tariff (own Condition, own
     * first version, PROPOSED). Prices nothing until validated (UC-18) — see {@link #validateConditionVersion}.
     * Used by the daily-accrual journeys (sc-04, sc-05) as the real-REST way to set a Contract-level Day Basis
     * (BR-139): the fresh {@code ZZ} test country has no row in {@code catalogue.day_basis_rule} (DEFAULT scope,
     * no feed at all — a gap, see README.md), but a Derogation's own {@code dayBasis} writes the DEROGATION
     * scope row, which {@code getConditionsBundle} checks first.
     */
    public ApiResponse registerDerogation(String profileId, Map<String, Object> derogationRequest, String requester) {
        return http.post("/api/profiles/" + profileId + "/derogations", derogationRequest, HttpSupport.userHeader(requester));
    }

    /** {@code GET /api/profiles/{profileId}/derogations} — used to recover the {@code conditionId} a registration answered only as {@code conditionVersionId}. */
    public ApiResponse listDerogations(String profileId) {
        return http.get("/api/profiles/" + profileId + "/derogations");
    }

    // ------------------------------------------------------------------ rates (shared index, UC-60 / BR-230)

    /**
     * {@code POST /api/rates} — registers a Rate Fixing of a Shared Index, FEED provenance (no
     * {@code X-User-Id}, no justification needed). Used by sc-06-missing-rate-recovery.feature to simulate
     * the Index feed catching up on the target Value Date (the "retry finds it" branch, AC-29.1) or on an
     * earlier one only (the Fallback Rate branch, AC-29.2/AC-30.1/AC-30.2, BR-230).
     */
    public ApiResponse registerRateFixing(String indexCode, LocalDate effectiveDate, BigDecimal value, String source) {
        JsonObject body = JsonObject.of()
                .with("indexCode", indexCode)
                .with("effectiveDate", effectiveDate.toString())
                .with("value", value)
                .with("source", source);
        return http.post("/api/rates", body, Map.of());
    }

    // ------------------------------------------------------------------ profiles (contract side)

    public ApiResponse getProfileByAccount(String accountId) {
        return http.get("/api/profiles/by-account/" + accountId);
    }

    public ApiResponse getProfile(String profileId) {
        return http.get("/api/profiles/" + profileId);
    }

    // ------------------------------------------------------------------ allocation & netting (settlement, UC-46..52)

    /**
     * {@code PUT /api/profiles/{profileId}/allocation-agreements?fromDate=...} — replaces the Contract
     * Agreement rows in force for a profile (BR-324). Real REST, no reference-data bridge needed. Read back
     * by {@code settlementDue}'s own {@code allocationRows} (TS-09-3.1/3.2) and required non-empty by
     * 2-Interest Servicing's settle-if-due step before it will persist a Settlement at all (ERR-324/NO_
     * ALLOCATION_AGREEMENT_IN_FORCE otherwise, observed live while building sc-10-settlement-execution.feature).
     * {@code fromDate} is a required query parameter, not a body field — the entrypoint's own
     * {@code openapi.yaml} (14.1.1 addition) is the source of truth here; a mirrored copy consumed by
     * 2-Interest Servicing's own generated client still shows it missing.
     */
    public ApiResponse setAllocationAgreements(String profileId, LocalDate fromDate, List<Map<String, Object>> rows) {
        return http.put("/api/profiles/" + profileId + "/allocation-agreements?fromDate=" + fromDate, rows, HttpSupport.userHeader("e2e-operations"));
    }
}
