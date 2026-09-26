package com.bnpp.itg.tas.ido.cice.e2e.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * KNOWN GAP, documented in README.md "Known gap: reference-data seeding". Country and Currency DO have
 * a real feed surface ({@code POST /api/countries}, {@code POST /api/currencies} — see
 * {@link com.bnpp.itg.tas.ido.cice.e2e.clients.ContractPricingManagerClient#deliverCountries} /
 * {@code deliverCurrencies}, used instead of this class) but two tables do not:
 * <ul>
 *   <li>{@code contract.bank} — no path of {@code api/v14.2/1-contract-pricing-manager-api.yaml} creates
 *       a Bank; {@code deliverBankCalendar} only appends a calendar fact to a bank that must already exist
 *       (409 UNKNOWN_BANK otherwise);</li>
 *   <li>{@code catalogue.default_product} — no delivery endpoint exists at all; the outbound port
 *       {@code DefaultProducts} is read-only, written only by CP's own H2 Cucumber test seed harness;</li>
 *   <li>{@code catalogue.charge_type} — "the Charge referential — shared vocabulary of every context"
 *       (the table's own comment in {@code 03-catalogue-tables.yaml}); {@code declareProductCharges}
 *       LINKS an existing charge to a product (409 UNKNOWN_CHARGE otherwise, confirmed against the real
 *       service), it does not create one, and the outbound port {@code ChargeReferential} is read-only too.</li>
 * </ul>
 * Without them NO journey that opens a Contract from an account event — manual or automated — can run at
 * all: at the time this class was written the shared PostgreSQL instance had zero rows in both tables, so
 * this is not a workaround around business logic, only around a missing admin capability (worth raising as
 * a follow-up: an admin/reference-data REST surface for Bank and Default Product, matching the one Country
 * and Currency already have).
 * <p>
 * This bridge inserts the minimum reference rows a journey needs, once, idempotently, using only the
 * distinctive {@code E2E_*} test identifiers so it can never collide with a real Bank a human tester
 * creates through the UI once that admin capability exists. Every other step of every journey goes through
 * real HTTP or real Kafka — this is the one deliberate, narrow exception, and it never writes a row that
 * expresses business logic (a Product, a Condition, a Contract): only the reference lookup a real C-CLIPS
 * feed would have supplied.
 */
public final class ReferenceDataBridge {

    private ReferenceDataBridge() {
    }

    public static void ensureBank(String bankCode, String countryCode, String label, String timeZone, String decisionScope, LocalDate calendarKnownThrough) {
        execute("""
                INSERT INTO contract.bank (bank_code, country_code, label, time_zone, decision_scope, calendar_known_through)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (bank_code) DO NOTHING
                """, bankCode, countryCode, label, timeZone, decisionScope, calendarKnownThrough);
    }

    /** {@code CREIN}, {@code DEBIN}, {@code BONUS}... — the shared Charge vocabulary a Product Charge links to. */
    public static void ensureChargeType(String chargeCode, String label, String family) {
        execute("""
                INSERT INTO catalogue.charge_type (charge_code, label, family)
                VALUES (?, ?, ?)
                ON CONFLICT (charge_code) DO NOTHING
                """, chargeCode, label, family);
    }

    public static void ensureDefaultProduct(String countryCode, String accountType, String productCode) {
        execute("""
                INSERT INTO catalogue.default_product (country_code, account_type, product_code)
                VALUES (?, ?, ?)
                ON CONFLICT (country_code, account_type) DO UPDATE SET product_code = EXCLUDED.product_code
                """, countryCode, accountType, productCode);
    }

    /**
     * ANOTHER instance of the same gap, found while building the daily-accrual journeys (sc-04, sc-05):
     * {@code interestservicing.bank_reference} is 2-Interest Servicing's OWN Bank/Cut-off replica — table
     * comment "fed by the reference feed; seeded until 1-CP serves it" (02-ledger-tables.yaml) — separate
     * from {@code contract.bank} above (1-Contract & Pricing Manager's own copy) and, like it, has NO REST
     * feed and NO seed data: a balance intake for a Bank absent here is refused with UNKNOWN_BANK
     * (confirmed live), even when {@code contract.bank} already knows the Bank. Every Bank this suite opens
     * a Contract at must therefore be seeded on BOTH sides.
     */
    public static void ensureInterestServicingBankReference(String bankCode, String timeZone, LocalTime cutOffTime, String decisionScope) {
        execute("""
                INSERT INTO interestservicing.bank_reference (bank_code, time_zone, cut_off_time, decision_scope, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (bank_code) DO NOTHING
                """, bankCode, timeZone, cutOffTime, decisionScope);
    }

    private static void execute(String sql, Object... params) {
        try (Connection connection = DriverManager.getConnection(Config.postgresJdbcUrl(), Config.postgresUser(), Config.postgresPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object param = params[i];
                if (param instanceof LocalDate date) {
                    statement.setObject(i + 1, date);
                } else {
                    statement.setObject(i + 1, param);
                }
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Reference-data seed failed: " + sql, e);
        }
    }
}
