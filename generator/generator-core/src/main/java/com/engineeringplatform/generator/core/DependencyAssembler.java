package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolvedProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maven dependency assembly (V02-WORK-004 §6/§7).
 *
 * Sources:
 *   - asset conformance.requiredDependencies ("group:artifact", versions managed by Spring Boot BOM)
 *   - provider GAV fixtures (tests/fixtures/*.gav.yaml — provider metadata, not the provider itself)
 *
 * Rules:
 *   - stable dedup: same GA emitted once
 *   - version conflict (same GA, different versions) -> GenerationException, never silent pick
 *   - Provider != Maven Dependency: GAV metadata is just part of provider implementation
 */
public final class DependencyAssembler {

    private DependencyAssembler() {
    }

    public static List<AssetRepository.MavenDependency> assemble(EffectiveProjectModel epm,
                                                                 AssetRepository repo) throws IOException {
        // enabled asset ids in stable EPM order
        List<String> enabledAssets = new ArrayList<>();
        for (ResolvedCapability capability : epm.capabilities()) {
            enabledAssets.add(capability.id());
        }
        for (ResolvedProvider provider : epm.providers()) {
            enabledAssets.add(provider.id());
        }

        Map<String, AssetRepository.MavenDependency> byGa = new LinkedHashMap<>();
        for (String assetId : enabledAssets) {
            // 1. conformance.requiredDependencies (GA only; version from BOM when unversioned)
            for (String ga : requiredDependencies(repo, assetId)) {
                String[] parts = ga.split(":", 2);
                AssetRepository.MavenDependency dep =
                        new AssetRepository.MavenDependency(parts[0], parts[1], null, null);
                merge(byGa, dep, assetId);
            }
            // 2. provider GAV fixtures (versioned metadata)
            for (AssetRepository.MavenDependency dep : repo.gavFixtures(assetId)) {
                merge(byGa, dep, assetId);
            }
        }
        return List.copyOf(byGa.values());
    }

    private static void merge(Map<String, AssetRepository.MavenDependency> byGa,
                              AssetRepository.MavenDependency dep, String assetId) {
        String group = dep.groupId();
        String artifact = dep.artifactId();
        // GA-only deps come as "group:artifact" in the ga() field
        if (artifact == null && dep.ga().contains(":")) {
            String[] parts = dep.ga().split(":", 2);
            group = parts[0];
            artifact = parts[1];
        }
        String key = group + ":" + artifact;
        AssetRepository.MavenDependency existing = byGa.get(key);
        if (existing == null) {
            byGa.put(key, new AssetRepository.MavenDependency(group, artifact, dep.version(), dep.scope()));
            return;
        }
        // same GA: version must agree
        if (existing.version() != null && dep.version() != null
                && !existing.version().equals(dep.version())) {
            throw new GenerationException("dependency version conflict for " + key
                    + ": " + existing.version() + " (from assets) vs " + dep.version()
                    + " (from " + assetId + ")");
        }
        if (existing.version() == null && dep.version() != null) {
            byGa.put(key, new AssetRepository.MavenDependency(group, artifact, dep.version(), dep.scope()));
        }
    }

    private static List<String> requiredDependencies(AssetRepository repo, String assetId) {
        Map<String, Object> raw = rawOf(repo, assetId);
        List<String> gavs = new ArrayList<>();
        if (raw == null || !(raw.get("conformance") instanceof Map<?, ?> conformance)) {
            return gavs;
        }
        Object deps = conformance.get("requiredDependencies");
        if (deps instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    gavs.add(s);
                }
            }
        }
        return gavs;
    }

    private static Map<String, Object> rawOf(AssetRepository repo, String assetId) {
        // raw YAML accessor is package-private via a small bridge in AssetRepository
        return repo.rawAsset(assetId);
    }
}
