package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ConformanceFinding;
import com.engineeringplatform.generator.contracts.ConformanceResult;
import com.engineeringplatform.generator.contracts.ConformanceSeverity;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
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

/**
 * Engineering Conformance Validator V1 tests (V02-WORK-005).
 * Uses the real reference project -> resolver -> generation chain; failure
 * scenarios deliberately break the generated project and expect FAIL + findings.
 */
class ConformanceValidatorTest {

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

    /** Generates the reference project into a fresh temp dir; returns (epm, repo, projectRoot). */
    private static Generated generate(Path outDir) throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, PLATFORM, referenceProject());
        if (resolution.status() != ResolutionResult.Status.SUCCESS) {
            throw new IllegalStateException("reference project must resolve: " + resolution.errors());
        }
        EffectiveProjectModel epm = resolution.effectiveProject();
        Files.createDirectories(outDir);
        new AssetProjectGenerator().generate(epm, repo, options(), outDir);
        return new Generated(epm, repo, outDir);
    }

    private record Generated(EffectiveProjectModel epm, AssetRepository repo, Path root) {
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

    private static ConformanceResult validate(Generated g) throws Exception {
        return new ConformanceValidator(g.repo()).validate(g.epm(), g.root());
    }

    // ---- 1. generated reference project -> Conformance PASS -> mvn test BUILD SUCCESS ----

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void generatedReferenceProjectPassesAndBuilds() throws Exception {
        Generated g = generate(tempDir.resolve("proj"));
        ConformanceResult result = validate(g);

        assertThat(result.status()).isEqualTo(ConformanceResult.Status.PASS);
        assertThat(result.errors()).isEmpty();

        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> command = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                g.root().resolve("pom.xml").toString(), "test"));
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
    }

    // ---- 2. missing required file ----

    @Test
    void missingRequiredFileFails() throws Exception {
        Generated g = generate(tempDir.resolve("proj2"));
        Files.delete(g.root().resolve(
                "src/main/java/com/engineeringplatform/demoorderservice/common/error/GlobalExceptionHandler.java"));

        ConformanceResult result = validate(g);
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("asset.required-file")
                && f.message().contains("GlobalExceptionHandler"));
    }

    // ---- 3. missing required dependency ----

    @Test
    void missingDependencyFails() throws Exception {
        Generated g = generate(tempDir.resolve("proj3"));
        String pom = Files.readString(g.root().resolve("pom.xml"));
        String patched = pom.replaceFirst(
                "<dependency>\\s*<groupId>org.springframework.boot</groupId>"
                        + "\\s*<artifactId>spring-boot-starter-validation</artifactId>"
                        + "\\s*</dependency>",
                "");
        assertThat(patched).isNotEqualTo(pom);
        Files.writeString(g.root().resolve("pom.xml"), patched);

        ConformanceResult result = validate(g);
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("dependency.required")
                && f.message().contains("spring-boot-starter-validation"));
    }

    // ---- 4. forbidden dependency ----

    @Test
    void forbiddenDependencyFails() throws Exception {
        Generated g = generate(tempDir.resolve("proj4"));
        String pom = Files.readString(g.root().resolve("pom.xml"));
        String patched = pom.replace("</dependencies>", """
                    <dependency>
                        <groupId>org.apache.log4j</groupId>
                        <artifactId>log4j</artifactId>
                        <version>1.2.17</version>
                    </dependency>
                </dependencies>""");
        Files.writeString(g.root().resolve("pom.xml"), patched);

        ConformanceResult result = validate(g);
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("dependency.forbidden")
                && f.message().contains("log4j"));
    }

    // ---- 5. missing required config ----

    @Test
    void missingConfigFails() throws Exception {
        Generated g = generate(tempDir.resolve("proj5"));
        Path yml = g.root().resolve("src/main/resources/application.yml");
        String patched = Files.readString(yml).replace("spring.application.name: demo-order-service\n", "");
        assertThat(patched).isNotEqualTo(Files.readString(yml));
        Files.writeString(yml, patched);

        ConformanceResult result = validate(g);
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("config.required")
                && f.message().contains("spring.application.name"));
    }

    // ---- 6. provider mismatch ----

    @Test
    void providerMismatchFails() throws Exception {
        Generated g = generate(tempDir.resolve("proj6"));
        String pom = Files.readString(g.root().resolve("pom.xml"));
        String patched = pom.replaceFirst(
                "<dependency>\\s*<groupId>com.baomidou</groupId>"
                        + "\\s*<artifactId>mybatis-plus-spring-boot3-starter</artifactId>"
                        + "\\s*<version>3.5.17</version>\\s*</dependency>",
                "");
        assertThat(patched).isNotEqualTo(pom);
        Files.writeString(g.root().resolve("pom.xml"), patched);

        ConformanceResult result = validate(g);
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("provider.mismatch")
                && f.message().contains("mybatis-plus"));
    }

    // ---- 7. java version mismatch ----

    @Test
    void javaMismatchFails() throws Exception {
        Generated g = generate(tempDir.resolve("proj7"));
        Path pom = g.root().resolve("pom.xml");
        String patched = Files.readString(pom).replace("<java.version>25</java.version>",
                "<java.version>17</java.version>");
        Files.writeString(pom, patched);

        ConformanceResult result = validate(g);
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("technology.java-version"));
    }

    // ---- 8. spring boot version mismatch ----

    @Test
    void springBootMismatchFails() throws Exception {
        Generated g = generate(tempDir.resolve("proj8"));
        Path pom = g.root().resolve("pom.xml");
        String patched = Files.readString(pom).replace("<version>3.5.3</version>",
                "<version>2.7.18</version>");
        Files.writeString(pom, patched);

        ConformanceResult result = validate(g);
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("technology.spring-boot-version"));
    }

    // ---- 9. warning does not fail ----

    @Test
    void warningDoesNotFail() throws Exception {
        Generated g = generate(tempDir.resolve("proj9"));
        ConformanceResult result = validate(g);

        // mybatis-plus asset declares a test fixture reference not present in the generated project -> WARNING
        assertThat(result.status()).isEqualTo(ConformanceResult.Status.PASS);
        assertThat(result.warnings()).anyMatch(f -> f.ruleId().equals("asset.tests-reference"));
    }

    // ---- 10. deterministic findings ----

    @Test
    void deterministicFindings() throws Exception {
        Generated g = generate(tempDir.resolve("proj10"));
        ConformanceResult r1 = validate(g);
        ConformanceResult r2 = validate(g);

        assertThat(r1.findings()).isEqualTo(r2.findings());
    }

    // ---- 11. source EPM unchanged ----

    @Test
    void sourceEpmUnchanged() throws Exception {
        Generated g = generate(tempDir.resolve("proj11"));
        EffectiveProjectModel before = g.epm();
        validate(g);

        assertThat(g.epm().capabilities()).isEqualTo(before.capabilities());
        assertThat(g.epm().providers()).isEqualTo(before.providers());
        assertThat(g.epm().quality()).isEqualTo(before.quality());
        assertThat(g.epm().technology()).isEqualTo(before.technology());
    }

    // ---- 12. source assets unchanged ----

    @Test
    void sourceAssetsUnchanged() throws Exception {
        Generated g = generate(tempDir.resolve("proj12"));
        Map<String, com.engineeringplatform.generator.contracts.EngineeringAsset> capsBefore = g.repo().capabilities();
        Map<String, com.engineeringplatform.generator.contracts.EngineeringAsset> provsBefore = g.repo().providers();
        validate(g);

        assertThat(g.repo().capabilities()).isEqualTo(capsBefore);
        assertThat(g.repo().providers()).isEqualTo(provsBefore);
    }
}
