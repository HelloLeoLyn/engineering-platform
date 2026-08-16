package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ConformanceResult;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
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

/**
 * V05-WORK-006 — Runtime Recipe.
 *
 * EP-side tests: runtime-recipe asset, runnable boot jar contract (pom build
 * section), e2e profile + seed placement, readiness endpoint, script contract
 * (no brace-params that collide with the renderer), no hardcoded backend URL,
 * conformance.
 */
class RuntimeRecipeWork006Test {

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
    private static Map<String, Object> v05Project() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v05-reference/frontend-auth/project.yaml"), StandardCharsets.UTF_8));
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.acme.core", "core-demo", "com.acme", "core-demo", "1.0.0", Map.of());
    }

    private static ResolutionResult resolveV05(AssetRepository repo) throws Exception {
        return new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v05Project());
    }

    private static AssetProjectGenerator.GenerationResult generate(AssetRepository repo,
                                                                   EffectiveProjectModel epm,
                                                                   Path out) throws Exception {
        Files.createDirectories(out);
        return new AssetProjectGenerator().generate(epm, repo, options(), out);
    }

    // 1. runtime-recipe asset validation
    @Test
    void runtimeRecipeAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("runtime-recipe");
        for (String target : List.of(
                "scripts/dev-start.sh", "scripts/dev-stop.sh", "scripts/dev-status.sh", "RUNTIME.md")) {
            assertThat(files).as("asset file: " + target)
                    .anyMatch(f -> f.target().equals(target));
        }
    }

    // 2. runnable boot jar contract: pom build section with spring-boot-maven-plugin
    @Test
    void runnableBootJarContract() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("jar");
        generate(repo, epm, out);
        String pom = Files.readString(out.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).contains("spring-boot-maven-plugin").contains("<goal>repackage</goal>");
        assertThat(pom).contains("<build>");
    }

    // 3. e2e profile + seed placement (main resources, not test-only)
    @Test
    void e2eProfileAndSeed() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("e2e");
        generate(repo, epm, out);

        assertThat(Files.exists(out.resolve("src/main/resources/application-e2e.yml")))
                .as("application-e2e.yml in main resources").isTrue();
        String e2e = Files.readString(out.resolve("src/main/resources/application-e2e.yml"),
                StandardCharsets.UTF_8);
        assertThat(e2e).contains("jdbc:h2:mem:e2edb").contains("classpath:db/seed-e2e/*.sql");
        // no committed secret in e2e config
        assertThat(e2e).doesNotContain("secret:");
        // e2e seeds land in main resources
        for (String seed : List.of("seed-test-data.sql", "seed-z-data-permission.sql",
                "seed-zz-menu.sql", "seed-zz-dictionary.sql", "seed-zzz-product.sql")) {
            assertThat(Files.exists(out.resolve("src/main/resources/db/seed-e2e/" + seed)))
                    .as("e2e seed " + seed).isTrue();
        }
    }

    // 4. readiness endpoint + anonymous path
    @Test
    void readinessEndpoint() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("health");
        generate(repo, epm, out);
        String health = Files.readString(
                out.resolve("src/main/java/com/acme/core/api/health/HealthController.java"),
                StandardCharsets.UTF_8);
        assertThat(health).contains("/api/health").contains("\"UP\"");
        String security = Files.readString(
                out.resolve("src/main/java/com/acme/core/infrastructure/security/SecurityWebConfig.java"),
                StandardCharsets.UTF_8);
        assertThat(security).contains("/api/health");
    }

    // 5. script contract: renderer-safe + runtime state + port allocation
    @Test
    void scriptContractNoBraceParams() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("scripts");
        generate(repo, epm, out);
        for (String script : List.of("dev-start.sh", "dev-stop.sh", "dev-status.sh")) {
            String text = Files.readString(out.resolve("scripts/" + script), StandardCharsets.UTF_8);
            assertThat(text).as(script + " is a bash script").contains("#!/usr/bin/env bash");
        }
        // state files + port auto-allocation contract
        String start = Files.readString(out.resolve("scripts/dev-start.sh"), StandardCharsets.UTF_8);
        assertThat(start).contains("backend.port").contains("backend.url")
                .contains("frontend.port").contains("frontend.url")
                .contains("allocate_port").contains("setsid");
        String status = Files.readString(out.resolve("scripts/dev-status.sh"), StandardCharsets.UTF_8);
        assertThat(status).contains("backend.pid").contains("frontend.pid")
                .contains("backend.port").contains("frontend.port");
    }

    // 6. no hardcoded backend URL in frontend runtime (vite proxy from env)
    @Test
    void frontendNoHardcodedBackendUrl() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("vite");
        generate(repo, epm, out);
        String vite = Files.readString(out.resolve("frontend/vite.config.ts"), StandardCharsets.UTF_8);
        assertThat(vite).contains("VITE_BACKEND_URL").contains("proxy");
        String start = Files.readString(out.resolve("scripts/dev-start.sh"), StandardCharsets.UTF_8);
        assertThat(start).contains("VITE_BACKEND_URL").contains(".runtime/backend.url");
    }

    // 7. conformance PASS
    @Test
    void conformancePass() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("conf");
        generate(repo, epm, out);
        ConformanceResult result = new ConformanceValidator(repo).validate(epm, out);
        assertThat(result.status()).as("conformance must PASS:\n%s", result.summary())
                .isEqualTo(ConformanceResult.Status.PASS);
    }

    // 8. generated backend builds a runnable jar (boot repackage)
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void runnableJarBuilds() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("build");
        AssetProjectGenerator.GenerationResult generated = generate(repo, epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "package", "-DskipTests", "-q"));
        if (Files.exists(settings)) {
            cmd.add(2, "-s");
            cmd.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(600, TimeUnit.SECONDS);
        assertThat(finished).as("mvn package must finish").isTrue();
        assertThat(process.exitValue())
                .as("mvn package must pass:\n%s", new String(output, StandardCharsets.UTF_8))
                .isEqualTo(0);

        List<Path> jars;
        try (var stream = Files.list(out.resolve("target"))) {
            jars = stream.filter(p -> p.toString().endsWith(".jar")
                    && !p.toString().endsWith(".original")).toList();
        }
        assertThat(jars).as("runnable jar produced").hasSize(1);
        // boot jar contains BOOT-INF (repackage marker)
        Process unzip = new ProcessBuilder("unzip", "-l", jars.get(0).toString())
                .redirectErrorStream(true).start();
        byte[] listing = unzip.getInputStream().readAllBytes();
        unzip.waitFor(30, TimeUnit.SECONDS);
        String listingText = new String(listing, StandardCharsets.UTF_8);
        assertThat(listingText).as("jar contains BOOT-INF").contains("BOOT-INF/");
    }

    // 9. deterministic
    @Test
    void deterministicGeneration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out1 = tempDir.resolve("det1");
        Path out2 = tempDir.resolve("det2");
        generate(repo, epm, out1);
        generate(repo, epm, out2);
        List<Path> files1;
        try (var stream = Files.walk(out1)) {
            files1 = stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".generator")).sorted().toList();
        }
        assertThat(files1).isNotEmpty();
        for (Path f1 : files1) {
            Path rel = out1.relativize(f1);
            assertThat(Files.exists(out2.resolve(rel))).as("same: " + rel).isTrue();
            assertThat(Files.readAllBytes(f1)).as("byte-identical: " + rel)
                    .isEqualTo(Files.readAllBytes(out2.resolve(rel)));
        }
    }
}
