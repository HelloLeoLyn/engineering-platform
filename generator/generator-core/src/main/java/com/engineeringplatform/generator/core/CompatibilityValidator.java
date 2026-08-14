package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolutionReport;
import com.engineeringplatform.generator.contracts.ResolvedModule;
import com.engineeringplatform.generator.contracts.ResolvedProvider;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;

/**
 * Step 10 — Compatibility Validation (EP-WORK-004C+D).
 *
 * Only validates what the existing contracts can express: module/provider
 * compatibility.platformVersion against the resolved platform version.
 * No version solver; incompatible -> COMPATIBILITY_FAILURE.
 * Never auto-modifies versions.
 */
public final class CompatibilityValidator {

    public void validate(
            ResolverInput input,
            List<ResolvedModule> modules,
            List<ResolvedProvider> providers,
            IntermediateResolutionState.Builder state) {
        String platformVersion = resolvedPlatformVersion(input);

        // Module compatibility
        for (ResolvedModule module : modules) {
            Map<String, Object> manifest = input.moduleManifests().get(module.id());
            if (manifest == null) {
                continue;
            }
            String required = compatibilityPlatformVersion(manifest);
            if (required != null && !matches(platformVersion, required)) {
                state.compatibilityFinding(new ResolutionReport.CompatibilityFinding(
                        module.id(), "engineering-platform", false,
                        "module requires platform " + required + " but resolved platform is " + platformVersion));
                state.error(new ResolutionError(
                        "COMPATIBILITY_FAILURE",
                        "Module " + module.id() + " requires platform " + required
                                + " but resolved platform is " + platformVersion,
                        ResolutionError.Severity.ERROR,
                        "module-manifest", "module.yaml:/compatibility/platformVersion",
                        "module", module.id(), Map.of()));
            } else {
                state.compatibilityFinding(new ResolutionReport.CompatibilityFinding(
                        module.id(), "engineering-platform", true, null));
            }
        }

        // Provider compatibility
        for (ResolvedProvider provider : providers) {
            Map<String, Object> manifest = input.providerManifests().get(provider.id());
            if (manifest == null) {
                continue;
            }
            String required = compatibilityPlatformVersion(manifest);
            if (required != null && !matches(platformVersion, required)) {
                state.compatibilityFinding(new ResolutionReport.CompatibilityFinding(
                        provider.id(), "engineering-platform", false,
                        "provider requires platform " + required + " but resolved platform is " + platformVersion));
                state.error(new ResolutionError(
                        "COMPATIBILITY_FAILURE",
                        "Provider " + provider.id() + " requires platform " + required
                                + " but resolved platform is " + platformVersion,
                        ResolutionError.Severity.ERROR,
                        "provider-manifest", "provider.yaml:/compatibility/platformVersion",
                        "provider", provider.id(), Map.of()));
            } else {
                state.compatibilityFinding(new ResolutionReport.CompatibilityFinding(
                        provider.id(), "engineering-platform", true, null));
            }
        }
    }

    private static String resolvedPlatformVersion(ResolverInput input) {
        Object platform = input.platformManifest().get("platform");
        if (platform instanceof Map<?, ?> m && m.get("version") instanceof String v) {
            return v;
        }
        return null;
    }

    private static String compatibilityPlatformVersion(Map<String, Object> manifest) {
        Object compatibility = manifest.get("compatibility");
        if (compatibility instanceof Map<?, ?> m && m.get("platformVersion") instanceof String v) {
            return v;
        }
        return null;
    }

    /** Minimal prefix matching: "0.1.x" matches "0.1.0"; exact equality otherwise. */
    private static boolean matches(String actual, String required) {
        if (actual == null) {
            return false;
        }
        if (required.endsWith(".x")) {
            String prefix = required.substring(0, required.length() - 1);
            return actual.startsWith(prefix);
        }
        return actual.equals(required);
    }
}
