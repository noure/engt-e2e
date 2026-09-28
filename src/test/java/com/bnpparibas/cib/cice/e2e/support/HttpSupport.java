package com.bnpparibas.cib.cice.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin, dependency-free wrapper around {@link java.net.http.HttpClient} shared by every per-service client.
 * Talks to real HTTP endpoints only â€” there is no in-process fake behind this class.
 */
public final class HttpSupport {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final String baseUrl;

    public HttpSupport(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public ApiResponse get(String path) {
        return send(request(path, "GET", null, Map.of()));
    }

    public ApiResponse get(String path, Map<String, String> headers) {
        return send(request(path, "GET", null, headers));
    }

    public ApiResponse post(String path, Object body, Map<String, String> headers) {
        return send(request(path, "POST", body, headers));
    }

    public ApiResponse put(String path, Object body, Map<String, String> headers) {
        return send(request(path, "PUT", body, headers));
    }

    public ApiResponse delete(String path, Map<String, String> headers) {
        return send(request(path, "DELETE", null, headers));
    }

    public static Map<String, String> userHeader(String userId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-User-Id", userId);
        return headers;
    }

    private HttpRequest request(String path, String method, Object body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15));
        headers.forEach(builder::header);
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            try {
                publisher = HttpRequest.BodyPublishers.ofString(JsonSupport.MAPPER.writeValueAsString(body));
                builder.header("Content-Type", "application/json");
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot serialize request body: " + body, e);
            }
        }
        return builder.method(method, publisher).build();
    }

    private ApiResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode json = MissingNode.getInstance();
            if (raw != null && !raw.isBlank()) {
                // exact decimals (BigDecimal, not double): a Snapshot's Raw Amount is compared at scale 10
                // (README "Nothing is rounded") â€” the same reader configuration the services themselves use
                // to parse the intake payload (BalanceIntakeParser).
                json = JsonSupport.MAPPER.reader()
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .with(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
                        .readTree(raw);
            }
            return new ApiResponse(response.statusCode(), json, raw);
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed: " + request.method() + " " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted: " + request.method() + " " + request.uri(), e);
        }
    }
}
