package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.Provenance;
import com.engineeringplatform.generator.contracts.ProvenanceSource;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;

/**
 * Step 3 — Profile Expansion (EP-WORK-004B).
 *
 * Expands project profile selection against platform profile presets.
 *   - profile exists  -> resolved default values, provenance source = PROFILE_DEFAULT
 *   - profile missing -> UNKNOWN_PROFILE error
 *
 * Implementation Choice: when multiple profiles are declared, deterministic
 * declaration order is applied (V0.7 does not define same-level conflict
 * resolution; recorded as an implementation choice, covered by tests).
 */
public final class ProfileExpander {

    private static final String PRESETS_KEY = "presets";
    private static final String DEFAULT_KEY = "default";

    /**
     * Returns the list of active profile names (declaration order).
     */
    public List<String> expand(ResolverInput input, IntermediateResolutionState.Builder state) {
        Map<String, Object> profiles = asMap(input.platformManifest().get("profiles"));
        Map<String, Object> presets = asMap(profiles.get(PRESETS_KEY));

        List<String> selected = selectProfiles(input.projectManifest(), profiles, state);
        for (String profile : selected) {
            if (!presets.containsKey(profile)) {
                state.error(ResolutionError.unknownProfile(profile, "platform.yaml:/profiles/presets/" + profile));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> preset = (Map<String, Object>) presets.get(profile);
            for (Map.Entry<String, Object> entry : preset.entrySet()) {
                String key = "profiles." + entry.getKey();
                state.value(key, entry.getValue());
                state.provenance(key, Provenance.of(
                        entry.getValue(), ProvenanceSource.PROFILE_DEFAULT,
                        "platform.yaml:/profiles/presets/" + profile + "/" + entry.getKey(),
                        profile));
            }
            state.profile(profile);
        }
        return List.copyOf(selected);
    }

    /**
     * Selects profile names from the project manifest.
     * Supports "profiles.default" (string) or "profiles" (string) or
     * multiple names under "profiles" map with a "profiles" list.
     * Deterministic declaration order.
     */
    private List<String> selectProfiles(
            Map<String, Object> project, Map<String, Object> platformProfiles, IntermediateResolutionState.Builder state) {
        List<String> result = new java.util.ArrayList<>();
        Object profilesVal = project.get("profiles");
        if (profilesVal instanceof String s) {
            result.add(s);
        } else if (profilesVal instanceof Map<?, ?> m) {
            // profiles.default: "standard" — single explicit selection
            if (m.get(DEFAULT_KEY) instanceof String s) {
                result.add(s);
            }
            // profiles.list: ["lite", "standard"] — declaration order
            if (m.get("list") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        result.add(s);
                    }
                }
            }
        }
        // Fallback to platform default when nothing selected
        if (result.isEmpty() && platformProfiles.get(DEFAULT_KEY) instanceof String s) {
            result.add(s);
            state.warning("no explicit profile in project; using platform default: " + s);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
