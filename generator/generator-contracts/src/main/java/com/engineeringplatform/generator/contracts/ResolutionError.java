package com.engineeringplatform.generator.contracts;

import java.util.Map;

/**
 * Resolver error envelope (004A Contract).
 * Aligns with resolver-error.schema.yaml. Only minimal codes used by 004B
 * (UNKNOWN_REFERENCE / UNKNOWN_PROFILE / CONSTRAINT_VIOLATION) are produced here;
 * the full 12-code catalog belongs to the Resolver Error Contract.
 *
 * @param code          resolver error code (UPPER_SNAKE_CASE)
 * @param message       human readable message
 * @param severity      INFO / WARNING / ERROR / CRITICAL
 * @param source        error source (e.g. project-manifest, registry, profile)
 * @param sourcePath    precise location (manifest path + JSON pointer)
 * @param referenceType reference type when reference-related (may be null)
 * @param referenceId   referenced id (may be null)
 * @param details       extra structured details (may be empty)
 */
public record ResolutionError(
        String code,
        String message,
        Severity severity,
        String source,
        String sourcePath,
        String referenceType,
        String referenceId,
        Map<String, Object> details) {

    public enum Severity { INFO, WARNING, ERROR, CRITICAL }

    public ResolutionError {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * V07-WORK-001: generic ERROR factory for business-contract validation.
     *
     * @param code         resolver error code (UPPER_SNAKE_CASE)
     * @param message      human readable message
     * @param source       error source (e.g. module-manifest)
     * @param sourcePath   module id
     * @param referenceId  relation/field name (may be null)
     */
    public static ResolutionError of(String code, String message, String source, String sourcePath, String referenceId) {
        return new ResolutionError(code, message, Severity.ERROR, source, sourcePath, null, referenceId, Map.of());
    }

    public static ResolutionError unknownReference(String referenceType, String referenceId, String sourcePath) {
        return new ResolutionError(
                "UNKNOWN_REFERENCE",
                "Unknown " + referenceType + " reference: " + referenceId,
                Severity.ERROR,
                "registry",
                sourcePath,
                referenceType,
                referenceId,
                Map.of());
    }

    public static ResolutionError unknownProfile(String profile, String sourcePath) {
        return new ResolutionError(
                "UNKNOWN_PROFILE",
                "Unknown profile: " + profile,
                Severity.ERROR,
                "project-manifest",
                sourcePath,
                "profile",
                profile,
                Map.of());
    }

    public static ResolutionError constraintViolation(String message, String sourcePath) {
        return new ResolutionError(
                "CONSTRAINT_VIOLATION",
                message,
                Severity.ERROR,
                "project-manifest",
                sourcePath,
                null,
                null,
                Map.of());
    }

    public static ResolutionError dependencyConflict(String message, String sourcePath) {
        return new ResolutionError(
                "DEPENDENCY_CONFLICT",
                message,
                Severity.ERROR,
                "project-manifest",
                sourcePath,
                "module",
                null,
                Map.of());
    }

    public static ResolutionError capabilityMissing(String capabilityId, String sourcePath) {
        return new ResolutionError(
                "CAPABILITY_MISSING",
                "Capability requirement cannot be satisfied: " + capabilityId,
                Severity.ERROR,
                "registry",
                sourcePath,
                "capability",
                capabilityId,
                Map.of());
    }

    public static ResolutionError providerMissing(String capabilityId, String sourcePath) {
        return new ResolutionError(
                "PROVIDER_MISSING",
                "No provider satisfies capability: " + capabilityId,
                Severity.ERROR,
                "registry",
                sourcePath,
                "provider",
                capabilityId,
                Map.of());
    }

    /** V02-WORK-003: referenced engineering asset does not exist. */
    public static ResolutionError assetMissing(String assetId, String requiredBy) {
        return new ResolutionError(
                "ASSET_MISSING",
                "Engineering asset not found: " + assetId + " (required by " + requiredBy + ")",
                Severity.ERROR,
                "assets",
                "capabilities/" + assetId + "/asset.yaml",
                "asset",
                assetId,
                Map.of("requiredBy", requiredBy));
    }

    /** V02-WORK-003: cyclic asset dependency. */
    public static ResolutionError assetDependencyCycle(String capabilityId, String cyclePath) {
        return new ResolutionError(
                "ASSET_DEPENDENCY_CYCLE",
                "Cyclic asset dependency involving: " + capabilityId + " (" + cyclePath + ")",
                Severity.ERROR,
                "assets",
                "capabilities/" + capabilityId + "/asset.yaml",
                "capability",
                capabilityId,
                Map.of("cycle", cyclePath));
    }

    /** V02-WORK-003: asset compatibility mismatch (java/springBoot/provider/capability). */
    public static ResolutionError incompatibleAsset(String assetId, String requirement, String actual) {
        return new ResolutionError(
                "COMPATIBILITY_FAILURE",
                "Asset " + assetId + " incompatible: " + requirement + " (actual: " + actual + ")",
                Severity.ERROR,
                "assets",
                "compatibility",
                "asset",
                assetId,
                Map.of("requirement", requirement, "actual", String.valueOf(actual)));
    }
}
