package com.bnpp.itg.tas.ido.cice.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;

/** The status code and parsed JSON body of one real HTTP call — every client method returns this, never a mock. */
public record ApiResponse(int status, JsonNode body, String rawBody) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public String string(String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    public int intValue(String field) {
        return body.get(field).asInt();
    }
}
