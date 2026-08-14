package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Declared compatibility surface of an asset (V02-WORK-001 §5).
 * Contract only; matching semantics live in the resolver.
 */
public record AssetCompatibility(
        String java,
        String springBoot,
        List<String> requiredCapabilities,
        List<String> compatibleProviders) {

    public AssetCompatibility {
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        compatibleProviders = compatibleProviders == null ? List.of() : List.copyOf(compatibleProviders);
    }

    public static AssetCompatibility none() {
        return new AssetCompatibility(null, null, List.of(), List.of());
    }
}
