package com.engineeringplatform.generator.contracts;

import java.util.Map;

/**
 * Resolver Foundation minimal input (004B).
 * Pure computation inputs only: declarative manifests + registry snapshot.
 * No OpenClaw / filesystem runtime state / GUI / network / database.
 *
 * @param platformManifest  platform manifest (platform.yaml parsed as Map)
 * @param projectManifest   project manifest (project.yaml parsed as Map)
 * @param moduleManifests   module manifest collection keyed by module id
 * @param providerManifests provider manifest collection keyed by provider id
 * @param registrySnapshot  registry snapshot (type -> ids), e.g. {"modules": ["sample-customer"]}
 */
public record ResolverInput(
        Map<String, Object> platformManifest,
        Map<String, Object> projectManifest,
        Map<String, Map<String, Object>> moduleManifests,
        Map<String, Map<String, Object>> providerManifests,
        Map<String, java.util.Set<String>> registrySnapshot) {

    public ResolverInput {
        platformManifest = platformManifest == null ? Map.of() : Map.copyOf(platformManifest);
        projectManifest = projectManifest == null ? Map.of() : Map.copyOf(projectManifest);
        // 声明顺序是 resolver 语义的一部分（stable declaration order）：
        // 用 LinkedHashMap 保序复制，Map.copyOf 不保证迭代顺序。
        moduleManifests = moduleManifests == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(moduleManifests));
        providerManifests = providerManifests == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(providerManifests));
        registrySnapshot = registrySnapshot == null ? Map.of() : Map.copyOf(registrySnapshot);
    }

    public static ResolverInput minimal(Map<String, Object> platform, Map<String, Object> project) {
        return new ResolverInput(platform, project, Map.of(), Map.of(), Map.of());
    }
}
