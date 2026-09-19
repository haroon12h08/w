package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A stable text form of a JSON value (object keys sorted recursively, no whitespace), so
 * content can be hashed when written and re-verified after a round trip through PostgreSQL
 * JSONB, which does not preserve key order or spacing.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String canonical(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(sort(mapper, node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sort(ObjectMapper mapper, JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String n : names) {
                sorted.set(n, sort(mapper, node.get(n)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(child -> out.add(sort(mapper, child)));
            return out;
        }
        return node;
    }
}
