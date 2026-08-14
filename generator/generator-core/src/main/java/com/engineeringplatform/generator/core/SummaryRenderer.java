package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.Provenance;

import java.util.Map;

/**
 * Human-readable effective-project-summary.md renderer (EP-WORK-004C+D).
 * Pure computation: EPM -> String. Does NOT write files (that belongs to the
 * Generator/Artifact layer). Tests verify generated content.
 */
public final class SummaryRenderer {

    public String render(EffectiveProjectModel epm) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Effective Project Summary\n\n");

        Map<String, Object> identity = epm.identity();
        sb.append("## Project\n\n");
        sb.append("- id: ").append(value(identity.get("id"))).append('\n');
        sb.append("- name: ").append(value(identity.get("name"))).append('\n');
        sb.append("- version: ").append(value(identity.get("version"))).append('\n');

        sb.append("\n## Resolution\n\n");
        sb.append("- resolutionId: ").append(epm.resolution().resolutionId()).append('\n');
        sb.append("- resolverVersion: ").append(epm.resolution().resolverVersion()).append('\n');
        sb.append("- inputHash: ").append(epm.resolution().inputHash()).append('\n');

        sb.append("\n## Platform\n\n");
        Map<String, Object> platform = epm.platform();
        sb.append("- id: ").append(value(platform.get("id"))).append('\n');
        sb.append("- version: ").append(value(platform.get("version"))).append('\n');

        sb.append("\n## Profiles\n\n");
        for (Map.Entry<String, Object> e : epm.profiles().entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(value(e.getValue())).append('\n');
        }

        sb.append("\n## Quality\n\n");
        sb.append("- minimum: ").append(value(epm.quality().get("minimum"))).append('\n');

        sb.append("\n## Modules (").append(epm.modules().size()).append(")\n\n");
        for (var module : epm.modules()) {
            sb.append("- ").append(module.id()).append(" [").append(module.activation().code()).append("]");
            if (!module.requiredBy().isEmpty()) {
                sb.append(" requiredBy=").append(module.requiredBy());
            }
            sb.append('\n');
        }

        sb.append("\n## Capabilities (").append(epm.capabilities().size()).append(")\n\n");
        for (var capability : epm.capabilities()) {
            sb.append("- ").append(capability.id()).append(" [").append(capability.activation().code()).append("]");
            if (capability.provider() != null) {
                sb.append(" provider=").append(capability.provider());
            }
            sb.append('\n');
        }

        sb.append("\n## Providers (").append(epm.providers().size()).append(")\n\n");
        for (var provider : epm.providers()) {
            sb.append("- ").append(provider.id()).append(" [").append(provider.activation().code()).append("]")
                    .append(" implements=").append(provider.implementsList()).append('\n');
        }

        sb.append("\n## Environments (").append(epm.environments().size()).append(")\n\n");
        for (var env : epm.environments()) {
            sb.append("- ").append(value(env.get("name"))).append('\n');
        }

        sb.append("\n## Security\n\n");
        Object findings = epm.security().get("findings");
        if (findings instanceof java.util.List<?> list && !list.isEmpty()) {
            sb.append("- ").append(list.size()).append(" finding(s)\n");
        } else {
            sb.append("- no findings\n");
        }

        sb.append("\n## Provenance (").append(epm.provenance().size()).append(" entries)\n\n");
        for (Map.Entry<String, Provenance> e : epm.provenance().entrySet()) {
            sb.append("- ").append(e.getKey()).append(": source=")
                    .append(e.getValue().source().name().toLowerCase().replace('_', '-'))
                    .append('\n');
        }

        sb.append("\n## Warnings (").append(epm.warnings().size()).append(")\n\n");
        for (String warning : epm.warnings()) {
            sb.append("- ").append(warning).append('\n');
        }

        return sb.toString();
    }

    /** Failure summary: explains why resolution failed (no EPM exists). */
    public String renderFailure(
            com.engineeringplatform.generator.contracts.ResolutionReport report,
            com.engineeringplatform.generator.contracts.IntermediateResolutionState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Effective Project Summary (FAILED)\n\n");
        sb.append("Resolution failed with ").append(state.errors().size()).append(" error(s):\n\n");
        for (var error : state.errors()) {
            sb.append("- [").append(error.severity()).append("] ")
                    .append(error.code()).append(": ").append(error.message()).append('\n');
        }
        if (report != null) {
            sb.append("\nResolutionReport resolutionId: ").append(report.resolutionId()).append('\n');
        }
        return sb.toString();
    }

    private static String value(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }
}
