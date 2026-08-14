package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asset-aware resolution entry point (V02-WORK-003).
 *
 * Loads real engineering assets (capabilities/ + providers/), computes the
 * capability dependency closure, validates compatibility, then feeds the
 * existing 13-step {@link CompleteResolver} pipeline enriched with an
 * {@link AssetContext}. No second pipeline, no resolver rewrite.
 *
 * Flow:
 *   project manifest + registry/assets -> existing Resolver pipeline -> EffectiveProjectModel
 */
public final class AssetAwareResolver {

    private final ManifestValidationPort validationPort;

    public AssetAwareResolver() {
        this(new ManifestRuntimeValidator());
    }

    public AssetAwareResolver(ManifestValidationPort validationPort) {
        this.validationPort = validationPort;
    }

    /** Resolves against real assets on disk (repository root contains capabilities/ + providers/). */
    public ResolutionResult resolve(Path repoRoot, Map<String, Object> platformManifest,
                                    Map<String, Object> projectManifest) throws IOException {
        return resolve(AssetRepository.load(repoRoot), platformManifest, projectManifest);
    }

    /** Resolves against a loaded asset repository (test injection supported). */
    public ResolutionResult resolve(AssetRepository repo, Map<String, Object> platformManifest,
                                    Map<String, Object> projectManifest) {
        List<String> explicitCapabilities = ReferenceResolver.extractIds(projectManifest.get("capabilities"));
        AssetContext assetContext = AssetResolution.resolve(repo, explicitCapabilities, platformManifest);

        Map<String, Map<String, Object>> providerManifests = repo.toProviderManifests();
        Map<String, Set<String>> registry = new LinkedHashMap<>();
        registry.put("modules", Set.of());
        registry.put("capabilities", Set.copyOf(repo.capabilities().keySet()));
        registry.put("providers", Set.copyOf(repo.providers().keySet()));

        ResolverInput input = new ResolverInput(platformManifest, projectManifest, Map.of(),
                providerManifests, registry);
        CompleteResolver resolver = new CompleteResolver(validationPort, assetContext);
        return resolver.resolve(input);
    }
}
