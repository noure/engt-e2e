package com.bnpp.itg.tas.ido.cice.e2e.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Builds {@code evt-balance-intake.v1} payloads (topic {@code interest-balance-intake}) on this suite's
 * behalf, standing in for the C-CLIPS Entry manager's end-of-day Balance feed (UC-19) that does not run in
 * this environment. Field-for-field match of {@code BalanceIntakeParser} on the 2-Interest Servicing side
 * (entrypoint/kafka/BalanceIntakeParser.java): {@code eventId}, {@code occurredAt}, {@code accountId},
 * {@code poolId}, {@code bankId}, {@code businessDate}, {@code sourceReference} and a {@code balances} array
 * of {@code valueDate} / {@code balance} / {@code backValue} / {@code currency} / {@code verdict}.
 */
public final class BalanceIntakeEvents {

    private BalanceIntakeEvents() {
    }

    /** One certified Balance for one Account, one Value Date — the nominal case of UC-19 step 1. */
    public static String certifiedNominalBalance(String eventId, String accountId, String bankId, LocalDate valueDate,
                                                  BigDecimal amount, String currency, LocalDate businessDate) {
        JsonObject balance = JsonObject.of()
                .with("valueDate", valueDate.toString())
                .with("balance", amount)
                .with("backValue", false)
                .with("currency", currency)
                .with("verdict", "CERTIFIED");
        JsonObject payload = JsonObject.of()
                .with("eventId", eventId)
                .with("occurredAt", Instant.now().toString())
                .with("accountId", accountId)
                .with("poolId", null)
                .with("bankId", bankId)
                .with("businessDate", businessDate.toString())
                .with("sourceReference", "c-ice-e2e")
                .with("balances", List.of(balance));
        return toJson(payload);
    }

    private static String toJson(JsonObject payload) {
        try {
            return JsonSupport.MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize balance intake event", e);
        }
    }
}
