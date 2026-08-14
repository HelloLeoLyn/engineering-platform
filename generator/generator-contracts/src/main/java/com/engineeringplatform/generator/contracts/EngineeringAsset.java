package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Engineering Asset (V02-WORK-001 Asset Contract V1) — resolver-facing minimal model.
 *
 * Only the fields the resolver consumes are modelled; the full YAML contract
 * (files/configuration/conformance/tests/documentation/metadata) stays in
 * generator/schemas/engineering-asset.schema.yaml as the source of truth.
 */
public record EngineeringAsset(
        String id,
        AssetType type,
        String version,
        String description,
        List<AssetDependency> dependencies,
        AssetCompatibility compatibility) {

    public EngineeringAsset {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("asset id is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("asset type is required");
        }
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        compatibility = compatibility == null ? AssetCompatibility.none() : compatibility;
    }

    /** Asset dependencies filtered by type (empty when none). */
    public List<AssetDependency> dependenciesOf(AssetType dependencyType) {
        return dependencies.stream()
                .filter(d -> d.type() == dependencyType)
                .toList();
    }
}
