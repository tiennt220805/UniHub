package com.unihub.shared.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * The request fingerprint of an idempotent write (backend-coding-standards.md §21 rule 4):
 * lowercase hex SHA-256 of the body in canonical JSON — object keys sorted at every depth, no
 * whitespace — so the same request hashes the same however the client ordered or spaced it, and any
 * change of a value changes the hash. Array order is meaningful and kept.
 *
 * <p>Computed from the body only: the {@code Idempotency-Key} is the identity, not part of the
 * payload, and the actor never comes from the request.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    /** Fingerprints a request DTO as Jackson would serialize it. */
    public static String of(ObjectMapper json, Object body) {
        return of(json, json.valueToTree(body));
    }

    public static String of(ObjectMapper json, JsonNode body) {
        var canonical = new StringBuilder();
        append(json, body, canonical);
        return sha256Hex(canonical.toString());
    }

    private static void append(ObjectMapper json, JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            out.append('{');
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(json, name)).append(':');
                append(json, node.get(name), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            Iterator<JsonNode> elements = node.elements();
            boolean first = true;
            while (elements.hasNext()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(json, elements.next(), out);
            }
            out.append(']');
        } else {
            out.append(quote(json, node));
        }
    }

    private static String quote(ObjectMapper json, Object scalarOrName) {
        try {
            return json.writeValueAsString(scalarOrName);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot canonicalize request body", e);
        }
    }

    private static String sha256Hex(String canonical) {
        try {
            var digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
