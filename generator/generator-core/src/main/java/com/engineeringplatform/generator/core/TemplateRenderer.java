package com.engineeringplatform.generator.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal template renderer V1 (V02-WORK-004 §5).
 *
 * Supports only simple variable substitution:
 *   ${key}           -> bound value
 *   ${key!'default'} -> bound value, or default when key is unbound (freemarker-style)
 *
 * No condition DSL, no loop DSL, no expression engine, no plugin SDK.
 * copy mode is plain verbatim copy (no rendering at all).
 */
final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)(!('[^']*'|\"[^\"]*\"|[^}]*))?}");

    private TemplateRenderer() {
    }

    static String render(String template, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String defaultValue = parseDefault(matcher.group(2));
            String value = variables.get(key);
            if (value == null && defaultValue != null) {
                value = defaultValue;
            }
            if (value == null) {
                throw new IllegalArgumentException("unbound template variable: " + key);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String parseDefault(String rawDefault) {
        if (rawDefault == null || rawDefault.isBlank()) {
            return null;
        }
        String d = rawDefault.substring(1); // strip leading '!'
        d = d.trim();
        if (d.length() >= 2 && ((d.startsWith("'") && d.endsWith("'"))
                || (d.startsWith("\"") && d.endsWith("\"")))) {
            d = d.substring(1, d.length() - 1);
        }
        return d;
    }
}
