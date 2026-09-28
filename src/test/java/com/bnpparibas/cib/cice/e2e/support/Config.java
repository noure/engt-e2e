package com.bnpparibas.cib.cice.e2e.support;

/**
 * Every base address the suite talks to, each overridable by an environment variable so the same
 * suite can run against a differently-mapped compose file without touching code. Defaults match
 * {@code c-ice-platform/docker-compose.yml}'s published host ports.
 */
public final class Config {

    private Config() {
    }

    public static String contractPricingManagerUrl() {
        return env("E2E_CP_URL", "http://localhost:9101");
    }

    public static String interestServicingUrl() {
        return env("E2E_IS_URL", "http://localhost:9102");
    }

    public static String interestCalculationUrl() {
        return env("E2E_IC_URL", "http://localhost:9103");
    }

    public static String settlementComputationUrl() {
        return env("E2E_SC_URL", "http://localhost:9104");
    }

    public static String restitutionUrl() {
        return env("E2E_RS_URL", "http://localhost:9105");
    }

    public static String wireMockUrl() {
        return env("E2E_WIREMOCK_URL", "http://localhost:8089");
    }

    public static String kafkaBootstrapServers() {
        return env("E2E_KAFKA_BOOTSTRAP", "localhost:9092");
    }

    public static String postgresJdbcUrl() {
        return env("E2E_POSTGRES_URL", "jdbc:postgresql://localhost:5432/cice");
    }

    public static String postgresUser() {
        return env("E2E_POSTGRES_USER", "cice");
    }

    public static String postgresPassword() {
        return env("E2E_POSTGRES_PASSWORD", "cice");
    }

    /** Every distinctive test-data prefix funnels through here so it can be changed in one place. */
    public static String testDataPrefix() {
        return env("E2E_TEST_PREFIX", "E2E");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
