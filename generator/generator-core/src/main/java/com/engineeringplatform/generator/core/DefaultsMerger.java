package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.Provenance;
import com.engineeringplatform.generator.contracts.ProvenanceSource;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;

/**
 * Step 4 — Defaults Merge (EP-WORK-004B).
 *
 * Precedence: Platform Default &lt; Profile Default.
 * Profile-expanded values (already in the state from Step 3) win over
 * platform-level defaults. Deterministic, immutable, provenance tracked,
 * sourcePath preserved.
 *
 * Not handling dependency-derived or provider-derived values (004C).
 */
public final class DefaultsMerger {

    /** Platform-level default keys merged when no profile value exists. */
    private static final List<String> PLATFORM_DEFAULT_KEYS = List.of(
            "technology.java", "technology.node", "technology.springBoot",
            "profiles.application", "profiles.infrastructure", "profiles.security", "profiles.quality");

    /**
     * Merges platform defaults under profile defaults. Only writes a value
     * when the key is not already present (profile values win).
     */
    public void merge(ResolverInput input, List<String> activeProfiles, IntermediateResolutionState.Builder state) {
        Map<String, Object> platform = input.platformManifest();
        Object technology = platform.get("technology");
        if (technology instanceof Map<?, ?> tech) {
            for (Map.Entry<?, ?> entry : tech.entrySet()) {
                String key = "technology." + entry.getKey();
                if (!state.containsValue(key) && isSimpleValue(entry.getValue())) {
                    state.value(key, entry.getValue());
                    state.provenance(key, Provenance.of(
                            entry.getValue(), ProvenanceSource.PLATFORM_DEFAULT,
                            "platform.yaml:/technology/" + entry.getKey(), null));
                }
            }
        }
        // profiles.application/infrastructure/security/quality platform defaults
        Object profiles = platform.get("profiles");
        if (profiles instanceof Map<?, ?> profilesMap && profilesMap.get("default") instanceof Map<?, ?> defaultProfile) {
            for (Map.Entry<?, ?> entry : defaultProfile.entrySet()) {
                String key = "profiles." + entry.getKey();
                if (!state.containsValue(key) && isSimpleValue(entry.getValue())) {
                    state.value(key, entry.getValue());
                    state.provenance(key, Provenance.of(
                            entry.getValue(), ProvenanceSource.PLATFORM_DEFAULT,
                            "platform.yaml:/profiles/default/" + entry.getKey(), null));
                }
            }
        }
    }

    private boolean isSimpleValue(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
