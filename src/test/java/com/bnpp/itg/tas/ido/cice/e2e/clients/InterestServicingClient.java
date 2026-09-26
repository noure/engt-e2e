package com.bnpp.itg.tas.ido.cice.e2e.clients;

import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.Config;
import com.bnpp.itg.tas.ido.cice.e2e.support.HttpSupport;

/**
 * Real HTTP client for 2-Interest Servicing ({@code api/v14.2/2-interest-servicing-api.yaml}).
 * Grown incrementally, journey batch by journey batch — see README.md "Next batch" for what is
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

    /** {@code GET /api/profiles/{profileId}/snapshots} — the Snapshots persisted for a profile, with their proof. */
    public ApiResponse getSnapshots(String profileId) {
        return http.get("/api/profiles/" + profileId + "/snapshots");
    }

    /** {@code GET /api/profiles/{profileId}/positions} — Running Totals and Last Processed Value Date. */
    public ApiResponse getPositions(String profileId) {
        return http.get("/api/profiles/" + profileId + "/positions");
    }

    /** {@code GET /api/profiles/{profileId}/settlements} — the Settlements executed for a profile. */
    public ApiResponse getSettlements(String profileId) {
        return http.get("/api/profiles/" + profileId + "/settlements");
    }

    // NOTE: no getWorkStatus(workId) wrapper — GET /api/work/{workId} is unusable with its own documented
    // work identifier format (eventId + "/" + profileId): a literal "/" does not match the {workId} path
    // template (404, confirmed live) and an encoded "%2F" is rejected by Tomcat before routing (400 "invalid
    // character", confirmed live). See README.md "Known gap: the Work Item status endpoint". This suite
    // observes intake and computation outcomes through getPositions and getSnapshots instead.
}
