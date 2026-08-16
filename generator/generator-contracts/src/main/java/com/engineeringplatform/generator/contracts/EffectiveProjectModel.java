package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * Final EffectiveProjectModel (004A effective-project.schema.yaml contract).
 * Unique, immutable, executable, traceable project fact model produced by the Resolver.
 * Not a second structure — mirrors the 004A contract sections.
 *
 * @param schemaVersion   contract schema version (const 1)
 * @param resolution      snapshot metadata (resolutionId/resolverVersion/inputHash)
 * @param identity        project identity
 * @param platform        resolved platform (id/version)
 * @param profiles        resolved profile dimensions
 * @param technology      resolved technology choices
 * @param modules         resolved modules with activation
 * @param capabilities    resolved capabilities with activation
 * @param providers       resolved providers with activation
 * @param quality         resolved quality level (Q1-Q3) + gates
 * @param environments    resolved environments
 * @param security        resolved security facts (findings only, no gatePassed)
 * @param infrastructure  resolved infrastructure (optional)
 * @param delivery        resolved delivery (optional)
 * @param registries      resolved registry refs
 * @param provenance      key effective value provenance map
 * @param warnings        aggregated warnings
 */
public record EffectiveProjectModel(
        int schemaVersion,
        SnapshotMetadata resolution,
        Map<String, Object> identity,
        Map<String, Object> platform,
        Map<String, Object> profiles,
        Map<String, Object> technology,
        List<ResolvedModule> modules,
        List<ResolvedCapability> capabilities,
        List<ResolvedProvider> providers,
        Map<String, Object> quality,
        List<Map<String, Object>> environments,
        Map<String, Object> security,
        Map<String, Object> infrastructure,
        Map<String, Object> delivery,
        Map<String, Object> registries,
        Map<String, Provenance> provenance,
        List<String> warnings,
        // V06-WORK-001: Contract & Profile Foundation
        String applicationProfile,
        String stackProfile,
        List<ResolvedFrontend> frontends,
        List<ResolvedBusinessModule> businessModules) {

    public EffectiveProjectModel {
        technology = technology == null ? Map.of() : Map.copyOf(technology);
        modules = modules == null ? List.of() : List.copyOf(modules);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        providers = providers == null ? List.of() : List.copyOf(providers);
        environments = environments == null ? List.of() : List.copyOf(environments);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        frontends = frontends == null ? List.of() : List.copyOf(frontends);
        businessModules = businessModules == null ? List.of() : List.copyOf(businessModules);
    }

    public static final int SCHEMA_VERSION = 1;
}
