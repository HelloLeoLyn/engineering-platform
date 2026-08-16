package com.engineeringplatform.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Console-side pre-validation of a Console project contract.
 *
 * Lightweight structural checks BEFORE handing off to the existing pipeline —
 * the existing CompleteResolver remains the authoritative validation; these
 * checks only provide friendly categorized feedback for the wizard.
 */
public final class ConsoleContractValidator {

    private static final Set<String> CERTIFIED_PROFILES = Set.of("enterprise");
    private static final Set<String> CERTIFIED_STACKS = Set.of("enterprise-java25");
    private static final Set<String> CERTIFIED_TEMPLATES = Set.of("enterprise-admin");
    private static final Set<String> KNOWN_MODULES = Set.of(
            "sample-customer", "supplier", "customer-lite", "warehouse-lite");
    private static final Set<String> KNOWN_CAPABILITIES = Set.of(
            "web", "validation", "exception-handling", "platform-core", "authentication",
            "rbac", "organization", "data-permission", "menu", "dictionary", "operation-log",
            "product-reference", "frontend-product-reference",
            "frontend-shell", "frontend-auth", "frontend-permission",
            "frontend-enterprise-management");

    private ConsoleContractValidator() {}

    public static List<Map<String, Object>> validate(Map<String, Object> contract) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (contract == null) {
            errors.add(err("Invalid Project Configuration", "Contract is empty"));
            return errors;
        }

        // project identity
        Object project = contract.get("project");
        if (!(project instanceof Map<?, ?> pm) || pm.get("name") == null || String.valueOf(pm.get("name")).isBlank()) {
            errors.add(err("Invalid Project Configuration", "Project name is required"));
        }
        if (!(project instanceof Map<?, ?> pm2) || pm2.get("id") == null
                || !String.valueOf(pm2.get("id")).matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
            errors.add(err("Invalid Project Configuration", "Project ID must match ^[a-z0-9]+(-[a-z0-9]+)*$"));
        }

        // application profile
        String profile = str(contract, "application", "profile");
        if (profile == null || profile.isBlank()) {
            errors.add(err("Unsupported Application Profile", "Application profile is required"));
        } else if (!CERTIFIED_PROFILES.contains(profile)) {
            errors.add(err("Unsupported Application Profile", "Profile '" + profile + "' is not certified (enterprise)"));
        }

        // stack profile
        String stack = str(contract, "stack", "profile");
        if (stack == null || stack.isBlank()) {
            errors.add(err("Unsupported Stack Profile", "Stack profile is required"));
        } else if (!CERTIFIED_STACKS.contains(stack)) {
            errors.add(err("Unsupported Stack Profile", "Stack '" + stack + "' is not certified (enterprise-java25)"));
        }

        // frontend template
        Object frontends = contract.get("frontends");
        if (frontends instanceof List<?> fl && !fl.isEmpty() && fl.get(0) instanceof Map<?, ?> fm) {
            String template = fm.get("template") == null ? "" : String.valueOf(fm.get("template"));
            if (!CERTIFIED_TEMPLATES.contains(template)) {
                errors.add(err("Unsupported Frontend Template", "Template '" + template + "' is not certified (enterprise-admin)"));
            }
        } else {
            errors.add(err("Unsupported Frontend Template", "At least one frontend with a template is required"));
        }

        // modules
        Object modules = contract.get("modules");
        if (modules instanceof List<?> ml) {
            for (Object o : ml) {
                String id = o instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : String.valueOf(o);
                if (!KNOWN_MODULES.contains(id)) {
                    errors.add(err("Unknown Module", "Module '" + id + "' is not registered"));
                }
            }
        }

        // capabilities
        Object caps = contract.get("capabilities");
        if (caps instanceof List<?> cl) {
            for (Object o : cl) {
                String id = o instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : String.valueOf(o);
                if (!KNOWN_CAPABILITIES.contains(id)) {
                    errors.add(err("Invalid Project Configuration", "Capability '" + id + "' is not registered"));
                }
            }
        }

        return errors;
    }

    private static String str(Map<String, Object> contract, String section, String key) {
        Object s = contract.get(section);
        if (s instanceof Map<?, ?> m) {
            Object v = m.get(key);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    private static Map<String, Object> err(String category, String message) {
        return Map.of("category", category, "message", message);
    }
}
