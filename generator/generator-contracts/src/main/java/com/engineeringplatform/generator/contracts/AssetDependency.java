package com.engineeringplatform.generator.contracts;

/**
 * Minimal asset dependency (V02-WORK-001 §4).
 * No version solver; constraint is a free-form string (e.g. "1.x", ">=2").
 */
public record AssetDependency(
        AssetType type,
        String id,
        String version,
        boolean required) {

    public AssetDependency {
        if (type == null) {
            throw new IllegalArgumentException("dependency type is required");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("dependency id is required");
        }
    }

    public static AssetDependency required(AssetType type, String id) {
        return new AssetDependency(type, id, null, true);
    }

    public static AssetDependency required(AssetType type, String id, String version) {
        return new AssetDependency(type, id, version, true);
    }
}
