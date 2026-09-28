package com.bnpparibas.cib.cice.e2e.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Builds {@code evt-account-lifecycle.v1} payloads (topic {@code interest-account-intake},
 * {@code kafka-asyncapi.yaml}) on this suite's behalf, standing in for the C-CLIPS Account
 * inventory producer that does not run in this environment. Field-for-field match of the
 * asyncapi schema's {@code AccountLifecycleEvent}.
 */
public final class AccountIntakeEvents {

    private AccountIntakeEvents() {
    }

    public static String createdAccount(String accountId, String bankId, String clientId, String currency, String accountType, LocalDate openingDate) {
        JsonObject payload = JsonObject.of()
                .with("eventId", UUID.randomUUID().toString())
                .with("occurredAt", Instant.now().toString())
                .with("version", "evt-account-lifecycle.v1")
                .with("type", "CREATED")
                .with("accountId", accountId)
                .with("poolId", null)
                .with("bankId", bankId)
                .with("clientId", clientId)
                .with("currency", currency)
                .with("accountType", accountType)
                .with("date", openingDate.toString())
                .with("initialDerogation", null);
        return toJson(payload);
    }

    private static String toJson(JsonObject payload) {
        try {
            return JsonSupport.MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize account intake event", e);
        }
    }
}
