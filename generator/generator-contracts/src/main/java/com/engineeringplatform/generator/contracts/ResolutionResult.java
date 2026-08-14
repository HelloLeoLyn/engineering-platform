package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Top-level resolution result (EP-WORK-004C+D).
 *
 * <ul>
 *   <li>SUCCESS: effectiveProject must be present; errors empty</li>
 *   <li>FAILED:  effectiveProject must be absent; errors explain the failure</li>
 *   <li>report:  present in both states (explains process / failure reasons)</li>
 * </ul>
 *
 * @param status           SUCCESS or FAILED
 * @param effectiveProject present only on SUCCESS (nullable)
 * @param report           always present
 * @param errors           failure reasons (empty on SUCCESS)
 * @param summary          human-readable summary (always present)
 */
public record ResolutionResult(
        Status status,
        EffectiveProjectModel effectiveProject,
        ResolutionReport report,
        List<ResolutionError> errors,
        String summary) {

    public enum Status { SUCCESS, FAILED }

    public ResolutionResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ResolutionResult success(
            EffectiveProjectModel effectiveProject, ResolutionReport report, String summary) {
        return new ResolutionResult(Status.SUCCESS, effectiveProject, report, List.of(), summary);
    }

    public static ResolutionResult failed(
            ResolutionReport report, List<ResolutionError> errors, String summary) {
        return new ResolutionResult(Status.FAILED, null, report, errors, summary);
    }
}
