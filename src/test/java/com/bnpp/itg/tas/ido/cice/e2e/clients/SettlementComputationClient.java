package com.bnpp.itg.tas.ido.cice.e2e.clients;

import com.bnpp.itg.tas.ido.cice.e2e.support.ApiResponse;
import com.bnpp.itg.tas.ido.cice.e2e.support.Config;
import com.bnpp.itg.tas.ido.cice.e2e.support.HttpSupport;

/**
 * Real HTTP client for 4-Settlement Computation. Like 3-IC, it is a pure function with no broker
 * access (INV-T3), called synchronously by 2-Interest Servicing at the settlement-due step. Kept
 * here for its health check now; the settlement journeys of the next batch (US-10-1, US-10-4) will
 * add the methods this suite actually needs once implemented and verified.
 */
public final class SettlementComputationClient {

    private final HttpSupport http;

    public SettlementComputationClient() {
        this(Config.settlementComputationUrl());
    }

    public SettlementComputationClient(String baseUrl) {
        this.http = new HttpSupport(baseUrl);
    }

    public ApiResponse health() {
        return http.get("/actuator/health");
    }
}
