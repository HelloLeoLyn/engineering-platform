package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Activation;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolvedProvider;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 9 — Provider Resolution (EP-WORK-004C+D).
 *
 * Provider ≠ Maven Dependency. Candidates come from provider manifests /
 * provider registry only — never scanned from pom.xml, and third-party
 * tech dependencies (MyBatis-Plus, Jackson, ...) are never auto-promoted.
 *
 * Selection (Implementation Choice, V0.7 does not define ranking):
 *   1. explicit project preference wins
 *   2. otherwise stable declaration order
 * No candidate -> PROVIDER_MISSING.
 * Returns resolved providers (also appended to state).
 */
public final class ProviderResolver {

    public List<ResolvedProvider> resolve(
            ResolverInput input,
            List<ResolvedCapability> capabilities,
            IntermediateResolutionState.Builder state) {
        Map<String, ResolvedProvider> resolved = new LinkedHashMap<>();

        // Explicit project preference: project.providers (List<{id}> or List<String>)
        List<String> explicitPreference = ReferenceResolver.extractIds(input.projectManifest().get("providers"));

        for (ResolvedCapability capability : capabilities) {
            // capability may already bind an explicit provider (project.capabilities[].provider)
            String boundProvider = capabilityBoundProvider(input, capability.id());
            List<String> candidates = new ArrayList<>();
            if (boundProvider != null) {
                candidates.add(boundProvider);
            } else {
                // candidates from provider manifests implementing this capability
                for (String providerId : input.providerManifests().keySet()) {
                    Map<String, Object> manifest = input.providerManifests().get(providerId);
                    if (manifest != null && implementsCapability(manifest, capability.id())) {
                        candidates.add(providerId);
                    }
                }
                // stable declaration order preference: explicit first, then manifest order
                candidates.sort((a, b) -> {
                    boolean pa = explicitPreference.contains(a);
                    boolean pb = explicitPreference.contains(b);
                    if (pa != pb) {
                        return pa ? -1 : 1;
                    }
                    return 0; // stable: LinkedHashMap iteration order preserved by TimSort stability
                });
            }

            String selected = candidates.isEmpty() ? null : candidates.get(0);
            if (selected == null) {
                state.error(ResolutionError.providerMissing(capability.id(),
                        "project.yaml:/capabilities/" + capability.id()));
                continue;
            }
            if (!resolved.containsKey(selected)) {
                String reason = explicitPreference.contains(selected)
                        ? "Explicit project preference for capability " + capability.id()
                        : "Deterministic selection (declaration order) for capability " + capability.id();
                resolved.put(selected, new ResolvedProvider(
                        selected,
                        explicitPreference.contains(selected) ? Activation.EXPLICIT : Activation.REQUIRED,
                        reason,
                        List.of(capability.id()),
                        providerVersion(input, selected),
                        providerImplements(input, selected)));
            }
        }

        List<ResolvedProvider> providers = List.copyOf(resolved.values());
        for (ResolvedProvider provider : providers) {
            state.provider(provider);
        }
        return providers;
    }

    private static String capabilityBoundProvider(ResolverInput input, String capabilityId) {
        Object caps = input.projectManifest().get("capabilities");
        if (caps instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m
                        && capabilityId.equals(m.get("id"))
                        && m.get("provider") instanceof String provider) {
                    return provider;
                }
            }
        }
        return null;
    }

    private static boolean implementsCapability(Map<String, Object> manifest, String capabilityId) {
        if (manifest.get("implements") instanceof List<?> implementsList) {
            return implementsList.contains(capabilityId);
        }
        return false;
    }

    private static String providerVersion(ResolverInput input, String providerId) {
        Map<String, Object> manifest = input.providerManifests().get(providerId);
        if (manifest != null && manifest.get("provider") instanceof Map<?, ?> provider
                && provider.get("version") instanceof String v) {
            return v;
        }
        return null;
    }

    private static List<String> providerImplements(ResolverInput input, String providerId) {
        Map<String, Object> manifest = input.providerManifests().get(providerId);
        if (manifest != null && manifest.get("implements") instanceof List<?> list) {
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
