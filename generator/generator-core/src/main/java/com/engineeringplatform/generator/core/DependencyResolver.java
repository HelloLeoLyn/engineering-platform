package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Activation;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolvedModule;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 7 — Dependency Resolution (EP-WORK-004C+D).
 *
 * Rules (V0.7 §18):
 *  - required dependency: auto-added, activation=required, transitive
 *  - optional dependency: NOT auto-enabled; only when explicitly triggered,
 *    activation=optional-triggered
 *  - dependency cycle: DEPENDENCY_CYCLE (DFS three-colour detection)
 *  - explicit disable vs required dependency: DEPENDENCY_CONFLICT
 *
 * Pure computation; source manifests never modified.
 * Returns resolved modules (also appended to state) for downstream steps.
 */
public final class DependencyResolver {

    private enum Color { WHITE, GRAY, BLACK }

    public List<ResolvedModule> resolve(ResolverInput input, IntermediateResolutionState.Builder state) {
        Map<String, ResolvedModule> resolved = new LinkedHashMap<>();
        List<String> explicitIds = ReferenceResolver.extractIds(input.projectManifest().get("modules"));
        Map<String, Boolean> explicitlyDisabled = explicitlyDisabled(input);

        // 1. Explicit modules declared by the project (not disabled)
        for (String id : explicitIds) {
            if (!Boolean.TRUE.equals(explicitlyDisabled.get(id))) {
                resolved.put(id, new ResolvedModule(id, Activation.EXPLICIT,
                        "Declared in project.modules", List.of("project"),
                        moduleVersion(input, id)));
            }
        }

        // 2. DFS closure over requiredModules (transitive) with cycle detection
        Map<String, Color> color = new HashMap<>();
        for (String id : new ArrayList<>(resolved.keySet())) {
            dfs(id, input, resolved, explicitlyDisabled, color, state);
        }

        // 3. Optional dependencies: only when the target is explicitly declared by project
        //    (V0.7 §18: Optional Dependency 不自动启用，只在 Project Explicit / Profile Default /
        //    Requirement 需要时启用；activation = optional-triggered)
        for (String id : explicitIds) {
            Map<String, Object> manifest = input.moduleManifests().get(id);
            if (manifest == null) {
                continue;
            }
            List<String> optionalModules = extractDependencyIds(manifest, "optionalModules");
            for (String opt : optionalModules) {
                if (explicitIds.contains(opt) && !Boolean.TRUE.equals(explicitlyDisabled.get(opt))) {
                    // project explicitly selected an optional dependency -> triggered
                    ResolvedModule existing = resolved.get(opt);
                    if (existing != null && existing.activation() != Activation.REQUIRED) {
                        resolved.put(opt, new ResolvedModule(opt, Activation.OPTIONAL_TRIGGERED,
                                "Optional dependency of " + id + " explicitly triggered by project",
                                List.of(id), existing.version()));
                    } else if (existing == null) {
                        resolved.put(opt, new ResolvedModule(opt, Activation.OPTIONAL_TRIGGERED,
                                "Optional dependency of " + id + " explicitly triggered by project",
                                List.of(id), moduleVersion(input, opt)));
                    }
                }
            }
        }

        List<ResolvedModule> modules = List.copyOf(resolved.values());
        for (ResolvedModule module : modules) {
            state.module(module);
        }
        return modules;
    }

    private void dfs(String id, ResolverInput input, Map<String, ResolvedModule> resolved,
                     Map<String, Boolean> explicitlyDisabled, Map<String, Color> color,
                     IntermediateResolutionState.Builder state) {
        Color c = color.getOrDefault(id, Color.WHITE);
        if (c == Color.GRAY) {
            state.error(new ResolutionError(
                    "DEPENDENCY_CYCLE",
                    "Dependency cycle detected involving module: " + id,
                    ResolutionError.Severity.ERROR,
                    "module-manifest", "module.yaml:/dependencies/requiredModules",
                    "module", id, Map.of()));
            return;
        }
        if (c == Color.BLACK) {
            return;
        }
        color.put(id, Color.GRAY);

        Map<String, Object> manifest = input.moduleManifests().get(id);
        if (manifest != null) {
            for (String req : extractDependencyIds(manifest, "requiredModules")) {
                if (Boolean.TRUE.equals(explicitlyDisabled.get(req))) {
                    state.error(ResolutionError.dependencyConflict(
                            "Dependency conflict: module '" + req + "' is explicitly disabled "
                                    + "but required by '" + id + "'",
                            "project.yaml:/modules/" + req));
                    continue;
                }
                if (!resolved.containsKey(req)) {
                    resolved.put(req, new ResolvedModule(req, Activation.REQUIRED,
                            "Required by module " + id, List.of(id),
                            moduleVersion(input, req)));
                }
                dfs(req, input, resolved, explicitlyDisabled, color, state);
            }
        }
        color.put(id, Color.BLACK);
    }

    private static List<String> extractDependencyIds(Map<String, Object> manifest, String key) {
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

    private static Map<String, Boolean> explicitlyDisabled(ResolverInput input) {
        Map<String, Boolean> disabled = new HashMap<>();
        Object modules = input.projectManifest().get("modules");
        if (modules instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("id") instanceof String id) {
                    disabled.put(id, Boolean.FALSE.equals(m.get("enabled")));
                }
            }
        }
        return disabled;
    }

    private static String moduleVersion(ResolverInput input, String id) {
        Map<String, Object> manifest = input.moduleManifests().get(id);
        if (manifest != null && manifest.get("module") instanceof Map<?, ?> module
                && module.get("version") instanceof String v) {
            return v;
        }
        return null;
    }
}
