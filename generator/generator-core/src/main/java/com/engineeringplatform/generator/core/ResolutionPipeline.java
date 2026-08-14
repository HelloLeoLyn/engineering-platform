package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolution Foundation Pipeline — first six steps (EP-WORK-004B).
 *
 * <ol>
 *   <li>Schema Validation Boundary</li>
 *   <li>Reference Resolution</li>
 *   <li>Profile Expansion</li>
 *   <li>Defaults Merge</li>
 *   <li>Project Overrides</li>
 *   <li>Constraint Enforcement</li>
 * </ol>
 *
 * Pure computation: never mutates any input manifest or registry.
 * Output is an immutable {@link IntermediateResolutionState}.
 *
 * Later steps (dependency/capability/provider/compatibility/security/quality/
 * environment) and final EPM assembly belong to EP-WORK-004C/004D.
 */
public final class ResolutionPipeline {

    private final ManifestValidationPort validationPort;
    private final ReferenceResolver referenceResolver;
    private final ProfileExpander profileExpander;
    private final DefaultsMerger defaultsMerger;
    private final OverrideResolver overrideResolver;
    private final ConstraintEnforcer constraintEnforcer;

    public ResolutionPipeline(
            ManifestValidationPort validationPort,
            ReferenceResolver referenceResolver,
            ProfileExpander profileExpander,
            DefaultsMerger defaultsMerger,
            OverrideResolver overrideResolver,
            ConstraintEnforcer constraintEnforcer) {
        this.validationPort = validationPort;
        this.referenceResolver = referenceResolver;
        this.profileExpander = profileExpander;
        this.defaultsMerger = defaultsMerger;
        this.overrideResolver = overrideResolver;
        this.constraintEnforcer = constraintEnforcer;
    }

    public static ResolutionPipeline withDefaults(ManifestValidationPort validationPort) {
        return new ResolutionPipeline(
                validationPort,
                new ReferenceResolver(),
                new ProfileExpander(),
                new DefaultsMerger(),
                new OverrideResolver(),
                new ConstraintEnforcer());
    }

    /**
     * Runs the first six steps and produces the intermediate resolution state.
     * Input manifests are never modified.
     */
    public IntermediateResolutionState resolve(ResolverInput input) {
        IntermediateResolutionState.Builder state = IntermediateResolutionState.builder();

        // Step 1 — Schema Validation Boundary
        runSchemaValidation(input, state);

        // Step 2 — Reference Resolution
        referenceResolver.resolve(input, state);

        // Step 3 — Profile Expansion
        List<String> activeProfiles = profileExpander.expand(input, state);

        // Step 4 — Defaults Merge (Platform Default < Profile Default)
        defaultsMerger.merge(input, activeProfiles, state);

        // Step 5 — Project Overrides (Project Preference)
        overrideResolver.apply(input, state);

        // Step 6 — Constraint Enforcement (Customer Constraint < Platform Guardrail)
        constraintEnforcer.enforce(input, state);

        return state.build();
    }

    private void runSchemaValidation(ResolverInput input, IntermediateResolutionState.Builder state) {
        List<ResolutionError> errors = new ArrayList<>();
        if (!validationPort.isValid("platform", input.platformManifest())) {
            errors.addAll(toErrors("platform", validationPort.validationErrors("platform", input.platformManifest())));
        }
        if (!validationPort.isValid("project", input.projectManifest())) {
            errors.addAll(toErrors("project", validationPort.validationErrors("project", input.projectManifest())));
        }
        if (!errors.isEmpty()) {
            state.errors(errors);
        }
    }

    private List<ResolutionError> toErrors(String manifestType, List<String> messages) {
        return messages.stream()
                .map(msg -> new ResolutionError(
                        "SCHEMA_VALIDATION_FAILED",
                        msg,
                        ResolutionError.Severity.ERROR,
                        manifestType + "-manifest",
                        null, null, null, java.util.Map.of()))
                .toList();
    }
}
