package com.engineeringplatform.generator.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal canonicalization (EP-WORK-004C+D).
 *
 * Guarantees:
 *  - Map key order does NOT affect the canonical form (keys sorted)
 *  - Lists keep declaration order (semantics preserved)
 * No complex canonicalization framework.
 */
final class Canonicalizer {

    private Canonicalizer() {
    }

    /** Recursively builds a deterministic string from a declarative input tree. */
    static String canonicalString(Object value) {
        StringBuilder sb = new StringBuilder();
        appendCanonical(sb, value);
        return sb.toString();
    }

    private static void appendCanonical(StringBuilder sb, Object value) {
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(e.getKey())).append(':');
                appendCanonical(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendCanonical(sb, item);
            }
            sb.append(']');
        } else if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append(quote(s));
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(quote(String.valueOf(value)));
        }
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
