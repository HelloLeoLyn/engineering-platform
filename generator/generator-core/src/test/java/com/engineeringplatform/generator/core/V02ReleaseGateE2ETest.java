package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AssetCompatibility;
import com.engineeringplatform.generator.contracts.AssetDependency;
import com.engineeringplatform.generator.contracts.AssetType;
import com.engineeringplatform.generator.contracts.ConformanceResult;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.EngineeringAsset;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V02 Real Project E2E & Release Gate (V02-WORK-006).
 *
 * The happy path runs the FULL real chain starting from the real
 * tests/fixtures/v02-reference/project.yaml and the real platform.yaml
 * (no hand-built EPM / plan / fixture project). Failure paths A-G assert
 * that failed states never masquerade as SUCCESS / PASS.
 */
class V02ReleaseGateE2ETest {

    private static final ManifestValidationPort ALWAYS_VALID = new ManifestValidationPort() {
        @Override public boolean isValid(String manifestType, Map<String, Object> manifest) { return true; }
        @Override public List<String> validationErrors(String manifestType, Map<String, Object> manifest) { return List.of(); }
    };

    @TempDir
    Path tempDir;

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6; i++) {
            if (Files.exists(p.resolve("capabilities")) && Files.exists(p.resolve("providers"))
                    && Files.exists(p.resolve("platform.yaml"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                throw new IllegalStateException("cannot locate repository root");
            }
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> realPlatform() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(repoRoot().resolve("platform.yaml"), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> referenceProject() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v02-reference/project.yaml"), StandardCharsets.UTF_8));
    }

    private static Map<String, Object> projectWith(String... capabilities) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("project", Map.of("id", "demo", "name", "demo", "version", "1.0.0",
                "basePackage", "com.engineeringplatform.demo"));
        p.put("platform", Map.of("id", "engineering-platform"));
        p.put("capabilities", java.util.Arrays.stream(capabilities)
                .map(c -> (Object) Map.of("id", c)).toList());
        p.put("quality", Map.of("minimum", "Q3"));
        return p;
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.engineeringplatform.demoorderservice",
                "demo-order-service",
                "com.engineeringplatform",
                "demo-order-service",
                "0.1.0",
                Map.of());
    }

    private static EngineeringAsset capability(String id, List<AssetDependency> deps, AssetCompatibility compat) {
        return new EngineeringAsset(id, AssetType.CAPABILITY, "0.1.0", "test asset " + id, deps, compat);
    }

    private static EngineeringAsset provider(String id, String java, List<String> provides) {
        return new EngineeringAsset(id, AssetType.PROVIDER, "1.0.0", "test provider " + id,
                List.of(), new AssetCompatibility(java, "3.x", provides, List.of()));
    }

    // ---- 1. Happy Path E2E: full real chain ----

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void happyPathFullRealChain() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> platform = realPlatform();
        Map<String, Object> project = referenceProject();

        // 1. Resolver SUCCESS
        ResolutionResult resolution = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, platform, project);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolution.effectiveProject();

        // 2. EPM capabilities = 7 (web/validation/exception-handling/audit + platform-core
        //    via exception-handling's declared dependency, V06-FINAL)
        assertThat(epm.capabilities()).hasSize(7);

        // 3. persistence provider = mybatis-plus
        assertThat(epm.providers()).anyMatch(p -> p.id().equals("mybatis-plus")
                && p.implementsList().contains("persistence"));

        // 4. quality = Q3
        assertThat(epm.quality()).containsEntry("minimum", "Q3");

        // 5. Generation SUCCESS (via existing planner + executor)
        Path out = tempDir.resolve("generated");
        Files.createDirectories(out);
        AssetProjectGenerator.GenerationResult generated =
                new AssetProjectGenerator().generate(epm, repo, options(), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        // 6. expected files exist
        for (String file : List.of(
                "pom.xml",
                "src/main/resources/application.yml",
                "src/main/resources/application-mybatis.yaml",
                "src/main/resources/logback-spring.xml",
                "src/main/java/com/engineeringplatform/demoorderservice/DemoOrderServiceApplication.java",
                "src/main/java/com/engineeringplatform/demoorderservice/common/error/ApiError.java",
                "src/main/java/com/engineeringplatform/demoorderservice/common/error/GlobalExceptionHandler.java",
                "src/main/java/com/engineeringplatform/demoorderservice/common/audit/AuditLogAspect.java",
                "src/main/java/com/engineeringplatform/demoorderservice/infrastructure/persistence/MapperScanConfig.java",
                "src/test/java/com/engineeringplatform/demoorderservice/common/error/ApiErrorTest.java")) {
            assertThat(Files.exists(out.resolve(file))).as("expected file: %s", file).isTrue();
        }

        // 7. Conformance PASS with 0 ERROR findings
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);
        assertThat(conformance.errors()).isEmpty();

        // 8. generated project mvn test = BUILD SUCCESS (real numbers)
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> command = new java.util.ArrayList<>(
                List.of("mvn", "-B", "-f", out.resolve("pom.xml").toString(), "test"));
        if (Files.exists(settings)) {
            command.add(1, "-s");
            command.add(2, settings.toString());
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(14, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue()).as("generated project mvn test must pass:\n%s", output).isEqualTo(0);
        assertThat(output).contains("BUILD SUCCESS");
        assertThat(output).contains("Tests run: 1");
    }

    // ---- Failure A: missing asset -> Resolution FAIL, never reaches Generation ----

    @Test
    void missingAssetFailsAtResolution() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, realPlatform(), projectWith("web", "no-such-capability"));

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("ASSET_MISSING"));
        assertThat(result.effectiveProject()).isNull(); // no executable model -> no generation
    }

    // ---- Failure B: incompatible provider -> Resolution FAIL ----

    @Test
    void incompatibleProviderFailsAtResolution() throws Exception {
        EngineeringAsset search = capability("search-b", List.of(),
                new AssetCompatibility("25", "3.x", List.of(), List.of("elastic-b")));
        EngineeringAsset elastic = provider("elastic-b", "17", List.of("search-b"));
        AssetRepository repo = AssetRepository.load(repoRoot(), List.of(search, elastic));
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, realPlatform(), projectWith("search-b"));

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("COMPATIBILITY_FAILURE"));
    }

    // ---- Failure C: asset dependency cycle -> Resolution FAIL ----

    @Test
    void dependencyCycleFailsAtResolution() throws Exception {
        EngineeringAsset cycleA = capability("cycle-x",
                List.of(AssetDependency.required(AssetType.CAPABILITY, "cycle-y")), AssetCompatibility.none());
        EngineeringAsset cycleB = capability("cycle-y",
                List.of(AssetDependency.required(AssetType.CAPABILITY, "cycle-x")), AssetCompatibility.none());
        AssetRepository repo = AssetRepository.load(repoRoot(), List.of(cycleA, cycleB));
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, realPlatform(), projectWith("cycle-x"));

        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.code().equals("ASSET_DEPENDENCY_CYCLE"));
    }

    // ---- Failure D: generation safety violation -> FAIL, nothing written ----

    @Test
    void generationSafetyViolationWritesNothing() throws Exception {
        Path fakeRoot = tempDir.resolve("repo-d");
        Files.createDirectories(fakeRoot.resolve("capabilities/evil-d/templates"));
        Files.createDirectories(fakeRoot.resolve("providers"));
        Files.writeString(fakeRoot.resolve("capabilities/evil-d/asset.yaml"), """
                schemaVersion: 1
                id: evil-d
                type: CAPABILITY
                version: 0.1.0
                description: path traversal target
                files:
                  - source: templates/x.ftl
                    target: ../escape.java
                    ownership: GENERATED
                    mode: render
                """);
        Files.writeString(fakeRoot.resolve("capabilities/evil-d/templates/x.ftl"), "package x;");
        AssetRepository repo = AssetRepository.load(fakeRoot);
        ResolutionResult resolution = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, realPlatform(), projectWith("evil-d"));
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);

        Path out = tempDir.resolve("out-d");
        assertThatThrownBy(() -> new AssetProjectGenerator()
                .generate(resolution.effectiveProject(), repo, options(), out))
                .isInstanceOf(GenerationException.class);
        assertThat(Files.exists(out)).isFalse(); // nothing written
    }

    // ---- Failure E: generation conflict (user-owned file) -> FAIL, user content kept ----

    @Test
    void generationConflictKeepsUserContent() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> project = referenceProject();
        ResolutionResult resolution = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), project);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);

        Path out = tempDir.resolve("out-e");
        Files.createDirectories(out.resolve("src/main/resources"));
        Files.createDirectories(out.resolve(".generator"));
        Files.writeString(out.resolve("src/main/resources/application.yml"), "user-content");
        Files.writeString(out.resolve(".generator/generation-manifest.json"),
                "{\"files\": {\"src/main/resources/application.yml\": \"USER_OWNED\"}}");

        AssetProjectGenerator.GenerationResult result = new AssetProjectGenerator()
                .generate(resolution.effectiveProject(), repo, options(), out);
        assertThat(result.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
        assertThat(Files.readString(out.resolve("src/main/resources/application.yml"))).isEqualTo("user-content");
    }

    // ---- Failure F: broken generated project -> Conformance FAIL with finding ----

    @Test
    void brokenGeneratedProjectFailsConformance() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, realPlatform(), referenceProject());
        Path out = tempDir.resolve("out-f");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(resolution.effectiveProject(), repo, options(), out);
        // break the project: delete a required asset file
        Files.delete(out.resolve(
                "src/main/java/com/engineeringplatform/demoorderservice/common/error/GlobalExceptionHandler.java"));

        ConformanceResult conformance = new ConformanceValidator(repo)
                .validate(resolution.effectiveProject(), out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(conformance.errors()).anyMatch(f -> f.ruleId().equals("asset.required-file"));
    }

    // ---- Failure G: build failure must never look like release-ready ----

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void buildFailureIsNotReleaseReady() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, realPlatform(), referenceProject());
        Path out = tempDir.resolve("out-g");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(resolution.effectiveProject(), repo, options(), out);
        // break compilation: delete ApiError (referenced by ApiErrorTest) and a conformance-required asset file
        Files.delete(out.resolve(
                "src/main/java/com/engineeringplatform/demoorderservice/common/error/ApiError.java"));

        // Conformance catches it too (exception-handling asset requires ApiError.java)
        ConformanceResult conformance = new ConformanceValidator(repo).validate(resolution.effectiveProject(), out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.FAIL);

        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> command = new java.util.ArrayList<>(
                List.of("mvn", "-B", "-f", out.resolve("pom.xml").toString(), "test"));
        if (Files.exists(settings)) {
            command.add(1, "-s");
            command.add(2, settings.toString());
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(14, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).as("broken project mvn test must NOT pass:\n%s", output)
                .isNotEqualTo(0);
        // no false success: FAILED build must not become PASS/RELEASE_READY
        assertThat(output).doesNotContain("BUILD SUCCESS");
        assertThat(conformance.status()).isNotEqualTo(ConformanceResult.Status.PASS);
    }
}
