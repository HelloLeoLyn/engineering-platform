package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.Provenance;
import com.engineeringplatform.generator.contracts.ProvenanceSource;
import com.engineeringplatform.generator.contracts.ResolutionReport;
import com.engineeringplatform.generator.contracts.ResolverInput;
import com.engineeringplatform.generator.contracts.SnapshotMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ResolutionReport Assembly (EP-WORK-004C+D).
 * Follows the 004A resolution-report.schema.yaml contract; only records events
 * that actually occurred; does not copy the full EPM.
 */
public final class ResolutionReportAssembler {

    public ResolutionReport assemble(
            ResolverInput input,
            IntermediateResolutionState state,
            SnapshotMetadata snapshot) {

        return new ResolutionReport(
                ResolutionReport.SCHEMA_VERSION,
                snapshot.resolutionId(),
                defaultsApplied(state),
                dependenciesAdded(state),
                providersSelected(state),
                overrides(state),
                constraints(state),
                state.warnings(),
                state.compatibilityFindings(),
                state.securityFindings(),
                state.qualityEscalations(),
                state.deprecatedExperimentalAssets(),
                List.of());
    }

    private static List<ResolutionReport.AppliedDefault> defaultsApplied(IntermediateResolutionState state) {
        List<ResolutionReport.AppliedDefault> result = new ArrayList<>();
        for (Map.Entry<String, Provenance> e : state.provenance().entrySet()) {
            ProvenanceSource source = e.getValue().source();
            if (source == ProvenanceSource.PLATFORM_DEFAULT || source == ProvenanceSource.PROFILE_DEFAULT) {
                result.add(new ResolutionReport.AppliedDefault(
                        e.getKey(), e.getValue().value(), source.name().toLowerCase().replace('_', '-')));
            }
        }
        return result;
    }

    private static List<ResolutionReport.DependencyAdded> dependenciesAdded(IntermediateResolutionState state) {
        List<ResolutionReport.DependencyAdded> result = new ArrayList<>();
        for (var module : state.resolvedModules()) {
            if (module.activation().code().equals("required")
                    || module.activation().code().equals("optional-triggered")) {
                result.add(new ResolutionReport.DependencyAdded(
                        module.id(), module.reason(), module.requiredBy()));
            }
        }
        return result;
    }

    private static List<ResolutionReport.ProviderSelected> providersSelected(IntermediateResolutionState state) {
        List<ResolutionReport.ProviderSelected> result = new ArrayList<>();
        for (var provider : state.resolvedProviders()) {
            String capability = provider.implementsList().isEmpty()
                    ? null : provider.implementsList().get(0);
            result.add(new ResolutionReport.ProviderSelected(
                    provider.id(), capability, provider.reason()));
        }
        return result;
    }

    private static List<ResolutionReport.Override> overrides(IntermediateResolutionState state) {
        List<ResolutionReport.Override> result = new ArrayList<>();
        for (Map.Entry<String, Provenance> e : state.provenance().entrySet()) {
            ProvenanceSource source = e.getValue().source();
            if (source == ProvenanceSource.PROJECT || source == ProvenanceSource.CUSTOMER_CONSTRAINT) {
                result.add(new ResolutionReport.Override(
                        e.getKey(), null, e.getValue().value(),
                        source.name().toLowerCase().replace('_', '-')));
            }
        }
        return result;
    }

    private static List<ResolutionReport.Constraint> constraints(IntermediateResolutionState state) {
        List<ResolutionReport.Constraint> result = new ArrayList<>();
        for (Map.Entry<String, Provenance> e : state.provenance().entrySet()) {
            if (e.getValue().source() == ProvenanceSource.CUSTOMER_CONSTRAINT) {
                result.add(new ResolutionReport.Constraint(
                        e.getKey(), e.getValue().value(), e.getValue().sourcePath()));
            }
        }
        return result;
    }
}
