package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AssetCompatibility;
import com.engineeringplatform.generator.contracts.AssetDependency;
import com.engineeringplatform.generator.contracts.AssetType;
import com.engineeringplatform.generator.contracts.EngineeringAsset;
import com.engineeringplatform.generator.contracts.Ownership;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asset Repository (V02-WORK-003 §2 + V02-WORK-004 §4) — minimal, filesystem-backed.
 *
 * Reads asset.yaml from the canonical asset directories:
 *   capabilities/<id>/asset.yaml  -> CAPABILITY assets
 *   providers/<id>/asset.yaml     -> PROVIDER assets
 *
 * V02-WORK-004 additions:
 *   - tracks asset source dirs to read templates/files from disk
 *   - exposes files/configuration metadata (raw contract) and provider GAV fixtures
 *
 * No remote registry, no cache system, no plugin discovery, no database, no marketplace.
 */
public final class AssetRepository {

    /** Asset file specification from asset.yaml files[] (V02-WORK-004 §8). */
    public record AssetFileSpec(String source, String target, Ownership ownership, boolean render) {
    }

    /** Asset configuration input from asset.yaml configuration[] (V02-WORK-004 §6/§14). */
    public record ConfigSpec(String key, String type, boolean required, Object defaultValue) {
    }

    /** Maven dependency assembled from provider GAV fixtures (Provider ≠ Maven Dependency). */
    public record MavenDependency(String groupId, String artifactId, String version, String scope) {
        public String ga() {
            return groupId + ":" + artifactId;
        }
    }

    private final Map<String, EngineeringAsset> capabilities;
    private final Map<String, EngineeringAsset> providers;
    private final Map<String, Path> capabilityDirs;
    private final Map<String, Path> providerDirs;
    private final Map<String, Map<String, Object>> rawByAsset;

    private AssetRepository(Map<String, EngineeringAsset> capabilities,
                            Map<String, EngineeringAsset> providers,
                            Map<String, Path> capabilityDirs,
                            Map<String, Path> providerDirs,
                            Map<String, Map<String, Object>> rawByAsset) {
        this.capabilities = Map.copyOf(capabilities);
        this.providers = Map.copyOf(providers);
        this.capabilityDirs = Map.copyOf(capabilityDirs);
        this.providerDirs = Map.copyOf(providerDirs);
        this.rawByAsset = Map.copyOf(rawByAsset);
    }

    /** Loads assets from the repository root (capabilities/ + providers/). */
    public static AssetRepository load(Path repoRoot) throws IOException {
        return load(repoRoot, List.of());
    }

    /** Loads real assets plus test-injected extras (fault injection for FAIL scenarios). */
    public static AssetRepository load(Path repoRoot, List<EngineeringAsset> extraAssets) throws IOException {
        Map<String, EngineeringAsset> caps = new LinkedHashMap<>();
        Map<String, EngineeringAsset> provs = new LinkedHashMap<>();
        Map<String, Path> capDirs = new LinkedHashMap<>();
        Map<String, Path> provDirs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        loadDir(repoRoot.resolve("capabilities"), AssetType.CAPABILITY, caps, capDirs, raw);
        loadDir(repoRoot.resolve("providers"), AssetType.PROVIDER, provs, provDirs, raw);
        for (EngineeringAsset asset : extraAssets) {
            if (asset.type() == AssetType.CAPABILITY) {
                caps.put(asset.id(), asset);
            } else if (asset.type() == AssetType.PROVIDER) {
                provs.put(asset.id(), asset);
            }
        }
        return new AssetRepository(caps, provs, capDirs, provDirs, raw);
    }

    @SuppressWarnings("unchecked")
    private static void loadDir(Path dir, AssetType expectedType,
                                Map<String, EngineeringAsset> assets, Map<String, Path> dirs,
                                Map<String, Map<String, Object>> rawByAsset) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> subDirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path sub : subDirs) {
                Path assetFile = sub.resolve("asset.yaml");
                if (!Files.exists(assetFile)) {
                    continue;
                }
                String text = Files.readString(assetFile, StandardCharsets.UTF_8);
                Map<String, Object> raw = (Map<String, Object>) AssetYamlReader.parse(text);
                EngineeringAsset asset = toAsset(sub.getFileName().toString(), raw);
                if (asset.type() != expectedType) {
                    throw new IOException("asset " + sub + " has type " + asset.type()
                            + " but lives in " + dir.getFileName() + "/");
                }
                assets.put(asset.id(), asset);
                dirs.put(asset.id(), sub);
                rawByAsset.put(asset.id(), raw);
            }
        }
    }

    private static EngineeringAsset toAsset(String dirName, Map<String, Object> raw) throws IOException {
        try {
            String id = str(raw.get("id"));
            if (id == null || !id.equals(dirName)) {
                throw new IOException("asset id '" + id + "' does not match directory '" + dirName + "'");
            }
            AssetType type = AssetType.valueOf(str(raw.get("type")));
            String version = str(raw.get("version"));
            String description = str(raw.get("description"));
            List<AssetDependency> dependencies = parseDependencies(raw.get("dependencies"));
            AssetCompatibility compatibility = parseCompatibility(raw.get("compatibility"));
            return new EngineeringAsset(id, type, version, description, dependencies, compatibility);
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new IOException("cannot parse asset.yaml in " + dirName + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AssetDependency> parseDependencies(Object value) {
        List<AssetDependency> deps = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return deps;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String typeRaw = str(m.get("type"));
            String id = str(m.get("id"));
            String version = str(m.get("version"));
            boolean required = m.get("required") == null || Boolean.TRUE.equals(m.get("required"));
            if (typeRaw != null && id != null) {
                deps.add(new AssetDependency(AssetType.valueOf(typeRaw), id, version, required));
            }
        }
        return deps;
    }

    @SuppressWarnings("unchecked")
    private static AssetCompatibility parseCompatibility(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return AssetCompatibility.none();
        }
        List<String> reqCaps = strList(m.get("requiredCapabilities"));
        List<String> compatProviders = strList(m.get("compatibleProviders"));
        return new AssetCompatibility(str(m.get("java")), str(m.get("springBoot")), reqCaps, compatProviders);
    }

    private static List<String> strList(Object value) {
        List<String> ids = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    ids.add(s);
                }
            }
        }
        return ids;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---- accessors ----

    public EngineeringAsset capability(String id) {
        return capabilities.get(id);
    }

    public EngineeringAsset provider(String id) {
        return providers.get(id);
    }

    public Map<String, EngineeringAsset> capabilities() {
        return capabilities;
    }

    public Map<String, EngineeringAsset> providers() {
        return providers;
    }

    /** Provider manifests in the shape the existing ProviderResolver consumes. */
    public Map<String, Map<String, Object>> toProviderManifests() {
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
        for (EngineeringAsset asset : providers.values()) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("implements", new ArrayList<>(asset.compatibility().requiredCapabilities()));
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("id", asset.id());
            provider.put("name", asset.id());
            provider.put("version", asset.version());
            manifest.put("provider", provider);
            manifest.put("compatibility", Map.of("platformVersion", "0.1.x"));
            manifests.put(asset.id(), manifest);
        }
        return manifests;
    }

    // ---- V02-WORK-004: asset content consumption ----

    /** files[] metadata of a capability/provider asset (empty when none declared). */
    public List<AssetFileSpec> assetFiles(String assetId) {
        Map<String, Object> raw = rawByAsset.get(assetId);
        List<AssetFileSpec> specs = new ArrayList<>();
        if (raw == null || !(raw.get("files") instanceof List<?> list)) {
            return specs;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String source = str(m.get("source"));
            String target = str(m.get("target"));
            if (source == null || target == null) {
                continue;
            }
            Ownership ownership = parseOwnership(str(m.get("ownership")));
            boolean render = "render".equals(str(m.get("mode")));
            specs.add(new AssetFileSpec(source, target, ownership, render));
        }
        return specs;
    }

    /** configuration[] metadata of an asset (empty when none declared). */
    public List<ConfigSpec> assetConfiguration(String assetId) {
        Map<String, Object> raw = rawByAsset.get(assetId);
        List<ConfigSpec> specs = new ArrayList<>();
        if (raw == null || !(raw.get("configuration") instanceof List<?> list)) {
            return specs;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String key = str(m.get("key"));
            String type = str(m.get("type"));
            if (key == null || type == null) {
                continue;
            }
            boolean required = Boolean.TRUE.equals(m.get("required"));
            specs.add(new ConfigSpec(key, type, required, m.get("default")));
        }
        return specs;
    }

    /** Reads an asset template/file from its source dir. Path safety enforced (no escape, no abs). */
    public String readAssetFile(String assetId, String relativePath) throws IOException {
        Path base = capabilityDirs.containsKey(assetId) ? capabilityDirs.get(assetId)
                : providerDirs.get(assetId);
        if (base == null) {
            throw new IOException("unknown asset: " + assetId);
        }
        Path file = base.resolve(relativePath).normalize();
        if (!file.startsWith(base)) {
            throw new IOException("path escape rejected for asset " + assetId + ": " + relativePath);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("asset file not found: " + assetId + "/" + relativePath);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Maven dependency metadata from the provider's PRODUCTION fact source (dependencies.yaml).
     *  V02-WORK-006 §6: test fixtures (tests/fixtures/*.gav.yaml) are never a production source. */
    public List<MavenDependency> gavFixtures(String providerId) throws IOException {
        Path base = providerDirs.get(providerId);
        List<MavenDependency> deps = new ArrayList<>();
        if (base == null) {
            return deps;
        }
        Path dependenciesFile = base.resolve("dependencies.yaml");
        if (!Files.isRegularFile(dependenciesFile)) {
            return deps;
        }
        Map<String, Object> raw = (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(dependenciesFile, StandardCharsets.UTF_8));
        Object list = raw.get("dependencies");
        if (!(list instanceof List<?> items)) {
            return deps;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String groupId = str(m.get("groupId"));
            String artifactId = str(m.get("artifactId"));
            if (groupId == null || artifactId == null) {
                continue;
            }
            String version = str(m.get("version"));
            String scope = str(m.get("scope"));
            deps.add(new MavenDependency(groupId, artifactId, version,
                    scope == null ? "compile" : scope));
        }
        return deps;
    }

    /** Raw parsed asset.yaml (package-private bridge for assembly logic). */
    Map<String, Object> rawAsset(String assetId) {
        return rawByAsset.get(assetId);
    }

    private static Ownership parseOwnership(String raw) {
        if (raw == null) {
            return Ownership.GENERATED;
        }
        try {
            return Ownership.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return Ownership.GENERATED;
        }
    }
}
