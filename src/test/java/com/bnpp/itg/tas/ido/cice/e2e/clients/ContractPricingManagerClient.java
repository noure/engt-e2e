package com.bnpp.itg.tas.ido.cice.e2e.clients;

import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.Config;
import com.bnpp.itg.tas.ido.cice.e2e.support.HttpSupport;
import com.bnpp.itg.tas.ido.cice.e2e.support.JsonObject;

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

    // ------------------------------------------------------------------ profiles (contract side)

    public ApiResponse getProfileByAccount(String accountId) {
        return http.get("/api/profiles/by-account/" + accountId);
    }

    public ApiResponse getProfile(String profileId) {
        return http.get("/api/profiles/" + profileId);
    }
}
