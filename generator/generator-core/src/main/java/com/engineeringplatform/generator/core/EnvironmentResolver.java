package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 13 — Environment Resolution (EP-WORK-004C+D).
 *
 * Resolves declarative environments from the project manifest.
 * Unknown environment -> UNKNOWN_ENVIRONMENT.
 * Never reads machine env vars, probes Docker/OS, or touches the network.
 * Resolver remains pure computation.
 */
public final class EnvironmentResolver {

    private static final Set<String> VALID_ENVIRONMENTS = Set.of("dev", "test", "staging", "prod");

    public void resolve(ResolverInput input, IntermediateResolutionState.Builder state) {
        Object environments = input.projectManifest().get("environments");
        if (!(environments instanceof List<?> list) || list.isEmpty()) {
            // default environment
            state.environment("dev");
            return;
        }
        for (Object item : list) {
            if (item instanceof String name) {
                resolveOne(name, state);
            } else if (item instanceof Map<?, ?> m && m.get("name") instanceof String name) {
                resolveOne(name, state);
            }
        }
    }

    private void resolveOne(String name, IntermediateResolutionState.Builder state) {
        if (!VALID_ENVIRONMENTS.contains(name)) {
            state.error(new ResolutionError(
                    "UNKNOWN_ENVIRONMENT",
                    "Unknown environment: " + name,
                    ResolutionError.Severity.ERROR,
                    "project-manifest", "project.yaml:/environments",
                    "environment", name, Map.of()));
            return;
        }
        state.environment(name);
    }
}
