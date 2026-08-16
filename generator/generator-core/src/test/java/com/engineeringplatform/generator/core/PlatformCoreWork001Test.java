package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ConformanceResult;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V04-WORK-001 Platform Core — asset-driven enterprise core contracts.
 *
 * Covers the 20 acceptance test points of the V04-WORK-001 spec:
 *   1  asset validation
 *   2  registry consistency
 *   3  resolver adds platform-core
 *   4  EPM contains platform-core
 *   5  generator outputs core files
 *   6-13 core contract behavior (executed inside the generated project by
 *        PlatformCoreConsumptionTest during the real mvn test in #14/#15)
 *   14 generated project compiles
 *   15 generated project tests execute
 *   16 conformance PASS
 *   17 forbidden framework leakage fail
 *   18 deterministic generation
 *   19 repeated generation no drift
 *   20 V0.3 reference project regression
 */
class PlatformCoreWork001Test {

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
    private static Map<String, Object> readProjectYaml(String relative) throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8));
    }

    private static Map<String, Object> v04ReferenceProject() throws Exception {
        return readProjectYaml("tests/fixtures/v04-reference/platform-core/project.yaml");
    }

    private static Map<String, Object> v03ReferenceProject() throws Exception {
        return readProjectYaml("tests/fixtures/v03-reference/inventory-service/project.yaml");
    }

    private static AssetProjectGenerator.Options options(String basePackage, String projectName) {
        return new AssetProjectGenerator.Options(
                basePackage, projectName, "com.acme", "core-demo", "1.0.0", Map.of());
    }

    // ---- 1. Asset validation ----

    @Test
    void assetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        var asset = repo.capability("platform-core");
        assertThat(asset).as("platform-core capability asset must load").isNotNull();
        assertThat(asset.id()).isEqualTo("platform-core");
        assertThat(asset.type().name()).isEqualTo("CAPABILITY");
        // 9 core templates + 1 consumption test template + HealthController + LongIdDeserializer
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("platform-core");
        assertThat(files).hasSize(11);
        assertThat(files).anyMatch(f -> f.target().endsWith("common/core/ApiResponse.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("common/core/IdGenerator.java"));
    }

    // ---- 2. Registry consistency ----

    @Test
    void registryConsistency() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<String> ids = new ArrayList<>(repo.capabilities().keySet());
        assertThat(ids).as("registry (capabilities/) must contain platform-core").contains("platform-core");
    }

    // ---- 3. Resolver adds platform-core ----

    @Test
    void resolverAddsPlatformCore() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04ReferenceProject());
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(resolution.effectiveProject().capabilities())
                .anyMatch(c -> c.id().equals("platform-core"));
    }

    // ---- 4. EPM contains platform-core ----

    @Test
    void epmContainsPlatformCore() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04ReferenceProject());
        EffectiveProjectModel epm = resolution.effectiveProject();
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("platform-core"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("web"));
    }

    // ---- 5. Generator outputs core files ----

    @Test
    void generatorOutputsCoreFiles() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04ReferenceProject());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("generated-core");
        Files.createDirectories(out);
        AssetProjectGenerator.GenerationResult generated =
                new AssetProjectGenerator().generate(epm, repo, options("com.acme.core", "core-demo"), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        String base = "src/main/java/com/acme/core/common/core/";
        for (String cls : List.of("ApiResponse", "ErrorCode", "PlatformException",
                "PageQuery", "PageResult", "RequestContext", "CurrentClock", "IdGenerator")) {
            assertThat(Files.exists(out.resolve(base + cls + ".java")))
                    .as("core file " + cls + ".java must be generated").isTrue();
        }
        assertThat(Files.exists(out.resolve(
                "src/test/java/com/acme/core/common/core/PlatformCoreConsumptionTest.java")))
                .as("consumption test must be generated").isTrue();
    }

    // ---- 14+15+16. Real generated project: compile + tests execute + conformance PASS ----

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void realGeneratedProjectCompilesTestsAndConformance() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04ReferenceProject());
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("real-project");
        Files.createDirectories(out);
        AssetProjectGenerator.GenerationResult generated =
                new AssetProjectGenerator().generate(epm, repo, options("com.acme.core", "core-demo"), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        // 16. conformance PASS on the real generated project
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status() == ConformanceResult.Status.PASS).as("conformance must PASS:\n%s", conformance.summary()).isTrue();

        // 14+15. generated project mvn test = BUILD SUCCESS (executes PlatformCoreConsumptionTest,
        // which covers spec test points 6-13: ApiResponse/ErrorCode/PlatformException/PageQuery/
        // PageResult/RequestContext/CurrentClock/IdGenerator behavior)
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> command = new ArrayList<>(
                List.of("mvn", "-B", "-f", out.resolve("pom.xml").toString(), "test"));
        if (Files.exists(settings)) {
            command.add(2, "-s");
            command.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(600, TimeUnit.SECONDS);
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue())
                .as("generated project mvn test must pass:\n%s", output)
                .isEqualTo(0);
        // consumption test really ran
        assertThat(output).as("PlatformCoreConsumptionTest must have executed").contains("PlatformCoreConsumptionTest");
    }

    // ---- 17. Forbidden framework leakage fail ----

    @Test
    void forbiddenFrameworkLeakageFails() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04ReferenceProject());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("leak-project");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(epm, repo, options("com.acme.core", "core-demo"), out);

        // clean project must PASS
        ConformanceResult clean = new ConformanceValidator(repo).validate(epm, out);
        assertThat(clean.status() == ConformanceResult.Status.PASS).as("clean project conformance must PASS").isTrue();

        // inject a forbidden framework import into a generated core file -> conformance must FAIL
        Path apiResponse = out.resolve("src/main/java/com/acme/core/common/core/ApiResponse.java");
        String content = Files.readString(apiResponse, StandardCharsets.UTF_8);
        String leaked = content.replaceFirst("package com.acme.core.common.core;",
                "package com.acme.core.common.core;\nimport org.springframework.security.core.Authentication;");
        Files.writeString(apiResponse, leaked, StandardCharsets.UTF_8);

        ConformanceResult failed = new ConformanceValidator(repo).validate(epm, out);
        assertThat(failed.status()).as("leaked core file conformance must FAIL").isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(failed.errors()).as("must contain source.forbidden-import finding")
                .anyMatch(f -> f.ruleId().equals("source.forbidden-import"));
    }

    // ---- 18. Deterministic generation ----

    @Test
    void deterministicGeneration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04ReferenceProject());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out1 = tempDir.resolve("det-1");
        Path out2 = tempDir.resolve("det-2");
        Files.createDirectories(out1);
        Files.createDirectories(out2);
        new AssetProjectGenerator().generate(epm, repo, options("com.acme.core", "core-demo"), out1);
        new AssetProjectGenerator().generate(epm, repo, options("com.acme.core", "core-demo"), out2);

        List<Path> files1;
        try (var stream = Files.walk(out1)) {
            files1 = stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".generator"))
                    .sorted().toList();
        }
        for (Path f1 : files1) {
            Path rel = out1.relativize(f1);
            Path f2 = out2.resolve(rel);
            assertThat(Files.exists(f2)).as("same file set: " + rel).isTrue();
            assertThat(Files.readAllBytes(f1)).as("byte-identical: " + rel)
                    .isEqualTo(Files.readAllBytes(f2));
        }
    }

    // ---- 19. Repeated generation no drift ----

    @Test
    void repeatedGenerationNoDrift() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04ReferenceProject());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("drift");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(epm, repo, options("com.acme.core", "core-demo"), out);

        List<Path> before;
        try (var stream = Files.walk(out)) {
            before = stream.filter(Files::isRegularFile).sorted().toList();
        }
        Map<Path, byte[]> beforeBytes = new java.util.LinkedHashMap<>();
        for (Path f : before) {
            beforeBytes.put(out.relativize(f), Files.readAllBytes(f));
        }

        // second generation over the same directory: executor must not drift content
        new AssetProjectGenerator().generate(epm, repo, options("com.acme.core", "core-demo"), out);

        for (Map.Entry<Path, byte[]> e : beforeBytes.entrySet()) {
            Path f = out.resolve(e.getKey());
            assertThat(Files.exists(f)).as("file still exists: " + e.getKey()).isTrue();
            assertThat(Files.readAllBytes(f)).as("no drift: " + e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    // ---- 20. V0.3 reference project regression ----

    @Test
    void v03ReferenceProjectRegression() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v03ReferenceProject());
        assertThat(resolution.status()).as("V0.3 inventory-service must still resolve")
                .isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("v03-regression");
        Files.createDirectories(out);
        AssetProjectGenerator.GenerationResult generated =
                new AssetProjectGenerator().generate(epm, repo, options("com.acme.inventory", "inventory-service"), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status() == ConformanceResult.Status.PASS).as("V0.3 inventory-service conformance must still PASS:\n%s",
                conformance.summary()).isTrue();
    }
}
