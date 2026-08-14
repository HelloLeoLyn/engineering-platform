package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolutionReport;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 12 — Quality Resolution (EP-WORK-004C+D).
 *
 * Resolves the final quality level (Q1/Q2/Q3) from the resolved
 * profiles.quality value (already merged/overridden) or project.quality.minimum.
 * Unknown -> UNKNOWN_QUALITY. Escalation (final > profile default) is recorded
 * as qualityEscalations in the ResolutionReport.
 */
public final class QualityResolver {

    private static final Set<String> VALID_LEVELS = Set.of("Q1", "Q2", "Q3");
    private static final List<String> LEVEL_ORDER = List.of("Q1", "Q2", "Q3");

    public void resolve(ResolverInput input, IntermediateResolutionState.Builder state) {
        // project.quality.minimum 是硬约束（可抬高质量），优先于 profile 默认；
        // 无显式 minimum 时回落到已合并的 profiles.quality（profile default）。
        Object raw = qualityFromProject(input);
        if (raw == null) {
            raw = state.value("profiles.quality");
        }
        String quality = raw == null ? null : String.valueOf(raw);
        if (quality == null || !VALID_LEVELS.contains(quality)) {
            state.error(new ResolutionError(
                    "UNKNOWN_QUALITY",
                    "Unknown quality level: " + quality,
                    ResolutionError.Severity.ERROR,
                    "project-manifest", "project.yaml:/quality/minimum",
                    "quality", quality, Map.of()));
            return;
        }
        state.quality(quality);

        // Escalation: final quality higher than the selected profile default
        String profileDefault = profileDefaultQuality(input);
        if (profileDefault != null
                && LEVEL_ORDER.indexOf(quality) > LEVEL_ORDER.indexOf(profileDefault)) {
            state.qualityEscalation(new ResolutionReport.QualityEscalation(
                    profileDefault, quality, "project/constraint raised quality above profile default"));
        }
    }

    private static Object qualityFromProject(ResolverInput input) {
        Object quality = input.projectManifest().get("quality");
        if (quality instanceof Map<?, ?> m && m.get("minimum") != null) {
            return m.get("minimum");
        }
        return null;
    }

    /** Quality of the project-selected profile preset (null when unknown). */
    private static String profileDefaultQuality(ResolverInput input) {
        String profileName = selectedProfileName(input);
        if (profileName == null) {
            return null;
        }
        Object profiles = input.platformManifest().get("profiles");
        if (!(profiles instanceof Map<?, ?> profilesMap)) {
            return null;
        }
        Object presets = profilesMap.get("presets");
        if (!(presets instanceof Map<?, ?> presetsMap)) {
            return null;
        }
        Object preset = presetsMap.get(profileName);
        if (preset instanceof Map<?, ?> presetMap && presetMap.get("quality") instanceof String q) {
            return q;
        }
        return null;
    }

    private static String selectedProfileName(ResolverInput input) {
        Object profiles = input.projectManifest().get("profiles");
        if (profiles instanceof String s) {
            return s;
        }
        if (profiles instanceof Map<?, ?> m && m.get("default") instanceof String s) {
            return s;
        }
        return null;
    }
}
