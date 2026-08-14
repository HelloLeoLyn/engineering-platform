package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolutionReport;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolvedModule;
import com.engineeringplatform.generator.contracts.ResolvedProvider;
import com.engineeringplatform.generator.contracts.ResolverInput;
import com.engineeringplatform.generator.contracts.SnapshotMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Complete Resolver — full 13-step pipeline + final artifacts (EP-WORK-004C+D).
 *
 * Steps 1-6 reuse the 004B foundation components; steps 7-13 and final
 * assembly (EffectiveProjectModel / ResolutionReport / Snapshot / Summary)
 * are implemented here.
 *
 * Fatal Error Policy:
 *  - Intermediate state may contain both values and errors
 *  - If any severity=ERROR resolver error exists -> ResolutionResult = FAILED,
 *    no executable EffectiveProjectModel, but a ResolutionReport is produced
 *    to explain the failure
 *  - Otherwise -> SUCCESS with EPM + report + summary
 */
public final class CompleteResolver {

    private final ManifestValidationPort validationPort;
    private final ReferenceResolver referenceResolver;
    private final ProfileExpander profileExpander;
    private final DefaultsMerger defaultsMerger;
    private final OverrideResolver overrideResolver;
    private final ConstraintEnforcer constraintEnforcer;
    private final DependencyResolver dependencyResolver;
    private final CapabilityResolver capabilityResolver;
    private final ProviderResolver providerResolver;
    private final CompatibilityValidator compatibilityValidator;
    private final SecurityGovernanceValidator securityGovernanceValidator;
    private final QualityResolver qualityResolver;
    private final EnvironmentResolver environmentResolver;
    private final SnapshotFactory snapshotFactory;
    private final EffectiveProjectModelAssembler epmAssembler;
    private final ResolutionReportAssembler reportAssembler;
    private final SummaryRenderer summaryRenderer;
    private final AssetContext assetContext;

    public CompleteResolver(ManifestValidationPort validationPort) {
        this(validationPort, SnapshotFactory.RESOLVER_VERSION, null);
    }

    /** Package-private / test-friendly: allows a different resolverVersion. */
    CompleteResolver(ManifestValidationPort validationPort, String resolverVersion) {
        this(validationPort, resolverVersion, null);
    }

    /** V02-WORK-003: asset-aware resolution enriches the same pipeline (may be null). */
    public CompleteResolver(ManifestValidationPort validationPort, AssetContext assetContext) {
        this(validationPort, SnapshotFactory.RESOLVER_VERSION, assetContext);
    }

    private CompleteResolver(ManifestValidationPort validationPort, String resolverVersion,
                             AssetContext assetContext) {
        this.validationPort = validationPort;
        this.referenceResolver = new ReferenceResolver();
        this.profileExpander = new ProfileExpander();
        this.defaultsMerger = new DefaultsMerger();
        this.overrideResolver = new OverrideResolver();
        this.constraintEnforcer = new ConstraintEnforcer();
        this.dependencyResolver = new DependencyResolver();
        this.capabilityResolver = new CapabilityResolver();
        this.providerResolver = new ProviderResolver();
        this.compatibilityValidator = new CompatibilityValidator();
        this.securityGovernanceValidator = new SecurityGovernanceValidator();
        this.qualityResolver = new QualityResolver();
        this.environmentResolver = new EnvironmentResolver();
        this.snapshotFactory = new SnapshotFactory(resolverVersion);
        this.epmAssembler = new EffectiveProjectModelAssembler();
        this.reportAssembler = new ResolutionReportAssembler();
        this.summaryRenderer = new SummaryRenderer();
        this.assetContext = assetContext;
    }

    public ResolutionResult resolve(ResolverInput input) {
        IntermediateResolutionState.Builder state = IntermediateResolutionState.builder();

        // Step 1 — Schema Validation Boundary
        runSchemaValidation(input, state);

        // Step 2 — Reference Resolution
        referenceResolver.resolve(input, state);

        // V02-WORK-003: asset-level errors (missing/cycle/compatibility) join the same state
        if (assetContext != null) {
            for (var error : assetContext.errors()) {
                state.error(error);
            }
        }

        // Step 3 — Profile Expansion
        List<String> activeProfiles = profileExpander.expand(input, state);

        // Step 4 — Defaults Merge (Platform Default < Profile Default)
        defaultsMerger.merge(input, activeProfiles, state);

        // Step 5 — Project Overrides (Project Preference)
        overrideResolver.apply(input, state);

        // Step 6 — Constraint Enforcement (Customer Constraint < Platform Guardrail)
        constraintEnforcer.enforce(input, state);

        // Step 7 — Dependency Resolution
        List<ResolvedModule> modules = dependencyResolver.resolve(input, state);

        // Step 8 — Capability Resolution
        List<ResolvedCapability> capabilities = capabilityResolver.resolve(input, modules, state);

        // V02-WORK-003: asset dependency closure joins the same capability model (dedup, stable order)
        if (assetContext != null) {
            for (ResolvedCapability capability : assetContext.capabilityClosure()) {
                state.capability(capability);
            }
            capabilities = new ArrayList<>(capabilities);
            for (ResolvedCapability capability : assetContext.capabilityClosure()) {
                if (capabilities.stream().noneMatch(c -> c.id().equals(capability.id()))) {
                    capabilities.add(capability);
                }
            }
        }

        // Step 9 — Provider Resolution (only capabilities that declare a provider need one)
        List<ResolvedCapability> providerCandidates = assetContext == null ? capabilities
                : capabilities.stream()
                        .filter(c -> assetContext.providerRequiredCapabilityIds().contains(c.id()))
                        .toList();
        List<ResolvedProvider> providers = providerResolver.resolve(input, providerCandidates, state);

        // Step 10 — Compatibility Validation
        compatibilityValidator.validate(input, modules, providers, state);

        // Step 11 — Security/Governance Validation
        securityGovernanceValidator.validate(input, state);

        // Step 12 — Quality Resolution
        qualityResolver.resolve(input, state);

        // Step 13 — Environment Resolution
        environmentResolver.resolve(input, state);

        IntermediateResolutionState resolved = state.build();

        // Snapshot metadata (resolutionId / resolverVersion / inputHash)
        SnapshotMetadata snapshot = snapshotFactory.create(input, resolved);

        // Fatal Error Policy
        if (resolved.hasFatalErrors()) {
            ResolutionReport report = reportAssembler.assemble(input, resolved, snapshot);
            String summary = summaryRenderer.renderFailure(report, resolved);
            return ResolutionResult.failed(report, resolved.errors(), summary);
        }

        // Final artifacts
        EffectiveProjectModel epm = epmAssembler.assemble(input, resolved, snapshot);
        ResolutionReport report = reportAssembler.assemble(input, resolved, snapshot);
        String summary = summaryRenderer.render(epm);
        return ResolutionResult.success(epm, report, summary);
    }

    private void runSchemaValidation(ResolverInput input, IntermediateResolutionState.Builder state) {
        List<ResolutionError> errors = new ArrayList<>();
        check(input.platformManifest(), "platform", errors);
        check(input.projectManifest(), "project", errors);
        if (!errors.isEmpty()) {
            state.errors(errors);
        }
    }

    private void check(Map<String, Object> manifest, String manifestType, List<ResolutionError> errors) {
        if (!validationPort.isValid(manifestType, manifest)) {
            for (String message : validationPort.validationErrors(manifestType, manifest)) {
                errors.add(new ResolutionError(
                        "SCHEMA_VALIDATION_FAILED",
                        message,
                        ResolutionError.Severity.ERROR,
                        manifestType + "-manifest",
                        null, null, null, Map.of()));
            }
        }
    }
}
