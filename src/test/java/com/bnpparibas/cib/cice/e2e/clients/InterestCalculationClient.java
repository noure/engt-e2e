package com.bnpparibas.cib.cice.e2e.clients;

import com.bnpparibas.cib.cice.e2e.support.ApiResponse;
import com.bnpparibas.cib.cice.e2e.support.Config;
import com.bnpparibas.cib.cice.e2e.support.HttpSupport;

/**
 * Real HTTP client for 3-Interest Calculation. 3-IC is a pure function with no broker access
 * (INV-T3): in production it is called synchronously by 2-Interest Servicing's WorkItem
 * preparation, never by this suite directly. Kept here for its health check and for a future
 * journey that needs to call {@code /api/compute} directly to isolate a wiring failure from a
 * business-rule failure.
 */
public final class InterestCalculationClient {

    private final HttpSupport http;

    public InterestCalculationClient() {
        this(Config.interestCalculationUrl());
    }

    public InterestCalculationClient(String baseUrl) {
        this.http = new HttpSupport(baseUrl);
    }

    public ApiResponse health() {
        return http.get("/actuator/health");
    }
}
