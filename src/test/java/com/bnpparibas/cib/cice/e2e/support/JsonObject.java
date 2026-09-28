package com.bnpparibas.cib.cice.e2e.support;

import java.util.LinkedHashMap;
import java.util.Map;

/** A tiny fluent builder for request bodies â€” avoids a DTO class per endpoint for a suite this size. */
public final class JsonObject extends LinkedHashMap<String, Object> {

    public static JsonObject of() {
        return new JsonObject();
    }

    public JsonObject with(String key, Object value) {
        put(key, value);
        return this;
    }

    @SafeVarargs
    public static <T> java.util.List<Map<String, Object>> list(Map<String, Object>... items) {
        return java.util.List.of(items);
    }
}
