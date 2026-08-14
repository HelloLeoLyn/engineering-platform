package com.engineeringplatform.generator.contracts;

/**
 * A single conformance finding (V02-WORK-005 §2/§13).
 *
 * ruleId is a stable machine identity (e.g. "technology.java-version",
 * "structure.required-file", "dependency.required", "dependency.forbidden",
 * "config.required", "provider.mismatch", "asset.required-file").
 */
public record ConformanceFinding(
        String ruleId,
        ConformanceSeverity severity,
        String message,
        String assetId,
        String path) {

    public ConformanceFinding {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
    }

    public static ConformanceFinding error(String ruleId, String message, String assetId, String path) {
        return new ConformanceFinding(ruleId, ConformanceSeverity.ERROR, message, assetId, path);
    }

    public static ConformanceFinding warning(String ruleId, String message, String assetId, String path) {
        return new ConformanceFinding(ruleId, ConformanceSeverity.WARNING, message, assetId, path);
    }
}
