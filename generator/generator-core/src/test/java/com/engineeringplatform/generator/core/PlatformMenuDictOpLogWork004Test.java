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
 * V04-WORK-004 — Menu + Dictionary + Operation Log.
 *
 * EP-side tests for spec points 1-7 + 23-30; spec points 8-22 (menu tree,
 * permission filtering, self-parent/cycle, dictionary enabled/disabled query,
 * stable ordering, operation SUCCESS/FAILURE, persistence, metadata, sensitive
 * data exclusion, permission denied, anonymous, RequestContext cleanup) are
 * covered by the generated project's MenuModelUnitTest + MenuE2ETest +
 * DictionaryE2ETest + OperationLogE2ETest, executed via the real mvn test in
 * generatedProjectTestsAndRealHttpE2E().
 */
class PlatformMenuDictOpLogWork004Test {

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
    private static Map<String, Object> v04Project() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v04-reference/platform-core/project.yaml"), StandardCharsets.UTF_8));
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.acme.core", "core-demo", "com.acme", "core-demo", "1.0.0", Map.of());
    }

    private static ResolutionResult resolve(AssetRepository repo) throws Exception {
        return new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
    }

    private static AssetProjectGenerator.GenerationResult generate(AssetRepository repo,
                                                                   EffectiveProjectModel epm,
                                                                   Path out) throws Exception {
        Files.createDirectories(out);
        return new AssetProjectGenerator().generate(epm, repo, options(), out);
    }

    // 1. menu asset validation
    @Test
    void menuAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        var asset = repo.capability("menu");
        assertThat(asset).as("menu capability must load").isNotNull();
        assertThat(asset.dependencies())
                .anyMatch(d -> d.id().equals("platform-core") && d.required())
                .anyMatch(d -> d.id().equals("rbac") && d.required())
                .anyMatch(d -> d.id().equals("persistence") && d.required());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("menu");
        assertThat(files).hasSize(12);
        assertThat(files).anyMatch(f -> f.target().endsWith("domain/entity/SysMenu.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("common/security/MenuType.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("api/menu/MenuController.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("db/migration/V005__menu.sql"));
    }

    // 2. dictionary asset validation
    @Test
    void dictionaryAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        var asset = repo.capability("dictionary");
        assertThat(asset).as("dictionary capability must load").isNotNull();
        assertThat(asset.dependencies())
                .anyMatch(d -> d.id().equals("platform-core") && d.required())
                .anyMatch(d -> d.id().equals("persistence") && d.required());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("dictionary");
        assertThat(files).hasSize(14);
        assertThat(files).anyMatch(f -> f.target().endsWith("domain/entity/DictionaryType.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("domain/entity/DictionaryItem.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("application/dictionary/DictionaryItemDto.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("db/migration/V006__dictionary.sql"));
    }

    // 3. operation-log asset validation
    @Test
    void operationLogAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        var asset = repo.capability("operation-log");
        assertThat(asset).as("operation-log capability must load").isNotNull();
        assertThat(asset.dependencies())
                .anyMatch(d -> d.id().equals("platform-core") && d.required())
                .anyMatch(d -> d.id().equals("rbac") && d.required())
                .anyMatch(d -> d.id().equals("persistence") && d.required());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("operation-log");
        assertThat(files).hasSize(12);
        assertThat(files).anyMatch(f -> f.target().endsWith("common/security/OperationLog.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("infrastructure/security/OperationLogAspect.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("domain/entity/SysOperationLog.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("db/migration/V007__operation_log.sql"));
    }

    // 4. registry consistency
    @Test
    void registryConsistency() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities().keySet())
                .contains("platform-core", "authentication", "rbac", "organization", "data-permission",
                        "menu", "dictionary", "operation-log");
    }

    // 5. resolver dependency closure
    @Test
    void resolverDependencyClosure() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution = resolve(repo);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        List<String> ids = resolution.effectiveProject().capabilities().stream().map(c -> c.id()).toList();
        assertThat(ids).contains("menu", "dictionary", "operation-log", "persistence", "rbac");
        assertThat(resolution.effectiveProject().providers())
                .anyMatch(p -> p.id().equals("mybatis-plus"));
    }

    // 6. EPM includes assets
    @Test
    void epmIncludesAssets() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("menu"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("dictionary"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("operation-log"));
    }

    // 7. migrations generated
    @Test
    void migrationsGenerated() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("mig");
        generate(repo, epm, out);

        String v5 = Files.readString(out.resolve("src/main/resources/db/migration/V005__menu.sql"),
                StandardCharsets.UTF_8);
        assertThat(v5).contains("CREATE TABLE IF NOT EXISTS sys_menu");
        assertThat(v5).contains("CONSTRAINT uk_sys_menu_code UNIQUE (code)");

        String v6 = Files.readString(out.resolve("src/main/resources/db/migration/V006__dictionary.sql"),
                StandardCharsets.UTF_8);
        assertThat(v6).contains("CREATE TABLE IF NOT EXISTS sys_dictionary_type");
        assertThat(v6).contains("CREATE TABLE IF NOT EXISTS sys_dictionary_item");
        assertThat(v6).contains("CONSTRAINT uk_sys_dictionary_item UNIQUE (type_id, `value`)");

        String v7 = Files.readString(out.resolve("src/main/resources/db/migration/V007__operation_log.sql"),
                StandardCharsets.UTF_8);
        assertThat(v7).contains("CREATE TABLE IF NOT EXISTS sys_operation_log");
    }

    // 23. MyBatis leakage scan on application/domain/api layers
    @Test
    void mybatisLeakageScan() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("leak");
        generate(repo, epm, out);

        try (var stream = Files.walk(out)) {
            for (Path f : stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = out.relativize(f).toString().replace('\\', '/');
                // infrastructure layer may use MyBatis; application/domain/api must not
                if (rel.contains("/infrastructure/")) {
                    continue;
                }
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                        continue;
                    }
                    assertThat(t).as("no MyBatis leak in " + rel)
                            .doesNotContain("QueryWrapper")
                            .doesNotContain("BaseMapper")
                            .doesNotContain("IPage")
                            .doesNotContain("com.baomidou.mybatisplus");
                }
            }
        }
    }

    // 24. conformance PASS
    @Test
    void conformancePass() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("conf");
        generate(repo, epm, out);
        ConformanceResult result = new ConformanceValidator(repo).validate(epm, out);
        assertThat(result.status()).as("conformance must PASS:\n%s", result.summary())
                .isEqualTo(ConformanceResult.Status.PASS);
    }

    // 25. broken enforcement conformance/test fail (delete OperationLogAspect -> FAIL)
    @Test
    void brokenEnforcementFails() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("broken");
        generate(repo, epm, out);

        // delete the operation-log enforcement implementation -> conformance must FAIL
        Files.delete(out.resolve("src/main/java/com/acme/core/infrastructure/security/OperationLogAspect.java"));
        ConformanceResult result = new ConformanceValidator(repo).validate(epm, out);
        assertThat(result.status()).as("missing enforcement impl must FAIL conformance")
                .isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("asset.required-file"));
    }

    // 26. deterministic generation
    @Test
    void deterministicGeneration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
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
            assertThat(Files.exists(out2.resolve(rel))).as("same file: " + rel).isTrue();
            assertThat(Files.readAllBytes(f1)).as("byte-identical: " + rel)
                    .isEqualTo(Files.readAllBytes(out2.resolve(rel)));
        }
    }

    // 27. repeated generation no drift
    @Test
    void repeatedGenerationNoDrift() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("drift");
        generate(repo, epm, out);
        Map<Path, byte[]> before = new java.util.LinkedHashMap<>();
        try (var stream = Files.walk(out)) {
            for (Path f : stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".generator")).toList()) {
                before.put(out.relativize(f), Files.readAllBytes(f));
            }
        }
        generate(repo, epm, out);
        for (Map.Entry<Path, byte[]> e : before.entrySet()) {
            assertThat(Files.readAllBytes(out.resolve(e.getKey())))
                    .as("no drift: " + e.getKey()).isEqualTo(e.getValue());
        }
    }

    // 28+29. generated project mvn test + real HTTP E2E
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void generatedProjectTestsAndRealHttpE2E() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("real");
        AssetProjectGenerator.GenerationResult generated = generate(repo, epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);

        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> command = new ArrayList<>(
                List.of("mvn", "-B", "-f", out.resolve("pom.xml").toString(), "test"));
        if (Files.exists(settings)) {
            command.add(2, "-s");
            command.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(900, TimeUnit.SECONDS);
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue())
                .as("generated project mvn test must pass:\n%s", output)
                .isEqualTo(0);
        // real HTTP E2E ran (spec points 8-22 covered by the generated tests)
        assertThat(output).as("MenuModelUnitTest must have run").contains("MenuModelUnitTest");
        assertThat(output).as("MenuE2ETest must have run").contains("MenuE2ETest");
        assertThat(output).as("DictionaryE2ETest must have run").contains("DictionaryE2ETest");
        assertThat(output).as("OperationLogE2ETest must have run").contains("OperationLogE2ETest");
    }

    // 30. V0.2/V0.3/WORK-001/002/003 regression
    @Test
    void v03ReferenceRegression() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> project = (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v03-reference/inventory-service/project.yaml"),
                StandardCharsets.UTF_8));
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), project);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolution.effectiveProject();
        assertThat(epm.capabilities()).noneMatch(c -> c.id().equals("menu"));
        assertThat(epm.capabilities()).noneMatch(c -> c.id().equals("dictionary"));
        assertThat(epm.capabilities()).noneMatch(c -> c.id().equals("operation-log"));

        Path out = tempDir.resolve("v03");
        Files.createDirectories(out);
        AssetProjectGenerator.GenerationResult generated = new AssetProjectGenerator().generate(epm, repo,
                new AssetProjectGenerator.Options("com.acme.inventory", "inventory-service",
                        "com.acme", "inventory-service", "2.1.0", Map.of()), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);
    }
}
