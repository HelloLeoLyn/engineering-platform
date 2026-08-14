package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * ResolutionReport (004A resolution-report.schema.yaml contract).
 * Explains HOW/WHY the resolution happened; does NOT copy the full EPM.
 * Only records events that actually occurred.
 *
 * @param schemaVersion                 contract schema version (const 1)
 * @param resolutionId                  references the EPM snapshot
 * @param defaultsApplied               applied defaults (key/value/source)
 * @param dependenciesAdded             added dependencies (dependencyId/reason/requiredBy)
 * @param providersSelected             selected providers (providerId/capability/reason)
 * @param overrides                     overrides (key/from/to/source)
 * @param constraints                   enforced constraints (name/value/reason)
 * @param warnings                      warnings
 * @param compatibility                 compatibility findings
 * @param securityFindings              security findings
 * @param qualityEscalations            quality escalations (from/to/reason)
 * @param deprecatedExperimentalAssets  deprecated/experimental asset list
 * @param suggestions                   optional suggestions (no Patch DSL)
 */
public record ResolutionReport(
        int schemaVersion,
        String resolutionId,
        List<AppliedDefault> defaultsApplied,
        List<DependencyAdded> dependenciesAdded,
        List<ProviderSelected> providersSelected,
        List<Override> overrides,
        List<Constraint> constraints,
        List<String> warnings,
        List<CompatibilityFinding> compatibility,
        List<SecurityFinding> securityFindings,
        List<QualityEscalation> qualityEscalations,
        List<String> deprecatedExperimentalAssets,
        List<Suggestion> suggestions) {

    public static final int SCHEMA_VERSION = 1;

    public ResolutionReport {
        defaultsApplied = defaultsApplied == null ? List.of() : List.copyOf(defaultsApplied);
        dependenciesAdded = dependenciesAdded == null ? List.of() : List.copyOf(dependenciesAdded);
        providersSelected = providersSelected == null ? List.of() : List.copyOf(providersSelected);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        compatibility = compatibility == null ? List.of() : List.copyOf(compatibility);
        securityFindings = securityFindings == null ? List.of() : List.copyOf(securityFindings);
        qualityEscalations = qualityEscalations == null ? List.of() : List.copyOf(qualityEscalations);
        deprecatedExperimentalAssets = deprecatedExperimentalAssets == null ? List.of() : List.copyOf(deprecatedExperimentalAssets);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public record AppliedDefault(String key, Object value, String source) { }
    public record DependencyAdded(String dependencyId, String reason, List<String> requiredBy) { }
    public record ProviderSelected(String providerId, String capability, String reason) { }
    public record Override(String key, Object from, Object to, String source) { }
    public record Constraint(String name, Object value, String reason) { }
    public record CompatibilityFinding(String subject, String target, boolean compatible, String detail) { }
    public record SecurityFinding(String code, String severity, String detail) { }
    public record QualityEscalation(String from, String to, String reason) { }
    public record Suggestion(String type, String target, String reason) { }
}
