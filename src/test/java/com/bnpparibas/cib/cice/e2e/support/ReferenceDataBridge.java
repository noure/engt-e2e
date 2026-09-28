package com.bnpparibas.cib.cice.e2e.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * KNOWN GAP, documented in README.md "Known gap: reference-data seeding". Country and Currency DO have
 * a real feed surface ({@code POST /api/countries}, {@code POST /api/currencies} â€” see
 * {@link com.bnpparibas.cib.cice.e2e.clients.ContractPricingManagerClient#deliverCountries} /
 * {@code deliverCurrencies}, used instead of this class) but two tables do not:
 * <ul>
 *   <li>{@code contract.bank} â€” no path of {@code api/v14.2/1-contract-pricing-manager-api.yaml} creates
 *       a Bank; {@code deliverBankCalendar} only appends a calendar fact to a bank that must already exist
 *       (409 UNKNOWN_BANK otherwise);</li>
 *   <li>{@code catalogue.default_product} â€” no delivery endpoint exists at all; the outbound port
 *       {@code DefaultProducts} is read-only, written only by CP's own H2 Cucumber test seed harness;</li>
 *   <li>{@code catalogue.charge_type} â€” "the Charge referential â€” shared vocabulary of every context"
 *       (the table's own comment in {@code 03-catalogue-tables.yaml}); {@code declareProductCharges}
 *       LINKS an existing charge to a product (409 UNKNOWN_CHARGE otherwise, confirmed against the real
 *       service), it does not create one, and the outbound port {@code ChargeReferential} is read-only too.</li>
 * </ul>
 * Without them NO journey that opens a Contract from an account event â€” manual or automated â€” can run at
 * all: at the time this class was written the shared PostgreSQL instance had zero rows in both tables, so
 * this is not a workaround around business logic, only around a missing admin capability (worth raising as
 * a follow-up: an admin/reference-data REST surface for Bank and Default Product, matching the one Country
 * and Currency already have).
 * <p>
 * This bridge inserts the minimum reference rows a journey needs, once, idempotently, using only the
 * distinctive {@code E2E_*} test identifiers so it can never collide with a real Bank a human tester
 * creates through the UI once that admin capability exists. Every other step of every journey goes through
 * real HTTP or real Kafka â€” this is the one deliberate, narrow exception, and it never writes a row that
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

    /** {@code CREIN}, {@code DEBIN}, {@code BONUS}... â€” the shared Charge vocabulary a Product Charge links to. */
    public static void ensureChargeType(String chargeCode, String label, String family) {
        ensureChargeType(chargeCode, label, family, false);
    }

    /**
     * {@code benchmarkable} is {@code catalogue.charge_type.benchmarkable_flag} itself (BR-103 â€” "the traits a
     * Product Charge carries are those of the Charge"), NOT a per-product override: {@code declareProductCharges}'s
     * own {@code benchmarkable} request field is accepted but the live check (ERR-142, SlabRules.checkRateElements)
     * reads the Charge's own flag. A FLOATING/BENCHMARK Slab (sc-06) therefore needs its OWN Charge code, never
     * {@code CREIN} (already seeded {@code false} by every FIXED-rate journey, and {@code ON CONFLICT DO NOTHING}
     * below never flips an existing row) â€” see sc-06-missing-rate-recovery.feature's use of {@code CREFL}.
     */
    public static void ensureChargeType(String chargeCode, String label, String family, boolean benchmarkable) {
        execute("""
                INSERT INTO catalogue.charge_type (charge_code, label, family, benchmarkable_flag)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (charge_code) DO NOTHING
                """, chargeCode, label, family, benchmarkable);
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
     * {@code interestservicing.bank_reference} is 2-Interest Servicing's OWN Bank/Cut-off replica â€” table
     * comment "fed by the reference feed; seeded until 1-CP serves it" (02-ledger-tables.yaml) â€” separate
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

    /**
     * A FIFTH instance of the same gap, found while building sc-06-missing-rate-recovery.feature:
     * {@code catalogue.rate_index} â€” the Shared Index vocabulary a FLOATING/BENCHMARK Slab's {@code indexCode}
     * must exist in (BR-113, ERR-130 on registerDerogation, ERR-437 on registerRateFixing/getFallbackRate) â€”
     * has NO REST feed anywhere in {@code 1-contract-pricing-manager-api.yaml} and NO seed data on the live
     * platform (confirmed empty by direct query). Every real Index (rate resolution, Rate Fixing registration)
     * a real journey would use is created by an admin/reference-data process this increment does not yet
     * expose â€” worth raising as a follow-up alongside the Bank / Charge Type / Default Product gaps above.
     * <p>
     * {@code external_index_code} is the column WireMock's CARTHAGE stubs and 1-CP's own read-back
     * ({@code CarthageHttpClient.liveFixing}) key on â€” see {@code DailyAccrualFixture}'s Javadoc for why this
     * suite always picks one CARTHAGE's mapping does not recognise.
     */
    public static void ensureRateIndex(String indexCode, String label, String source, String externalIndexCode, String externalIndexType) {
        execute("""
                INSERT INTO catalogue.rate_index (index_code, label, source, external_index_code, external_index_type)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (index_code) DO NOTHING
                """, indexCode, label, source, externalIndexCode, externalIndexType);
    }

    /**
     * A SIXTH instance of the same gap, found while building sc-10-settlement-execution.feature:
     * {@code catalogue.tax_scheme} (scheme code, country code, {@code uq_scheme_country} â€” one scheme per
     * country) has no creation endpoint at all â€” {@code POST /api/taxes} ({@code upsertTaxUseCase.upsert})
     * is a genuine feed for a TAX ROW of an EXISTING scheme, but refuses {@code ERR-449 UNKNOWN_TAX_SCHEME}
     * outright when {@code registry.schemeExists(schemeCode)} answers false (confirmed by reading
     * {@code UpsertTaxService.upsert}, first line of the method), and no other path creates that row. Given
     * {@code uq_scheme_country} allows only ONE scheme per country ever, this suite uses a dedicated,
     * never-reused country code exclusively for the settlement journey's own Client â€” never the shared
     * {@code ZZ} every other journey's Bank/Country uses (that would retroactively add a Tax Scheme to
     * sc-04/05/06's own no-tax scope) and never a real country the running example or a human tester might
     * still register the REAL Tax Scheme for (BANK-FR's own TS-FR, the spec's running example). See
     * {@code DailyAccrualFixture.openContractWithFixedRateChargeAndFastSettlement}'s Javadoc.
     */
    public static void ensureTaxScheme(String schemeCode, String countryCode) {
        execute("""
                INSERT INTO catalogue.tax_scheme (scheme_code, country_code)
                VALUES (?, ?)
                ON CONFLICT (scheme_code) DO NOTHING
                """, schemeCode, countryCode);
    }

    /**
     * The one Tax row of {@link #ensureTaxScheme}'s scheme â€” bridged rather than sent through the real
     * {@code POST /api/taxes} feed because that feed's own {@code catalogue.tax} table keys on
     * {@code (tax_code, from_date)} with a plain INSERT, no upsert semantics (confirmed reading
     * {@code TaxRegistry.append}/{@code UpsertTaxService.upsert}): a rerun on the same calendar day with the
     * same effective date would collide on that primary key with no clean 409 to tolerate, unlike this
     * suite's other {@code ON CONFLICT DO NOTHING} bridges. A fixed, far-past {@code fromDate} keeps this
     * idempotent across reruns.
     */
    public static void ensureTax(String taxCode, LocalDate fromDate, String schemeCode, String label, java.math.BigDecimal rate, String computationBasis, int applyOrder) {
        execute("""
                INSERT INTO catalogue.tax (tax_code, from_date, scheme_code, label, rate, computation_basis, apply_order)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tax_code, from_date) DO NOTHING
                """, taxCode, fromDate, schemeCode, label, rate, computationBasis, applyOrder);
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
