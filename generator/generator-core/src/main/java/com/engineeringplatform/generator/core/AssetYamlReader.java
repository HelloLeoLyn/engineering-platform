package com.engineeringplatform.generator.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML subset reader for engineering asset manifests (V02-WORK-003).
 *
 * Supports exactly the structure used by asset.yaml contracts:
 *   - indentation-based nested maps (2-space)
 *   - list items ("- item", item may be a nested block)
 *   - inline lists ("[a, b]")
 *   - scalar values (string / number / boolean / null)
 *   - single/double quoted scalars
 *   - comments (full-line or trailing " #")
 *
 * Not a general YAML parser: flow maps, anchors, multi-doc, block scalars are unsupported.
 * Deliberately no external dependency (generator-core is dependency-free).
 */
final class AssetYamlReader {

    private AssetYamlReader() {
    }

    static Object parse(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = stripComment(line);
            if (trimmed.isBlank()) {
                continue;
            }
            lines.add(trimmed.replace("\t", "  "));
        }
        if (lines.isEmpty()) {
            return Map.of();
        }
        int[] pos = {0};
        Object root = parseBlock(lines, pos, 0);
        return root == null ? Map.of() : root;
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                if (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t') {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    /** Parses a block starting at lines[pos[0]] with the given indent. */
    private static Object parseBlock(List<String> lines, int[] pos, int indent) {
        if (pos[0] >= lines.size()) {
            return null;
        }
        String first = lines.get(pos[0]);
        int firstIndent = indentOf(first);
        if (firstIndent < indent) {
            return null;
        }
        if (first.trim().startsWith("- ")) {
            return parseList(lines, pos, indent);
        }
        return parseMap(lines, pos, indent);
    }

    private static Map<String, Object> parseMap(List<String> lines, int[] pos, int indent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (pos[0] < lines.size()) {
            String line = lines.get(pos[0]);
            int lineIndent = indentOf(line);
            if (lineIndent < indent) {
                break;
            }
            if (lineIndent > indent) {
                throw new IllegalArgumentException("unexpected indentation at line: " + line);
            }
            String content = line.trim();
            if (content.startsWith("- ")) {
                break; // list belongs to parent
            }
            int colon = content.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("expected 'key: value' at line: " + line);
            }
            String key = unquote(content.substring(0, colon).trim());
            String rest = content.substring(colon + 1).trim();
            pos[0]++;
            if (rest.isEmpty()) {
                // child block indent = actual indent of the next line (YAML allows any deeper indent)
                int childIndent = pos[0] < lines.size() ? indentOf(lines.get(pos[0])) : lineIndent + 1;
                Object child = parseBlock(lines, pos, childIndent);
                map.put(key, child == null ? Map.of() : child);
            } else if (rest.startsWith("[")) {
                map.put(key, parseInlineList(rest));
            } else if (rest.equals("-")) {
                pos[0]--;
                int childIndent = pos[0] < lines.size() ? indentOf(lines.get(pos[0])) : lineIndent + 1;
                Object child = parseBlock(lines, pos, childIndent);
                map.put(key, child == null ? List.of() : child);
            } else {
                map.put(key, parseScalar(rest));
            }
        }
        return map;
    }

    private static List<Object> parseList(List<String> lines, int[] pos, int indent) {
        List<Object> list = new ArrayList<>();
        while (pos[0] < lines.size()) {
            String line = lines.get(pos[0]);
            int lineIndent = indentOf(line);
            if (lineIndent < indent) {
                break;
            }
            if (lineIndent > indent) {
                throw new IllegalArgumentException("unexpected indentation at line: " + line);
            }
            String content = line.trim();
            if (!content.startsWith("- ")) {
                break;
            }
            String item = content.substring(2).trim();
            pos[0]++;
            if (item.isEmpty()) {
                int childIndent = pos[0] < lines.size() ? indentOf(lines.get(pos[0])) : lineIndent + 1;
                Object child = parseBlock(lines, pos, childIndent);
                list.add(child == null ? Map.of() : child);
            } else if (item.startsWith("[")) {
                list.add(parseInlineList(item));
            } else if (item.contains(":")) {
                // inline map item: "- key: value" starts a map block; deeper lines belong to it
                List<String> synthetic = new ArrayList<>();
                synthetic.add(item);
                while (pos[0] < lines.size()) {
                    String next = lines.get(pos[0]);
                    int ni = indentOf(next);
                    if (ni <= lineIndent) {
                        break;
                    }
                    pos[0]++;
                    int reIndent = Math.max(0, ni - lineIndent - 2);
                    synthetic.add(" ".repeat(reIndent) + next.trim());
                }
                int[] subPos = {0};
                list.add(parseMap(synthetic, subPos, 0));
            } else {
                list.add(parseScalar(item));
            }
        }
        return list;
    }

    private static List<Object> parseInlineList(String text) {
        List<Object> list = new ArrayList<>();
        String inner = text.substring(1, text.lastIndexOf(']')).trim();
        if (inner.isEmpty()) {
            return list;
        }
        for (String part : inner.split(",")) {
            list.add(parseScalar(part.trim()));
        }
        return list;
    }

    private static Object parseScalar(String raw) {
        String value = unquote(raw);
        if (value.equals("true")) {
            return Boolean.TRUE;
        }
        if (value.equals("false")) {
            return Boolean.FALSE;
        }
        if (value.equals("null") || value.equals("~")) {
            return null;
        }
        if (value.matches("-?\\d+")) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                // fall through to long
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // fall through to string
            }
        }
        if (value.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(value);
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }
}
