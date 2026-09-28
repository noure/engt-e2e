package com.bnpparibas.cib.cice.e2e.clients;

import com.bnpparibas.cib.cice.e2e.support.ApiResponse;
import com.bnpparibas.cib.cice.e2e.support.Config;
import com.bnpparibas.cib.cice.e2e.support.HttpSupport;

import java.util.Map;

/**
 * Real HTTP client for 2-Interest Servicing ({@code api/v14.2/2-interest-servicing-api.yaml}).
 * Grown incrementally, journey batch by journey batch â€” see README.md "Next batch" for what is
 * planned but not yet wrapped here (accrual pull, provisional-charge actions, recalculation demand
 * status reads).
 */
public final class InterestServicingClient {

    private final HttpSupport http;

    public InterestServicingClient() {
        this(Config.interestServicingUrl());
    }

    public InterestServicingClient(String baseUrl) {
        this.http = new HttpSupport(baseUrl);
    }

    public ApiResponse health() {
        return http.get("/actuator/health");
    }

    /** {@code GET /api/profiles/{profileId}/snapshots} â€” the Snapshots persisted for a profile, with their proof. */
    public ApiResponse getSnapshots(String profileId) {
        return http.get("/api/profiles/" + profileId + "/snapshots");
    }

    /** {@code GET /api/profiles/{profileId}/positions} â€” Running Totals and Last Processed Value Date. */
    public ApiResponse getPositions(String profileId) {
        return http.get("/api/profiles/" + profileId + "/positions");
    }

    /** {@code GET /api/profiles/{profileId}/settlements} â€” the Settlements executed for a profile. */
    public ApiResponse getSettlements(String profileId) {
        return http.get("/api/profiles/" + profileId + "/settlements");
    }

    /**
     * {@code GET /api/provisional-charges} â€” Charges computed on the Fallback Rate (BR-230), flagged
     * provisional, for Operations review. Used by sc-06-missing-rate-recovery.feature (AC-30.1). BR-452
     * (Permission) reads {@code X-Permissions} the way Apigee would set it (ProvisionalChargeController's own
     * Javadoc) â€” {@code X-User-Id} alone answers 403 ERR-235, confirmed live.
     */
    public ApiResponse listProvisionalCharges(String status, String bankId) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("X-User-Id", "e2e-operations");
        headers.put("X-Permissions", "provisional-charge.read");
        return http.get("/api/provisional-charges?status=" + status + "&bankId=" + bankId, headers);
    }

    // NOTE: no getWorkStatus(workId) wrapper â€” GET /api/work/{workId} is unusable with its own documented
    // work identifier format (eventId + "/" + profileId): a literal "/" does not match the {workId} path
    // template (404, confirmed live) and an encoded "%2F" is rejected by Tomcat before routing (400 "invalid
    // character", confirmed live). See README.md "Known gap: the Work Item status endpoint". This suite
    // observes intake and computation outcomes through getPositions and getSnapshots instead.
}
