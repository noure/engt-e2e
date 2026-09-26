package com.bnpp.itg.tas.ido.cice.e2e.clients;

import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.Config;
import com.bnpp.itg.tas.ido.cice.e2e.support.HttpSupport;

/**
 * Real HTTP client for 5-Restitution ({@code api/v14.1/5-restitution-api.yaml}).
 */
public final class RestitutionClient {

    private final HttpSupport http;

    public RestitutionClient() {
        this(Config.restitutionUrl());
    }

    public RestitutionClient(String baseUrl) {
        this.http = new HttpSupport(baseUrl);
    }

    public ApiResponse health() {
        return http.get("/actuator/health");
    }

    /** {@code GET /api/statements/by-profile/{profileId}} — verifies the direct-call Statement attachment of UC-08 step 10. */
    public ApiResponse getStatementByProfile(String profileId) {
        return http.get("/api/statements/by-profile/" + profileId);
    }
}
