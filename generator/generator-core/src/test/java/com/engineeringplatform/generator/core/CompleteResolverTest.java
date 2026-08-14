package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionReport;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complete Resolver tests — Steps 7-13 + final artifacts (EP-WORK-004C+D).
 * All fixtures are in-memory; real registry files are never touched.
 */
class CompleteResolverTest {

    private static final ManifestValidationPort ALWAYS_VALID = new ManifestValidationPort() {
        @Override public boolean isValid(String manifestType, Map<String, Object> manifest) { return true; }
        @Override public List<String> validationErrors(String manifestType, Map<String, Object> manifest) { return List.of(); }
    };

    private static final Map<String, Object> PLATFORM = Map.of(
            "platform", Map.of("id", "engineering-platform", "version", "0.1.0"),
            "technology", Map.of("java", "25", "node", "24"),
            "profiles", Map.of(
                    "presets", Map.of(
                            "lite", Map.of("application", "lite", "infrastructure", "lite",
                                    "security", "standard", "quality", "Q1"),
                            "standard", Map.of("application", "standard", "infrastructure", "standard",
                                    "security", "standard", "quality", "Q2")),
                    "default", Map.of("application", "lite", "infrastructure", "lite",
                            "security", "standard", "quality", "Q1")),
            "registries", Map.of(
                    "capability", Map.of("path", "registry/capabilities.yaml"),
                    "provider", Map.of("path", "registry/providers.yaml"),
                    "module", Map.of("path", "registry/modules.yaml")));

    private static Map<String, Object> project(String profile, List<?> modules, List<?> caps, List<?> providers) {
        Map<String, Object> p = new java.util.HashMap<>(Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "modules", modules == null ? List.of() : modules));
        if (profile != null) {
            p.put("profiles", Map.of("default", profile));
        }
        if (caps != null) {
            p.put("capabilities", caps);
        }
        if (providers != null) {
            p.put("providers", providers);
        }
        return p;
    }

    private static Map<String, Object> moduleManifest(String id, List<String> required, List<String> optional,
                                                      List<String> requiredCaps, List<String> optionalCaps) {
        Map<String, Object> deps = new java.util.HashMap<>();
        if (required != null && !required.isEmpty()) deps.put("requiredModules", required);
        if (optional != null && !optional.isEmpty()) deps.put("optionalModules", optional);
        if (requiredCaps != null && !requiredCaps.isEmpty()) deps.put("requiredCapabilities", requiredCaps);
        if (optionalCaps != null && !optionalCaps.isEmpty()) deps.put("optionalCapabilities", optionalCaps);
        Map<String, Object> manifest = new java.util.HashMap<>();
        manifest.put("module", Map.of("id", id, "name", id, "version", "0.1.0"));
        manifest.put("compatibility", Map.of("platformVersion", "0.1.x"));
        if (!deps.isEmpty()) {
            manifest.put("dependencies", deps);
        }
        return manifest;
    }

    private static Map<String, Object> providerManifest(String id, List<String> implementsList) {
        return Map.of(
                "provider", Map.of("id", id, "name", id, "version", "3.5.17"),
                "implements", implementsList,
                "compatibility", Map.of("platformVersion", "0.1.x"));
    }

    private static ResolverInput input(Map<String, Object> project,
                                       Map<String, Map<String, Object>> modules,
                                       Map<String, Map<String, Object>> providers,
                                       Map<String, Set<String>> registry) {
        return new ResolverInput(PLATFORM, project, modules, providers, registry);
    }

    private static CompleteResolver resolver() {
        return new CompleteResolver(ALWAYS_VALID);
    }

    // ---- 15. required dependency added ----

    @Test
    void requiredDependencyAdded() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", List.of("lib"), null, null, null),
                "lib", moduleManifest("lib", null, null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app", "lib")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.effectiveProject().modules())
                .anyMatch(m -> m.id().equals("lib") && m.activation().code().equals("required"));
    }

    // ---- 16. transitive dependency ----

    @Test
    void transitiveDependencyAdded() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", List.of("lib-a"), null, null, null),
                "lib-a", moduleManifest("lib-a", List.of("lib-b"), null, null, null),
                "lib-b", moduleManifest("lib-b", null, null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app", "lib-a", "lib-b")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().modules())
                .anyMatch(m -> m.id().equals("lib-a") && m.activation().code().equals("required"));
        assertThat(result.effectiveProject().modules())
                .anyMatch(m -> m.id().equals("lib-b") && m.activation().code().equals("required"));
    }

    // ---- 17. optional dependency NOT auto-enabled ----

    @Test
    void optionalDependencyNotAutoEnabled() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, List.of("lib-opt"), null, null),
                "lib-opt", moduleManifest("lib-opt", null, null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().modules())
                .noneMatch(m -> m.id().equals("lib-opt"));
    }

    // ---- 18. optional dependency explicitly triggered ----

    @Test
    void optionalDependencyExplicitlyTriggered() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, List.of("lib-opt"), null, null),
                "lib-opt", moduleManifest("lib-opt", null, null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app"), Map.of("id", "lib-opt")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app", "lib-opt")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().modules())
                .anyMatch(m -> m.id().equals("lib-opt") && m.activation().code().equals("optional-triggered"));
    }

    // ---- 19. dependency cycle ----

    @Test
    void dependencyCycleDetected() {
        Map<String, Map<String, Object>> modules = Map.of(
                "a", moduleManifest("a", List.of("b"), null, null, null),
                "b", moduleManifest("b", List.of("a"), null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "a")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("a", "b")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("DEPENDENCY_CYCLE"));
    }

    // ---- 20. dependency conflict ----

    @Test
    void dependencyConflictDetected() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", List.of("lib"), null, null, null),
                "lib", moduleManifest("lib", null, null, null, null));
        ResolverInput in = input(project(null, List.of(
                        Map.of("id", "app"), Map.of("id", "lib", "enabled", false)), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app", "lib")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("DEPENDENCY_CONFLICT"));
    }

    // ---- 21. capability resolved ----

    @Test
    void capabilityResolved() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, List.of("persistence"), null));
        // capability 解析成功路径需要完整可解析输入：实现该 capability 的 provider 必须存在
        // （与 providerMissing 测试的 PROVIDER_MISSING 语义一致，见 ProviderResolver: No candidate -> PROVIDER_MISSING）
        Map<String, Map<String, Object>> providers = Map.of(
                "jdbc-provider", providerManifest("jdbc-provider", List.of("persistence")));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, providers, Map.of(
                        "modules", Set.of("app"),
                        "capabilities", Set.of("persistence")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().capabilities())
                .anyMatch(c -> c.id().equals("persistence") && c.activation().code().equals("required"));
    }

    // ---- 22. capability missing ----

    @Test
    void capabilityMissing() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, List.of("persistence"), null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("CAPABILITY_MISSING"));
    }

    // ---- 22b. UNKNOWN_REFERENCE (base registry reference missing) vs CAPABILITY_MISSING ----

    @Test
    void unknownReferenceDistinctFromCapabilityMissing() {
        // project explicitly references module "ghost" that does not exist anywhere:
        // base reference missing -> UNKNOWN_REFERENCE (Step 2 Reference Resolution)
        ResolverInput in = input(project(null, List.of(Map.of("id", "ghost")), null, null),
                Map.of(), Map.of(), Map.of("modules", Set.of()));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("UNKNOWN_REFERENCE"));
        // module exists, but its required capability is not registered -> CAPABILITY_MISSING
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, List.of("persistence"), null));
        ResolverInput in2 = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app")));
        ResolutionResult result2 = resolver().resolve(in2);
        assertThat(result2.errors()).anyMatch(e -> e.code().equals("CAPABILITY_MISSING"));
        assertThat(result2.errors()).noneMatch(e -> e.code().equals("UNKNOWN_REFERENCE"));
    }

    // ---- 23. provider explicit preference ----

    @Test
    void providerExplicitPreferenceWins() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, List.of("persistence"), null));
        Map<String, Map<String, Object>> providers = Map.of(
                "jdbc-provider", providerManifest("jdbc-provider", List.of("persistence")),
                "mybatis-plus", providerManifest("mybatis-plus", List.of("persistence")));
        Map<String, Object> project = project(null, List.of(Map.of("id", "app")),
                List.of(Map.of("id", "persistence", "provider", "jdbc-provider")), null);
        ResolverInput in = input(project, modules, providers, Map.of(
                "modules", Set.of("app"),
                "capabilities", Set.of("persistence")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().providers())
                .anyMatch(p -> p.id().equals("jdbc-provider"));
    }

    // ---- 24. provider deterministic selection (declaration order) ----

    @Test
    void providerDeterministicSelection() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, List.of("persistence"), null));
        // 稳定声明顺序：用 LinkedHashMap 保证 jdbc-provider 先声明（Map.of 迭代顺序未定义，
        // 无法表达“声明顺序”；ProviderResolver 规则 = explicit preference 优先，否则 stable declaration order）
        Map<String, Map<String, Object>> providers = new java.util.LinkedHashMap<>();
        providers.put("jdbc-provider", providerManifest("jdbc-provider", List.of("persistence")));
        providers.put("mybatis-plus", providerManifest("mybatis-plus", List.of("persistence")));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, providers, Map.of(
                        "modules", Set.of("app"),
                        "capabilities", Set.of("persistence")));
        ResolutionResult result = resolver().resolve(in);
        // LinkedHashMap iteration order: jdbc-provider first -> deterministic selection
        assertThat(result.effectiveProject().providers()).isNotEmpty();
        assertThat(result.effectiveProject().providers().get(0).id()).isEqualTo("jdbc-provider");
    }

    // ---- 25. provider missing ----

    @Test
    void providerMissing() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, List.of("persistence"), null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of(
                        "modules", Set.of("app"),
                        "capabilities", Set.of("persistence")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("PROVIDER_MISSING"));
    }

    // ---- 26. compatibility success ----

    @Test
    void compatibilitySuccess() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.report().compatibility())
                .anyMatch(f -> f.compatible());
    }

    // ---- 27. compatibility failure ----

    @Test
    void compatibilityFailure() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", null, null, null, null));
        modules.get("app").put("compatibility", Map.of("platformVersion", "0.2.x"));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("COMPATIBILITY_FAILURE"));
    }

    // ---- 28. security violation ----

    @Test
    void securityViolation() {
        Map<String, Object> platform = new java.util.HashMap<>(PLATFORM);
        platform.put("governance", Map.of("securityGate", Map.of("required", true)));
        Map<String, Object> project = project(null, List.of(), null, null);
        ResolverInput in = new ResolverInput(platform, project, Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("SECURITY_VIOLATION"));
    }

    // ---- 29. quality resolution ----

    @Test
    void qualityResolution() {
        Map<String, Object> project = project("standard", List.of(), null, null);
        ResolverInput in = input(project, Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.effectiveProject().quality()).containsEntry("minimum", "Q2");
    }

    // ---- 30. quality escalation ----

    @Test
    void qualityEscalation() {
        Map<String, Object> project = new java.util.HashMap<>(project("standard", List.of(), null, null));
        project.put("quality", Map.of("minimum", "Q3"));
        ResolverInput in = input(project, Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.report().qualityEscalations())
                .anyMatch(e -> e.from().equals("Q2") && e.to().equals("Q3"));
    }

    // ---- 31. unknown quality ----

    @Test
    void unknownQuality() {
        Map<String, Object> project = new java.util.HashMap<>(project(null, List.of(), null, null));
        project.put("quality", Map.of("minimum", "Q9"));
        ResolverInput in = input(project, Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("UNKNOWN_QUALITY"));
    }

    // ---- 32. environment resolution ----

    @Test
    void environmentResolution() {
        Map<String, Object> project = new java.util.HashMap<>(project(null, List.of(), null, null));
        project.put("environments", List.of("dev", "test"));
        ResolverInput in = input(project, Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().environments())
                .extracting(e -> e.get("name")).containsExactly("dev", "test");
    }

    // ---- 33. unknown environment ----

    @Test
    void unknownEnvironment() {
        Map<String, Object> project = new java.util.HashMap<>(project(null, List.of(), null, null));
        project.put("environments", List.of("mars"));
        ResolverInput in = input(project, Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("UNKNOWN_ENVIRONMENT"));
    }

    // ---- 34. successful final EPM ----

    @Test
    void successfulFinalEpm() {
        ResolverInput in = input(project(null, List.of(), null, null), Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.effectiveProject()).isNotNull();
        assertThat(result.effectiveProject().schemaVersion()).isEqualTo(EffectiveProjectModel.SCHEMA_VERSION);
        assertThat(result.effectiveProject().resolution().resolutionId()).startsWith("res-");
        assertThat(result.effectiveProject().resolution().inputHash()).isNotBlank();
    }

    // ---- 35. failed resolution has no executable EPM ----

    @Test
    void failedResolutionHasNoEpm() {
        Map<String, Map<String, Object>> modules = Map.of(
                "a", moduleManifest("a", List.of("b"), null, null, null),
                "b", moduleManifest("b", List.of("a"), null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "a")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("a", "b")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.effectiveProject()).isNull();
        assertThat(result.report()).isNotNull();
    }

    // ---- 36. ResolutionReport correctness ----

    @Test
    void resolutionReportCorrectness() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", List.of("lib"), null, List.of("persistence"), null),
                "lib", moduleManifest("lib", null, null, null, null));
        Map<String, Map<String, Object>> providers = Map.of(
                "mybatis-plus", providerManifest("mybatis-plus", List.of("persistence")));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, providers, Map.of(
                        "modules", Set.of("app", "lib"),
                        "capabilities", Set.of("persistence")));
        ResolutionResult result = resolver().resolve(in);
        ResolutionReport report = result.report();
        assertThat(report.resolutionId()).isEqualTo(result.effectiveProject().resolution().resolutionId());
        assertThat(report.dependenciesAdded())
                .anyMatch(d -> d.dependencyId().equals("lib"));
        assertThat(report.providersSelected())
                .anyMatch(p -> p.providerId().equals("mybatis-plus"));
        assertThat(report.defaultsApplied()).isNotEmpty();
    }

    // ---- 37. activation correctness ----

    @Test
    void activationCorrectness() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", List.of("lib"), List.of("opt"), null, null),
                "lib", moduleManifest("lib", null, null, null, null),
                "opt", moduleManifest("opt", null, null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app"), Map.of("id", "opt")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app", "lib", "opt")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().modules())
                .anyMatch(m -> m.id().equals("app") && m.activation().code().equals("explicit"));
        assertThat(result.effectiveProject().modules())
                .anyMatch(m -> m.id().equals("lib") && m.activation().code().equals("required"));
        assertThat(result.effectiveProject().modules())
                .anyMatch(m -> m.id().equals("opt") && m.activation().code().equals("optional-triggered"));
    }

    // ---- 38. requiredBy correctness ----

    @Test
    void requiredByCorrectness() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", moduleManifest("app", List.of("lib"), null, null, null),
                "lib", moduleManifest("lib", null, null, null, null));
        ResolverInput in = input(project(null, List.of(Map.of("id", "app")), null, null),
                modules, Map.of(), Map.of("modules", Set.of("app", "lib")));
        ResolutionResult result = resolver().resolve(in);
        assertThat(result.effectiveProject().modules())
                .filteredOn(m -> m.id().equals("lib"))
                .anyMatch(m -> m.requiredBy().contains("app"));
    }

    // ---- 39. provenance correctness ----

    @Test
    void provenanceCorrectness() {
        Map<String, Object> project = project("standard", List.of(), null, null);
        project.put("technology", Map.of("java", "21"));
        ResolverInput in = input(project, Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        var provenance = result.effectiveProject().provenance();
        assertThat(provenance.get("technology.java").source().name()).isEqualTo("PROJECT");
        assertThat(provenance.get("profiles.quality").source().name()).isEqualTo("PROFILE_DEFAULT");
        assertThat(provenance.get("technology.node").source().name()).isEqualTo("PLATFORM_DEFAULT");
    }

    // ---- 40. deterministic inputHash ----

    @Test
    void deterministicInputHash() {
        ResolverInput in = input(project(null, List.of(), null, null), Map.of(), Map.of(), Map.of());
        String h1 = resolver().resolve(in).effectiveProject().resolution().inputHash();
        String h2 = resolver().resolve(in).effectiveProject().resolution().inputHash();
        assertThat(h1).isEqualTo(h2);
    }

    // ---- 41. map-order independent hash ----

    @Test
    void mapOrderIndependentHash() {
        ResolverInput a = input(project(null, List.of(), null, null), Map.of(), Map.of(), Map.of());
        Map<String, Object> projectB = new java.util.LinkedHashMap<>();
        projectB.put("modules", List.of());
        projectB.put("platform", Map.of("id", "engineering-platform"));
        projectB.put("project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"));
        ResolverInput b = new ResolverInput(PLATFORM, projectB, Map.of(), Map.of(), Map.of());
        String ha = resolver().resolve(a).effectiveProject().resolution().inputHash();
        String hb = resolver().resolve(b).effectiveProject().resolution().inputHash();
        assertThat(ha).isEqualTo(hb);
    }

    // ---- 41b. Snapshot A: same input + same resolverVersion -> same resolutionId ----

    @Test
    void sameInputSameVersionSameResolutionId() {
        ResolverInput in = input(project(null, List.of(), null, null), Map.of(), Map.of(), Map.of());
        String id1 = resolver().resolve(in).effectiveProject().resolution().resolutionId();
        String id2 = resolver().resolve(in).effectiveProject().resolution().resolutionId();
        assertThat(id1).isEqualTo(id2);
        assertThat(id1).startsWith("res-");
    }

    // ---- 41c. Snapshot B: same input + different resolverVersion -> different resolutionId ----

    @Test
    void differentResolverVersionDifferentResolutionId() {
        ResolverInput in = input(project(null, List.of(), null, null), Map.of(), Map.of(), Map.of());
        CompleteResolver r1 = new CompleteResolver(ALWAYS_VALID, SnapshotFactory.RESOLVER_VERSION);
        CompleteResolver r2 = new CompleteResolver(ALWAYS_VALID, "0.2.0");
        String id1 = r1.resolve(in).effectiveProject().resolution().resolutionId();
        String id2 = r2.resolve(in).effectiveProject().resolution().resolutionId();
        assertThat(id1).isNotEqualTo(id2);
        // inputHash stays the same across resolver versions
        assertThat(r1.resolve(in).effectiveProject().resolution().inputHash())
                .isEqualTo(r2.resolve(in).effectiveProject().resolution().inputHash());
    }

    // Snapshot C (same input -> same inputHash) covered by test 40;
    // Snapshot D (map key order change -> same inputHash) covered by test 41.

    // ---- 42. deterministic resolution output ----

    @Test
    void deterministicOutput() {
        ResolverInput in = input(project(null, List.of(), null, null), Map.of(), Map.of(), Map.of());
        EffectiveProjectModel m1 = resolver().resolve(in).effectiveProject();
        EffectiveProjectModel m2 = resolver().resolve(in).effectiveProject();
        assertThat(m1).isEqualTo(m2);
    }

    // ---- 43. source input unchanged ----

    @Test
    void sourceInputUnchanged() {
        Map<String, Object> platform = new java.util.HashMap<>(PLATFORM);
        Map<String, Object> project = new java.util.HashMap<>(project(null, List.of(), null, null));
        Map<String, Object> platformBefore = new java.util.HashMap<>(platform);
        Map<String, Object> projectBefore = new java.util.HashMap<>(project);
        resolver().resolve(new ResolverInput(platform, project, Map.of(), Map.of(), Map.of()));
        assertThat(platform).isEqualTo(platformBefore);
        assertThat(project).isEqualTo(projectBefore);
    }

    // ---- 44. summary rendering ----

    @Test
    void summaryRendering() {
        ResolverInput in = input(project(null, List.of(), null, null), Map.of(), Map.of(), Map.of());
        ResolutionResult result = resolver().resolve(in);
        String summary = result.summary();
        assertThat(summary).contains("# Effective Project Summary");
        assertThat(summary).contains("resolutionId: res-");
        assertThat(summary).contains("## Modules");
        assertThat(summary).contains("## Capabilities");
    }
}
