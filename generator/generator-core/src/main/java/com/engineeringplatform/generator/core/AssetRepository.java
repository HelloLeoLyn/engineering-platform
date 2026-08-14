package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AssetCompatibility;
import com.engineeringplatform.generator.contracts.AssetDependency;
import com.engineeringplatform.generator.contracts.AssetType;
import com.engineeringplatform.generator.contracts.EngineeringAsset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asset Repository (V02-WORK-003 §2) — minimal, filesystem-backed.
 *
 * Reads asset.yaml from the canonical asset directories:
 *   capabilities/<id>/asset.yaml  -> CAPABILITY assets
 *   providers/<id>/asset.yaml     -> PROVIDER assets
 *
 * Responsibilities only: locate by registry id, parse metadata, expose to the resolver.
 * No remote registry, no cache, no plugin discovery, no database, no marketplace.
 */
public final class AssetRepository {

    private final Map<String, EngineeringAsset> capabilities;
    private final Map<String, EngineeringAsset> providers;

    private AssetRepository(Map<String, EngineeringAsset> capabilities,
                            Map<String, EngineeringAsset> providers) {
        this.capabilities = Map.copyOf(capabilities);
        this.providers = Map.copyOf(providers);
    }

    /** Loads assets from the repository root (capabilities/ + providers/). */
    public static AssetRepository load(Path repoRoot) throws IOException {
        Map<String, EngineeringAsset> caps = loadDir(repoRoot.resolve("capabilities"), AssetType.CAPABILITY);
        Map<String, EngineeringAsset> provs = loadDir(repoRoot.resolve("providers"), AssetType.PROVIDER);
        return new AssetRepository(caps, provs);
    }

    /** Loads real assets plus test-injected extras (fault injection for FAIL scenarios). */
    public static AssetRepository load(Path repoRoot, List<EngineeringAsset> extraAssets) throws IOException {
        AssetRepository real = load(repoRoot);
        Map<String, EngineeringAsset> caps = new LinkedHashMap<>(real.capabilities);
        Map<String, EngineeringAsset> provs = new LinkedHashMap<>(real.providers);
        for (EngineeringAsset asset : extraAssets) {
            if (asset.type() == AssetType.CAPABILITY) {
                caps.put(asset.id(), asset);
            } else if (asset.type() == AssetType.PROVIDER) {
                provs.put(asset.id(), asset);
            }
        }
        return new AssetRepository(caps, provs);
    }

    private static Map<String, EngineeringAsset> loadDir(Path dir, AssetType expectedType) throws IOException {
        Map<String, EngineeringAsset> assets = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return assets;
        }
        try (var stream = Files.list(dir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path sub : dirs) {
                Path assetFile = sub.resolve("asset.yaml");
                if (!Files.exists(assetFile)) {
                    continue;
                }
                String text = Files.readString(assetFile, StandardCharsets.UTF_8);
                EngineeringAsset asset = toAsset(sub.getFileName().toString(), text);
                if (asset.type() != expectedType) {
                    throw new IOException("asset " + sub + " has type " + asset.type()
                            + " but lives in " + dir.getFileName() + "/");
                }
                assets.put(asset.id(), asset);
            }
        }
        return assets;
    }

    @SuppressWarnings("unchecked")
    private static EngineeringAsset toAsset(String dirName, String yamlText) throws IOException {
        try {
            Map<String, Object> raw = (Map<String, Object>) AssetYamlReader.parse(yamlText);
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
            // Provider asset declares the capabilities it provides via compatibility.requiredCapabilities
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
}
