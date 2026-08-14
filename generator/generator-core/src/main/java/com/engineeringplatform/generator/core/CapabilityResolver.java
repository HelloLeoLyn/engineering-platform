package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Activation;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolvedModule;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 8 — Capability Resolution (EP-WORK-004C+D).
 *
 * Capability ≠ Module. Capability requirements come from resolved module
 * manifests and the project manifest; existence is checked against the
 * capabilities registry (fixture in tests; real registry may be empty).
 *
 * Missing required capability -> CAPABILITY_MISSING.
 * Returns resolved capabilities (also appended to state).
 */
public final class CapabilityResolver {

    public List<ResolvedCapability> resolve(
            ResolverInput input,
            List<ResolvedModule> modules,
            IntermediateResolutionState.Builder state) {
        Map<String, ResolvedCapability> resolved = new LinkedHashMap<>();

        Set<String> registryCapabilities = input.registrySnapshot()
                .getOrDefault("capabilities", Set.of());

        // 1. Required capabilities from resolved modules
        for (ResolvedModule module : modules) {
            Map<String, Object> manifest = input.moduleManifests().get(module.id());
            if (manifest == null) {
                continue;
            }
            List<String> required = extractCapabilityIds(manifest, "requiredCapabilities");
            for (String cap : required) {
                if (!resolved.containsKey(cap)) {
                    if (!registryCapabilities.contains(cap)) {
                        state.error(ResolutionError.capabilityMissing(cap,
                                "module.yaml:/dependencies/requiredCapabilities"));
                    }
                    resolved.put(cap, new ResolvedCapability(cap, Activation.REQUIRED,
                            "Required by module " + module.id(), List.of(module.id()), null));
                }
            }
            List<String> optional = extractCapabilityIds(manifest, "optionalCapabilities");
            for (String cap : optional) {
                if (!resolved.containsKey(cap) && isProjectDeclaredCapability(input, cap)) {
                    resolved.put(cap, new ResolvedCapability(cap, Activation.OPTIONAL_TRIGGERED,
                            "Optional capability of " + module.id() + " triggered by project",
                            List.of(module.id()), null));
                }
            }
        }

        // 2. Capabilities explicitly declared by project
        List<String> projectCaps = ReferenceResolver.extractIds(input.projectManifest().get("capabilities"));
        for (String cap : projectCaps) {
            if (!resolved.containsKey(cap)) {
                resolved.put(cap, new ResolvedCapability(cap, Activation.EXPLICIT,
                        "Declared in project.capabilities", List.of("project"), null));
            }
        }

        List<ResolvedCapability> capabilities = List.copyOf(resolved.values());
        for (ResolvedCapability capability : capabilities) {
            state.capability(capability);
        }
        return capabilities;
    }

    private static boolean isProjectDeclaredCapability(ResolverInput input, String cap) {
        List<String> projectCaps = ReferenceResolver.extractIds(input.projectManifest().get("capabilities"));
        return projectCaps.contains(cap);
    }

    private static List<String> extractCapabilityIds(Map<String, Object> manifest, String key) {
        Object deps = manifest.get("dependencies");
        if (deps instanceof Map<?, ?> depsMap && depsMap.get(key) instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    ids.add(s);
                }
            }
            return ids;
        }
        return List.of();
    }
}
