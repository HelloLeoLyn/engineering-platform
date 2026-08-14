package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Activation;
import com.engineeringplatform.generator.contracts.AssetCompatibility;
import com.engineeringplatform.generator.contracts.AssetDependency;
import com.engineeringplatform.generator.contracts.AssetType;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.EngineeringAsset;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolvedProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asset-aware resolution tests (V02-WORK-003).
 * PASS cases use the real WORK-002 assets on disk (capabilities/ + providers/);
 * FAIL cases inject minimal fault assets into the repository (no fake E2E fixture set).
 */
class AssetAwareResolutionTest {

    private static final ManifestValidationPort ALWAYS_VALID = new ManifestValidationPort() {
        @Override public boolean isValid(String manifestType, Map<String, Object> manifest) { return true; }
        @Override public List<String> validationErrors(String manifestType, Map<String, Object> manifest) { return List.of(); }
    };

    private static final Map<String, Object> PLATFORM = Map.of(
            "platform", Map.of("id", "engineering-platform", "version", "0.1.0"),
            "technology", Map.of("java", "25", "springBoot", "3.x"),
            "profiles", Map.of(
                    "presets", Map.of(
                            "standard", Map.of("application", "standard", "infrastructure", "standard",
                                    "security", "standard", "quality", "Q2")),
                    "default", Map.of("application", "standard", "infrastructure", "standard",
                            "security", "standard", "quality", "Q1")));

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6; i++) {
            if (Files.exists(p.resolve("capabilities")) && Files.exists(p.resolve("providers"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                throw new IllegalStateException("cannot locate repository root");
            }
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    /** Reads the real reference project manifest fixture (tests/fixtures/v02-reference/project.yaml). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> referenceProject() throws Exception {
        Path fixture = repoRoot().resolve("tests/fixtures/v02-reference/project.yaml");
        String text = Files.readString(fixture, StandardCharsets.UTF_8);
        return (Map<String, Object>) AssetYamlReader.parse(text);
    }

    private static Map<String, Object> project(String... capabilities) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"));
        p.put("platform", Map.of("id", "engineering-platform"));
        p.put("capabilities", java.util.Arrays.stream(capabilities)
                .map(c -> (Object) Map.of("id", c)).toList());
        return p;
    }

    private static EngineeringAsset capability(String id, List<AssetDependency> deps, AssetCompatibility compat) {
        return new EngineeringAsset(id, AssetType.CAPABILITY, "0.1.0", "test asset " + id, deps, compat);
    }

    private static EngineeringAsset provider(String id, String java, List<String> provides) {
        return new EngineeringAsset(id, AssetType.PROVIDER, "1.0.0", "test provider " + id,
                List.of(), new AssetCompatibility(java, "3.x", provides, List.of()));
    }

    // ---- PASS 1: reference project resolves end-to-end ----

    @Test
    void referenceProjectResolvesToRealEpm() throws Exception {
        Map<String, Object> project = referenceProject();
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = result.effectiveProject();

        // Requested capabilities (explicit)
        assertThat(epm.capabilities())
                .anyMatch(c -> c.id().equals("web") && c.activation() == Activation.EXPLICIT)
                .anyMatch(c -> c.id().equals("validation") && c.activation() == Activation.EXPLICIT)
                .anyMatch(c -> c.id().equals("exception-handling") && c.activation() == Activation.EXPLICIT)
                .anyMatch(c -> c.id().equals("audit") && c.activation() == Activation.EXPLICIT);
        // Dependencies auto-added from assets
        assertThat(epm.capabilities())
                .anyMatch(c -> c.id().equals("persistence") && c.activation() == Activation.REQUIRED
                        && c.requiredBy().contains("audit"))
                .anyMatch(c -> c.id().equals("logging") && c.activation() == Activation.REQUIRED
                        && c.requiredBy().contains("audit"));
        // Provider selected
        assertThat(epm.providers()).anyMatch(p -> p.id().equals("mybatis-plus"));
        // Quality
        assertThat(epm.quality()).containsEntry("minimum", "Q3");
        // Technology
        assertThat(epm.technology()).containsEntry("java", "25");
    }

    // ---- PASS 2: required dependency auto-added with provenance ----

    @Test
    void requiredDependencyAutoAddedWithProvenance() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> project = project("audit");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        List<ResolvedCapability> caps = result.effectiveProject().capabilities();
        ResolvedCapability persistence = caps.stream()
                .filter(c -> c.id().equals("persistence")).findFirst().orElseThrow();
        assertThat(persistence.activation()).isEqualTo(Activation.REQUIRED);
        assertThat(persistence.requiredBy()).containsExactly("audit");
        assertThat(persistence.reason()).contains("Required by asset audit");
    }

    // ---- PASS 3: persistence resolves to mybatis-plus provider ----

    @Test
    void persistenceResolvesToMybatisPlus() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> project = project("persistence");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        ResolvedProvider provider = result.effectiveProject().providers().stream()
                .filter(p -> p.id().equals("mybatis-plus")).findFirst().orElseThrow();
        assertThat(provider.implementsList()).contains("persistence");
        assertThat(provider.reason()).contains("Deterministic selection");
    }

    // ---- PASS 4: deterministic result ----

    @Test
    void deterministicResult() throws Exception {
        Map<String, Object> project = referenceProject();
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult r1 = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);
        ResolutionResult r2 = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(r1.effectiveProject().capabilities())
                .extracting(ResolvedCapability::id)
                .isEqualTo(r2.effectiveProject().capabilities().stream().map(ResolvedCapability::id).toList());
        assertThat(r1.effectiveProject().providers())
                .extracting(ResolvedProvider::id)
                .isEqualTo(r2.effectiveProject().providers().stream().map(ResolvedProvider::id).toList());
    }

    // ---- FAIL 5: missing capability asset ----

    @Test
    void missingCapabilityFails() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> project = project("web", "no-such-capability");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("ASSET_MISSING"));
    }

    // ---- FAIL 6: capability requires provider but none available ----

    @Test
    void missingProviderFails() throws Exception {
        // "sharding" declares a compatible provider that does not exist as an asset
        EngineeringAsset sharding = capability("sharding", List.of(),
                new AssetCompatibility("25", "3.x", List.of(), List.of("shard-proxy")));
        AssetRepository repo = AssetRepository.load(repoRoot(), List.of(sharding));
        Map<String, Object> project = project("sharding");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("PROVIDER_MISSING"));
    }

    // ---- FAIL 7: incompatible provider (java baseline mismatch) ----

    @Test
    void incompatibleProviderFails() throws Exception {
        EngineeringAsset search = capability("search", List.of(),
                new AssetCompatibility("25", "3.x", List.of(), List.of("elastic-provider")));
        EngineeringAsset elastic = provider("elastic-provider", "17", List.of("search"));
        AssetRepository repo = AssetRepository.load(repoRoot(), List.of(search, elastic));
        Map<String, Object> project = project("search");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("COMPATIBILITY_FAILURE"));
    }

    // ---- FAIL 8: dependency cycle ----

    @Test
    void dependencyCycleFails() throws Exception {
        EngineeringAsset cycleA = capability("cycle-a",
                List.of(AssetDependency.required(AssetType.CAPABILITY, "cycle-b")),
                AssetCompatibility.none());
        EngineeringAsset cycleB = capability("cycle-b",
                List.of(AssetDependency.required(AssetType.CAPABILITY, "cycle-a")),
                AssetCompatibility.none());
        AssetRepository repo = AssetRepository.load(repoRoot(), List.of(cycleA, cycleB));
        Map<String, Object> project = project("cycle-a");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("ASSET_DEPENDENCY_CYCLE"));
    }

    // ---- FAIL 9: incompatible java baseline ----

    @Test
    void incompatibleJavaFails() throws Exception {
        EngineeringAsset java17 = capability("java17-only", List.of(),
                new AssetCompatibility("17", "3.x", List.of(), List.of()));
        AssetRepository repo = AssetRepository.load(repoRoot(), List.of(java17));
        Map<String, Object> project = project("java17-only");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("COMPATIBILITY_FAILURE"));
    }

    // ---- optional dependency is not auto-added ----

    @Test
    void optionalDependencyNotAutoAdded() throws Exception {
        EngineeringAsset messaging = capability("messaging", List.of(
                new AssetDependency(AssetType.CAPABILITY, "web", "1.x", false)),
                AssetCompatibility.none());
        AssetRepository repo = AssetRepository.load(repoRoot(), List.of(messaging));
        Map<String, Object> project = project("messaging");
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);

        // optional dep must NOT be auto-added; but "web" is not required by messaging -> fine
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.effectiveProject().capabilities())
                .extracting(ResolvedCapability::id)
                .doesNotContain("web");
    }
}
