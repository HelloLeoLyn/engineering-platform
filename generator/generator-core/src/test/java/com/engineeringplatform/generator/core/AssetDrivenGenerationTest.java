package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AssetCompatibility;
import com.engineeringplatform.generator.contracts.AssetDependency;
import com.engineeringplatform.generator.contracts.AssetType;
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
 * Asset-driven generation tests (V02-WORK-004).
 * Real WORK-002 assets + real resolver EPM + existing planner/executor.
 * Generated projects land in @TempDir only.
 */
class AssetDrivenGenerationTest {

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

    @TempDir
    Path tempDir;

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> referenceProject() throws Exception {
        Path fixture = repoRoot().resolve("tests/fixtures/v02-reference/project.yaml");
        String text = Files.readString(fixture, StandardCharsets.UTF_8);
        return (Map<String, Object>) AssetYamlReader.parse(text);
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

    private static EffectiveProjectModel referenceEpm() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, referenceProject());
        if (result.status() != ResolutionResult.Status.SUCCESS) {
            throw new IllegalStateException("reference project must resolve: " + result.errors());
        }
        return result.effectiveProject();
    }

    private static AssetRepository repoWith(EngineeringAsset... extras) throws Exception {
        return AssetRepository.load(repoRoot(), List.of(extras));
    }

    private static EngineeringAsset capability(String id, List<AssetDependency> deps, AssetCompatibility compat) {
        return new EngineeringAsset(id, AssetType.CAPABILITY, "0.1.0", "test asset " + id, deps, compat);
    }

    private static EngineeringAsset provider(String id, List<String> provides, String version) {
        return new EngineeringAsset(id, AssetType.PROVIDER, version, "test provider " + id,
                List.of(), new AssetCompatibility("25", "3.x", provides, List.of()));
    }

    private static Map<String, Object> projectWith(String... capabilities) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("project", Map.of("id", "demo", "name", "demo", "version", "1.0.0",
                "basePackage", "com.engineeringplatform.demo"));
        p.put("platform", Map.of("id", "engineering-platform"));
        p.put("capabilities", java.util.Arrays.stream(capabilities)
                .map(c -> (Object) Map.of("id", c)).toList());
        return p;
    }

    private static EffectiveProjectModel epmOf(AssetRepository repo, Map<String, Object> project) {
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID).resolve(repo, PLATFORM, project);
        if (result.status() != ResolutionResult.Status.SUCCESS) {
            throw new IllegalStateException("expected resolution success: " + result.errors());
        }
        return result.effectiveProject();
    }

    // ---- PASS 1: reference EPM -> GenerationPlan ----

    @Test
    void referenceEpmProducesGenerationPlan() throws Exception {
        EffectiveProjectModel epm = referenceEpm();
        AssetProjectGenerator generator = new AssetProjectGenerator();
        AssetProjectGenerator.GenerationResult result =
                generator.generate(epm, AssetRepository.load(repoRoot()), options(), tempDir);

        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().operations()).isNotEmpty();
        assertThat(result.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
    }

    // ---- PASS 2: asset template included ----

    @Test
    void assetTemplateIncluded() throws Exception {
        AssetProjectGenerator.GenerationResult result = new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);

        assertThat(result.generatedFiles())
                .contains("src/main/java/com/engineeringplatform/demoorderservice/common/error/ApiError.java")
                .contains("src/main/java/com/engineeringplatform/demoorderservice/common/error/GlobalExceptionHandler.java")
                .contains("src/main/resources/logback-spring.xml")
                .contains("src/main/java/com/engineeringplatform/demoorderservice/common/audit/AuditLogAspect.java")
                .contains("src/main/java/com/engineeringplatform/demoorderservice/infrastructure/persistence/MapperScanConfig.java");
    }

    // ---- PASS 3: maven dependencies assembled ----

    @Test
    void mavenDependenciesAssembled() throws Exception {
        AssetProjectGenerator.GenerationResult result = new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);
        String pom = Files.readString(tempDir.resolve("pom.xml"));

        assertThat(pom).contains("org.springframework.boot</groupId>");
        assertThat(pom).contains("spring-boot-starter-web");
        assertThat(pom).contains("spring-boot-starter-validation");
        assertThat(pom).contains("spring-boot-starter-aop");
        assertThat(pom).contains("spring-boot-starter-test");
    }

    // ---- PASS 4: dependency deduplicated ----

    @Test
    void dependencyDeduplicated() throws Exception {
        AssetProjectGenerator.GenerationResult result = new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);
        String pom = Files.readString(tempDir.resolve("pom.xml"));

        int occurrences = pom.split("spring-boot-starter-web", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
    }

    // ---- PASS 5: provider dependency included ----

    @Test
    void providerDependencyIncluded() throws Exception {
        AssetProjectGenerator.GenerationResult result = new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);
        String pom = Files.readString(tempDir.resolve("pom.xml"));

        assertThat(pom).contains("mybatis-plus-spring-boot3-starter");
        assertThat(pom).contains("3.5.17");
    }

    // ---- PASS 6: config generated ----

    @Test
    void configGenerated() throws Exception {
        AssetProjectGenerator.GenerationResult result = new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);
        String yml = Files.readString(tempDir.resolve("src/main/resources/application.yml"));

        assertThat(yml).contains("spring.application.name: demo-order-service");
        assertThat(yml).contains("server.port: 8080");
        assertThat(Files.exists(tempDir.resolve("src/main/resources/application-mybatis.yaml"))).isTrue();
    }

    // ---- PASS 7: java source generated ----

    @Test
    void javaSourceGenerated() throws Exception {
        new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);

        assertThat(Files.exists(tempDir.resolve(
                "src/main/java/com/engineeringplatform/demoorderservice/DemoOrderServiceApplication.java"))).isTrue();
        assertThat(Files.exists(tempDir.resolve(
                "src/main/java/com/engineeringplatform/demoorderservice/common/error/ApiError.java"))).isTrue();
    }

    // ---- PASS 8: test source generated ----

    @Test
    void testSourceGenerated() throws Exception {
        new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);

        assertThat(Files.exists(tempDir.resolve(
                "src/test/java/com/engineeringplatform/demoorderservice/common/error/ApiErrorTest.java"))).isTrue();
    }

    // ---- PASS 9: deterministic plan ----

    @Test
    void deterministicPlan() throws Exception {
        EffectiveProjectModel epm = referenceEpm();
        AssetRepository repo = AssetRepository.load(repoRoot());
        AssetProjectGenerator generator = new AssetProjectGenerator();

        AssetProjectGenerator.GenerationResult r1 = generator.generate(epm, repo, options(), tempDir.resolve("a"));
        AssetProjectGenerator.GenerationResult r2 = generator.generate(epm, repo, options(), tempDir.resolve("b"));

        assertThat(r1.plan().planId()).isEqualTo(r2.plan().planId());
        assertThat(r1.plan().operations()).isEqualTo(r2.plan().operations());
    }

    // ---- PASS 10: deterministic file set ----

    @Test
    void deterministicFileSet() throws Exception {
        EffectiveProjectModel epm = referenceEpm();
        AssetRepository repo = AssetRepository.load(repoRoot());
        AssetProjectGenerator generator = new AssetProjectGenerator();

        Path outA = tempDir.resolve("a");
        Path outB = tempDir.resolve("b");
        Files.createDirectories(outA);
        Files.createDirectories(outB);
        generator.generate(epm, repo, options(), outA);
        generator.generate(epm, repo, options(), outB);

        assertThat(Files.walk(outA).filter(Files::isRegularFile)
                .map(p -> outA.relativize(p).toString())
                .filter(p -> !p.startsWith(".generator"))
                .sorted().toList())
                .isEqualTo(Files.walk(outB).filter(Files::isRegularFile)
                        .map(p -> outB.relativize(p).toString())
                        .filter(p -> !p.startsWith(".generator"))
                        .sorted().toList());
    }

    // ---- PASS 11: generated project mvn test -> BUILD SUCCESS ----

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void generatedProjectMvnTestPasses() throws Exception {
        new AssetProjectGenerator()
                .generate(referenceEpm(), AssetRepository.load(repoRoot()), options(), tempDir);

        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> command = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                tempDir.resolve("pom.xml").toString(), "test"));
        if (Files.exists(settings)) {
            command.add(1, "-s");
            command.add(2, settings.toString());
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(14, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("mvn test must finish within timeout").isTrue();
        assertThat(process.exitValue()).as("generated project mvn test must pass:\n%s", output)
                .isEqualTo(0);
        assertThat(output).contains("BUILD SUCCESS");
    }

    // ---- FAIL 1: missing asset template ----

    @Test
    void missingAssetTemplateFails() throws Exception {
        // simulate by requesting generation against an asset whose files reference a missing template
        Path fakeRoot = tempDir.resolve("repo");
        Files.createDirectories(fakeRoot.resolve("capabilities/broken-tpl"));
        Files.createDirectories(fakeRoot.resolve("providers"));
        Files.writeString(fakeRoot.resolve("capabilities/broken-tpl/asset.yaml"), """
                schemaVersion: 1
                id: broken-tpl
                type: CAPABILITY
                version: 0.1.0
                description: missing template asset
                files:
                  - source: templates/nope.java.ftl
                    target: src/main/java/x/Nope.java
                    ownership: GENERATED
                    mode: render
                """);
        AssetRepository repo = AssetRepository.load(fakeRoot);
        Map<String, Object> project = projectWith("broken-tpl");
        EffectiveProjectModel epm = epmOf(repo, project);

        assertThatThrownBy(() -> new AssetProjectGenerator()
                .generate(epm, repo, options(), tempDir.resolve("out")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("missing asset template");
        assertThat(Files.exists(tempDir.resolve("out"))).isFalse();
    }

    // ---- FAIL 2: invalid template target ----

    @Test
    void invalidTemplateTargetFails() throws Exception {
        Path fakeRoot = tempDir.resolve("repo2");
        Files.createDirectories(fakeRoot.resolve("capabilities/evil-tpl"));
        Files.createDirectories(fakeRoot.resolve("providers"));
        Files.createDirectories(fakeRoot.resolve("capabilities/evil-tpl/templates"));
        Files.writeString(fakeRoot.resolve("capabilities/evil-tpl/asset.yaml"), """
                schemaVersion: 1
                id: evil-tpl
                type: CAPABILITY
                version: 0.1.0
                description: path traversal target
                files:
                  - source: templates/x.ftl
                    target: ../escape.java
                    ownership: GENERATED
                    mode: render
                """);
        Files.writeString(fakeRoot.resolve("capabilities/evil-tpl/templates/x.ftl"), "package x;");
        AssetRepository repo = AssetRepository.load(fakeRoot);
        EffectiveProjectModel epm = epmOf(repo, projectWith("evil-tpl"));

        assertThatThrownBy(() -> new AssetProjectGenerator()
                .generate(epm, repo, options(), tempDir.resolve("out2")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("invalid template target");
    }

    // ---- FAIL 3: dependency version conflict ----

    @Test
    void dependencyVersionConflictFails() throws Exception {
        // two enabled providers declare the same GA with different versions -> conflict
        Path fakeRoot = tempDir.resolve("repo3");
        Files.createDirectories(fakeRoot.resolve("providers/mp-a/tests/fixtures"));
        Files.createDirectories(fakeRoot.resolve("providers/mp-b/tests/fixtures"));
        Files.createDirectories(fakeRoot.resolve("capabilities/cap-a"));
        Files.createDirectories(fakeRoot.resolve("capabilities/cap-b"));
        Files.writeString(fakeRoot.resolve("providers/mp-a/asset.yaml"), """
                schemaVersion: 1
                id: mp-a
                type: PROVIDER
                version: 3.5.9
                description: conflicting provider a
                compatibility:
                  java: "25"
                  springBoot: "3.x"
                  requiredCapabilities: [cap-a]
                """);
        Files.writeString(fakeRoot.resolve("providers/mp-a/tests/fixtures/mp.gav.yaml"), """
                groupId: com.baomidou
                artifactId: mybatis-plus-spring-boot3-starter
                version: 3.5.9
                scope: compile
                """);
        Files.writeString(fakeRoot.resolve("providers/mp-b/asset.yaml"), """
                schemaVersion: 1
                id: mp-b
                type: PROVIDER
                version: 3.5.10
                description: conflicting provider b
                compatibility:
                  java: "25"
                  springBoot: "3.x"
                  requiredCapabilities: [cap-b]
                """);
        Files.writeString(fakeRoot.resolve("providers/mp-b/tests/fixtures/mp.gav.yaml"), """
                groupId: com.baomidou
                artifactId: mybatis-plus-spring-boot3-starter
                version: 3.5.10
                scope: compile
                """);
        Files.writeString(fakeRoot.resolve("capabilities/cap-a/asset.yaml"), """
                schemaVersion: 1
                id: cap-a
                type: CAPABILITY
                version: 0.1.0
                description: bound to mp-a
                compatibility:
                  java: "25"
                  springBoot: "3.x"
                  compatibleProviders: [mp-a]
                """);
        Files.writeString(fakeRoot.resolve("capabilities/cap-b/asset.yaml"), """
                schemaVersion: 1
                id: cap-b
                type: CAPABILITY
                version: 0.1.0
                description: bound to mp-b
                compatibility:
                  java: "25"
                  springBoot: "3.x"
                  compatibleProviders: [mp-b]
                """);
        AssetRepository dupRepo = AssetRepository.load(fakeRoot);
        EffectiveProjectModel epm = epmOf(dupRepo, projectWith("cap-a", "cap-b"));

        assertThatThrownBy(() -> new AssetProjectGenerator()
                .generate(epm, dupRepo, options(), tempDir.resolve("out3")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("dependency version conflict");
    }

    // ---- FAIL 4: missing required configuration ----

    @Test
    void missingRequiredConfigurationFails() throws Exception {
        Path fakeRoot = tempDir.resolve("repo4");
        Files.createDirectories(fakeRoot.resolve("capabilities/needs-config"));
        Files.createDirectories(fakeRoot.resolve("providers"));
        Files.writeString(fakeRoot.resolve("capabilities/needs-config/asset.yaml"), """
                schemaVersion: 1
                id: needs-config
                type: CAPABILITY
                version: 0.1.0
                description: requires a config key
                configuration:
                  - key: app.api.base-url
                    type: string
                    required: true
                    description: required without default
                """);
        AssetRepository repo = AssetRepository.load(fakeRoot);
        EffectiveProjectModel epm = epmOf(repo, projectWith("needs-config"));

        assertThatThrownBy(() -> new AssetProjectGenerator()
                .generate(epm, repo, options(), tempDir.resolve("out4")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("missing required configuration");
    }

    // ---- FAIL 5: ownership denied ----

    @Test
    void ownershipDeniedFails() throws Exception {
        EffectiveProjectModel epm = referenceEpm();
        AssetRepository repo = AssetRepository.load(repoRoot());
        // pre-existing user-owned file blocks generation of the same target
        Path out = tempDir.resolve("out5");
        Files.createDirectories(out.resolve("src/main/resources"));
        Files.writeString(out.resolve("src/main/resources/application.yml"), "user-content");
        Files.createDirectories(out.resolve(".generator"));
        Files.writeString(out.resolve(".generator/generation-manifest.json"),
                "{\"files\": {\"src/main/resources/application.yml\": \"USER_OWNED\"}}");

        AssetProjectGenerator.GenerationResult result =
                new AssetProjectGenerator().generate(epm, repo, options(), out);
        assertThat(result.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
        // no half-generated success: the user file is untouched
        assertThat(Files.readString(out.resolve("src/main/resources/application.yml"))).isEqualTo("user-content");
    }

    // ---- FAIL 6: render variable missing ----

    @Test
    void renderVariableMissingFails() throws Exception {
        Path fakeRoot = tempDir.resolve("repo6");
        Files.createDirectories(fakeRoot.resolve("capabilities/unbound-var"));
        Files.createDirectories(fakeRoot.resolve("providers"));
        Files.createDirectories(fakeRoot.resolve("capabilities/unbound-var/templates"));
        Files.writeString(fakeRoot.resolve("capabilities/unbound-var/asset.yaml"), """
                schemaVersion: 1
                id: unbound-var
                type: CAPABILITY
                version: 0.1.0
                description: template with unbound variable
                files:
                  - source: templates/x.ftl
                    target: src/main/java/x/X.java
                    ownership: GENERATED
                    mode: render
                """);
        Files.writeString(fakeRoot.resolve("capabilities/unbound-var/templates/x.ftl"),
                "package x; // ${noSuchVariable}");
        AssetRepository repo = AssetRepository.load(fakeRoot);
        EffectiveProjectModel epm = epmOf(repo, projectWith("unbound-var"));

        assertThatThrownBy(() -> new AssetProjectGenerator()
                .generate(epm, repo, options(), tempDir.resolve("out6")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("unbound template variable");
    }
}
