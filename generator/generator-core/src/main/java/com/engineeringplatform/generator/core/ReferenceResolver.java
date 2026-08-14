package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolvedReference;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 2 — Reference Resolution (EP-WORK-004B).
 *
 * Only existence checks and basic binding:
 *   - reference exists in registry snapshot  -> resolved
 *   - reference missing                     -> UNKNOWN_REFERENCE error
 *
 * Deliberately NOT doing: dependency resolution, capability resolution,
 * provider selection (all belong to EP-WORK-004C).
 */
public final class ReferenceResolver {

    private static final Map<String, String> REGISTRY_TYPE_BY_FIELD = Map.of(
            "modules", "modules",
            "capabilities", "capabilities",
            "providers", "providers");

    /**
     * Resolves references declared in the project manifest against the
     * registry snapshot. Appends results/errors to the state.
     */
    public void resolve(ResolverInput input, IntermediateResolutionState.Builder state) {
        List<ResolvedReference> references = new ArrayList<>();
        for (Map.Entry<String, String> field : REGISTRY_TYPE_BY_FIELD.entrySet()) {
            String fieldName = field.getKey();
            String registryType = field.getValue();
            List<String> ids = extractIds(input.projectManifest().get(fieldName));
            Set<String> registryIds = input.registrySnapshot().getOrDefault(registryType, Set.of());
            for (String id : ids) {
                boolean exists = registryIds.contains(id);
                references.add(new ResolvedReference(registryType, id, exists,
                        "project.yaml:/" + fieldName));
                if (!exists) {
                    state.error(ResolutionError.unknownReference(registryType, id, "project.yaml:/" + fieldName));
                }
            }
        }
        for (ResolvedReference ref : references) {
            state.reference(ref);
        }
    }

    /** Accepts List<String>, List<Map{id}> or String; returns normalized ids. */
    static List<String> extractIds(Object value) {
        List<String> ids = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    ids.add(s);
                } else if (item instanceof Map<?, ?> m && m.get("id") instanceof String s) {
                    ids.add(s);
                }
            }
        } else if (value instanceof String s) {
            ids.add(s);
        }
        return ids;
    }
}
