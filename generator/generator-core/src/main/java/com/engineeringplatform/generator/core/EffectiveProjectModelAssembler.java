package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolverInput;
import com.engineeringplatform.generator.contracts.SnapshotMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EffectiveProjectModel Assembly (EP-WORK-004C+D).
 * Builds the final EPM strictly following the 004A effective-project.schema.yaml
 * contract — no second structure.
 */
public final class EffectiveProjectModelAssembler {

    public EffectiveProjectModel assemble(
            ResolverInput input,
            IntermediateResolutionState state,
            SnapshotMetadata snapshot) {

        return new EffectiveProjectModel(
                EffectiveProjectModel.SCHEMA_VERSION,
                snapshot,
                identity(input),
                platform(input),
                profiles(state),
                technology(state),
                state.resolvedModules(),
                state.resolvedCapabilities(),
                state.resolvedProviders(),
                quality(state),
                environments(state),
                security(state),
                infrastructure(state),
                delivery(input),
                registries(input),
                state.provenance(),
                state.warnings());
    }

    private static Map<String, Object> identity(ResolverInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object project = input.projectManifest().get("project");
        if (project instanceof Map<?, ?> m) {
            // V02-WORK-004: basePackage drives generated code package (optional field)
            for (String key : new String[]{"id", "name", "version", "description", "basePackage"}) {
                if (m.get(key) != null) {
                    result.put(key, m.get(key));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> platform(ResolverInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object platform = input.platformManifest().get("platform");
        if (platform instanceof Map<?, ?> m) {
            for (String key : new String[]{"id", "version"}) {
                if (m.get(key) != null) {
                    result.put(key, m.get(key));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> profiles(IntermediateResolutionState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String dim : new String[]{"application", "infrastructure", "security", "quality"}) {
            Object value = state.resolvedValues().get("profiles." + dim);
            if (value != null) {
                result.put(dim, value);
            }
        }
        return result;
    }

    private static Map<String, Object> technology(IntermediateResolutionState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : state.resolvedValues().entrySet()) {
            if (e.getKey().startsWith("technology.")) {
                result.put(e.getKey().substring("technology.".length()), e.getValue());
            }
        }
        return result;
    }

    private static Map<String, Object> quality(IntermediateResolutionState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (state.quality() != null) {
            result.put("minimum", state.quality());
        }
        return result;
    }

    private static List<Map<String, Object>> environments(IntermediateResolutionState state) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String env : state.environments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", env);
            result.add(m);
        }
        return result;
    }

    private static Map<String, Object> security(IntermediateResolutionState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (var f : state.securityFindings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("severity", f.severity());
            if (f.detail() != null) {
                m.put("detail", f.detail());
            }
            findings.add(m);
        }
        result.put("findings", findings);
        return result;
    }

    private static Map<String, Object> infrastructure(IntermediateResolutionState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : state.resolvedValues().entrySet()) {
            if (e.getKey().startsWith("infrastructure.")) {
                result.put(e.getKey().substring("infrastructure.".length()), e.getValue());
            }
        }
        return result;
    }

    private static Map<String, Object> delivery(ResolverInput input) {
        Object delivery = input.projectManifest().get("delivery");
        if (delivery instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private static Map<String, Object> registries(ResolverInput input) {
        Object registries = input.platformManifest().get("registries");
        if (registries instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }
}
