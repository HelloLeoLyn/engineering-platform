package com.engineeringplatform.console;

import java.util.List;
import java.util.Map;

/**
 * Minimal YAML dumper for Project Contract preview/artifacts.
 * Handles the subset needed by Project Contract V2 (maps/lists/scalars).
 */
public final class YamlDumper {

    private YamlDumper() {}

    public static String dump(Map<String, Object> root) {
        StringBuilder sb = new StringBuilder();
        dumpMap(sb, root, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void dumpValue(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map<?, ?> m) {
            dumpMap(sb, (Map<String, Object>) m, indent);
        } else if (v instanceof List<?> l) {
            dumpList(sb, l, indent);
        } else if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append(quote(s));
        } else {
            sb.append(v);
        }
    }

    private static void dumpMap(StringBuilder sb, Map<String, Object> m, int indent) {
        String pad = "  ".repeat(indent);
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            sb.append(pad).append(e.getKey()).append(":");
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                sb.append('\n');
                dumpValue(sb, v, indent + 1);
            } else {
                sb.append(' ');
                dumpValue(sb, v, indent);
                sb.append('\n');
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void dumpList(StringBuilder sb, List<?> l, int indent) {
        String pad = "  ".repeat(indent);
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> mm = (Map<String, Object>) m;
                // inline map list item: "- key: value" then deeper lines belong to it
                // (AssetYamlReader-compatible format)
                if (mm.isEmpty()) {
                    sb.append(pad).append("- {}\n");
                    continue;
                }
                boolean first = true;
                for (Map.Entry<String, Object> e : mm.entrySet()) {
                    Object v = e.getValue();
                    if (first) {
                        sb.append(pad).append("- ").append(e.getKey()).append(":");
                        first = false;
                    } else {
                        sb.append(pad).append("  ").append(e.getKey()).append(":");
                    }
                    if (v instanceof Map<?, ?> || v instanceof List<?>) {
                        sb.append('\n');
                        dumpValue(sb, v, indent + 2);
                    } else {
                        sb.append(' ');
                        dumpValue(sb, v, indent + 2);
                        sb.append('\n');
                    }
                }
            } else if (o instanceof String s) {
                sb.append(pad).append("- ").append(quote(s)).append('\n');
            } else {
                sb.append(pad).append("- ").append(o).append('\n');
            }
        }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        if (s.matches("[A-Za-z0-9._\\-]+")) return s;
        return "'" + s.replace("'", "''") + "'";
    }
}
