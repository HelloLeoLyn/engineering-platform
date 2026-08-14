package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolutionError;

import java.util.List;
import java.util.Set;

/**
 * Asset-aware resolution context (V02-WORK-003).
 *
 * Carries the capability dependency closure computed from engineering assets,
 * the set of capabilities that require a provider, and any asset-level errors.
 * Consumed by {@link CompleteResolver} to enrich the existing 13-step pipeline
 * without a second resolution model.
 */
public record AssetContext(
        List<ResolvedCapability> capabilityClosure,
        Set<String> providerRequiredCapabilityIds,
        List<ResolutionError> errors) {

    public AssetContext {
        capabilityClosure = capabilityClosure == null ? List.of() : List.copyOf(capabilityClosure);
        providerRequiredCapabilityIds = providerRequiredCapabilityIds == null
                ? Set.of() : Set.copyOf(providerRequiredCapabilityIds);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static AssetContext empty() {
        return new AssetContext(List.of(), Set.of(), List.of());
    }
}
