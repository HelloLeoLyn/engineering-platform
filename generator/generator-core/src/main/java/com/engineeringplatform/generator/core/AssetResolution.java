package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Activation;
import com.engineeringplatform.generator.contracts.AssetCompatibility;
import com.engineeringplatform.generator.contracts.AssetDependency;
import com.engineeringplatform.generator.contracts.AssetType;
import com.engineeringplatform.generator.contracts.EngineeringAsset;
import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolutionError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asset dependency closure + compatibility validation (V02-WORK-003 §5/§6/§7).
 *
 * - required asset dependencies are added automatically (dedup, stable order)
 * - optional dependencies are never auto-added
 * - missing asset -> ASSET_MISSING
 * - cyclic asset dependency -> ASSET_DEPENDENCY_CYCLE
 * - java/springBoot/requiredCapabilities/compatibleProviders validated (no SAT solver)
 */
public final class AssetResolution {

    private AssetResolution() {
    }

    public static AssetContext resolve(AssetRepository repo, List<String> explicitCapabilityIds,
                                       Map<String, Object> platformManifest) {
        Set<String> added = new LinkedHashSet<>(explicitCapabilityIds == null ? List.of() : explicitCapabilityIds);
        List<ResolvedCapability> closure = new ArrayList<>();
        Set<String> providerRequired = new LinkedHashSet<>();
        List<ResolutionError> errors = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();

        for (String explicit : List.copyOf(added)) {
            if (repo.capability(explicit) == null) {
                errors.add(ResolutionError.assetMissing(explicit, "project"));
                continue;
            }
            expand(repo, explicit, "project", added, closure, providerRequired,
                    errors, visiting, visited, new ArrayList<>());
        }

        validateCompatibility(repo, added, providerRequired, platformManifest, errors);
        return new AssetContext(closure, providerRequired, errors);
    }

    private static void expand(AssetRepository repo, String capId, String requiredBy,
                               Set<String> added, List<ResolvedCapability> closure,
                               Set<String> providerRequired, List<ResolutionError> errors,
                               Set<String> visiting, Set<String> visited, List<String> path) {
        if (visiting.contains(capId)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(capId);
            errors.add(ResolutionError.assetDependencyCycle(capId, String.join(" -> ", cycle)));
            return;
        }
        if (visited.contains(capId)) {
            return;
        }
        EngineeringAsset asset = repo.capability(capId);
        if (asset == null) {
            errors.add(ResolutionError.assetMissing(capId, requiredBy));
            return;
        }
        if (!asset.compatibility().compatibleProviders().isEmpty()
                || !asset.dependenciesOf(AssetType.PROVIDER).isEmpty()) {
            providerRequired.add(capId);
        }
        visiting.add(capId);
        path.add(capId);
        for (AssetDependency dep : asset.dependenciesOf(AssetType.CAPABILITY)) {
            if (!dep.required()) {
                continue; // optional dependency is never auto-added
            }
            String depId = dep.id();
            if (!added.contains(depId)) {
                EngineeringAsset depAsset = repo.capability(depId);
                if (depAsset == null) {
                    errors.add(ResolutionError.assetMissing(depId, capId));
                    continue;
                }
                added.add(depId);
                closure.add(new ResolvedCapability(depId, Activation.REQUIRED,
                        "Required by asset " + capId, List.of(capId), null));
            }
            expand(repo, depId, capId, added, closure, providerRequired,
                    errors, visiting, visited, path);
        }
        path.remove(path.size() - 1);
        visiting.remove(capId);
        visited.add(capId);
    }

    private static void validateCompatibility(AssetRepository repo, Set<String> added,
                                              Set<String> providerRequired,
                                              Map<String, Object> platformManifest,
                                              List<ResolutionError> errors) {
        Object technology = platformManifest == null ? null : platformManifest.get("technology");
        String java = null;
        String springBoot = null;
        if (technology instanceof Map<?, ?> tech) {
            Object javaRaw = tech.get("java");
            java = javaRaw == null ? null : String.valueOf(javaRaw);
            Object sbRaw = tech.get("springBoot");
            springBoot = sbRaw == null ? null : String.valueOf(sbRaw);
        }

        for (String capId : added) {
            EngineeringAsset asset = repo.capability(capId);
            if (asset == null) {
                continue;
            }
            AssetCompatibility compat = asset.compatibility();
            if (java != null && compat.java() != null && !matches(compat.java(), java)) {
                errors.add(ResolutionError.incompatibleAsset(capId, "java " + compat.java(), java));
            }
            if (springBoot != null && compat.springBoot() != null && !matches(compat.springBoot(), springBoot)) {
                errors.add(ResolutionError.incompatibleAsset(capId, "springBoot " + compat.springBoot(), springBoot));
            }
            for (String requiredCap : compat.requiredCapabilities()) {
                if (!added.contains(requiredCap)) {
                    errors.add(ResolutionError.incompatibleAsset(capId,
                            "requires capability " + requiredCap, "not in resolution"));
                }
            }
        }

        for (String capId : providerRequired) {
            EngineeringAsset capabilityAsset = repo.capability(capId);
            List<EngineeringAsset> candidates = new ArrayList<>();
            for (EngineeringAsset provider : repo.providers().values()) {
                if (provider.compatibility().requiredCapabilities().contains(capId)) {
                    candidates.add(provider);
                }
            }
            if (candidates.isEmpty()) {
                continue; // PROVIDER_MISSING is reported by the existing ProviderResolver
            }
            EngineeringAsset selected = candidates.get(0);
            if (capabilityAsset != null && !capabilityAsset.compatibility().compatibleProviders().isEmpty()
                    && !capabilityAsset.compatibility().compatibleProviders().contains(selected.id())) {
                errors.add(ResolutionError.incompatibleAsset(capId,
                        "provider " + selected.id() + " not in compatibleProviders",
                        capabilityAsset.compatibility().compatibleProviders().toString()));
                continue;
            }
            AssetCompatibility providerCompat = selected.compatibility();
            if (java != null && providerCompat.java() != null && !matches(providerCompat.java(), java)) {
                errors.add(ResolutionError.incompatibleAsset(selected.id(), "java " + providerCompat.java(), java));
            }
            if (springBoot != null && providerCompat.springBoot() != null
                    && !matches(providerCompat.springBoot(), springBoot)) {
                errors.add(ResolutionError.incompatibleAsset(selected.id(),
                        "springBoot " + providerCompat.springBoot(), springBoot));
            }
        }
    }

    /** Minimal version matching: exact, "x" wildcard suffix, or "+" minimum. No SAT solver. */
    static boolean matches(String constraint, String actual) {
        if (constraint == null || actual == null) {
            return true;
        }
        String c = constraint.trim();
        String a = actual.trim();
        if (c.equals(a)) {
            return true;
        }
        if (c.endsWith(".x")) {
            return a.startsWith(c.substring(0, c.length() - 2));
        }
        if (c.endsWith("+")) {
            return compareVersions(a, c.substring(0, c.length() - 1)) >= 0;
        }
        return false;
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? parseNum(pa[i]) : 0;
            int y = i < pb.length ? parseNum(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parseNum(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
