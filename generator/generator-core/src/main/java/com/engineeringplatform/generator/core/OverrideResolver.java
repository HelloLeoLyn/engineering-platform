package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.Provenance;
import com.engineeringplatform.generator.contracts.ProvenanceSource;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.Map;

/**
 * Step 5 — Project Overrides (EP-WORK-004B).
 *
 * Applies Project Preference over merged defaults.
 * Precedence: Platform Default &lt; Profile Default &lt; Project Preference.
 *
 * Records override provenance: final value + source + sourcePath, so the
 * intermediate state can explain who overrode whom.
 */
public final class OverrideResolver {

    /** Project manifest keys treated as explicit preferences. */
    private static final String[] PROJECT_PREFERENCE_KEYS = {"technology"};

    /**
     * Overrides previously resolved values with explicit project declarations.
     * Deterministic, immutable (writes new state entries), provenance tracked.
     */
    public void apply(ResolverInput input, IntermediateResolutionState.Builder state) {
        Map<String, Object> project = input.projectManifest();

        // technology.* overrides
        if (project.get("technology") instanceof Map<?, ?> tech) {
            for (Map.Entry<?, ?> entry : tech.entrySet()) {
                String key = "technology." + entry.getKey();
                state.value(key, entry.getValue());
                state.provenance(key, Provenance.of(
                        entry.getValue(), ProvenanceSource.PROJECT,
                        "project.yaml:/technology/" + entry.getKey(), null));
            }
        }

        // profiles.* explicit overrides (application/infrastructure/security/quality)
        if (project.get("profiles") instanceof Map<?, ?> profiles) {
            for (String dim : new String[]{"application", "infrastructure", "security", "quality"}) {
                if (profiles.get(dim) != null) {
                    String key = "profiles." + dim;
                    state.value(key, profiles.get(dim));
                    state.provenance(key, Provenance.of(
                            profiles.get(dim), ProvenanceSource.PROJECT,
                            "project.yaml:/profiles/" + dim, null));
                }
            }
        }
    }
}
