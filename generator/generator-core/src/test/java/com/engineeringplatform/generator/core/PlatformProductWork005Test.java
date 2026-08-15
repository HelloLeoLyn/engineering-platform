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
 * V04-WORK-005 — Enterprise Asset Integration + Reference Product CRUD.
 *
 * EP-side tests for spec points 1-15 + 20; spec points 16-19 (generated project
 * tests, real CRUD HTTP E2E, real DataScope E2E, composition E2E) are covered by
 * the generated project's ProductModelUnitTest + ProductHttpE2ETest +
 * ProductDataScopeE2ETest + EnterpriseCompositionE2ETest + DataScopeE2ETest,
 * executed via the real mvn test in generatedProjectTestsAndRealHttpE2E().
 */
class PlatformProductWork005Test {

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

    // 1. product asset validation
    @Test
    void productAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        var asset = repo.capability("product-reference");
        assertThat(asset).as("product-reference capability must load").isNotNull();
        // enterprise foundation dependency closure declared on the asset
        assertThat(asset.dependencies())
                .anyMatch(d -> d.id().equals("platform-core") && d.required())
                .anyMatch(d -> d.id().equals("authentication") && d.required())
                .anyMatch(d -> d.id().equals("rbac") && d.required())
                .anyMatch(d -> d.id().equals("organization") && d.required())
                .anyMatch(d -> d.id().equals("data-permission") && d.required())
                .anyMatch(d -> d.id().equals("dictionary") && d.required())
                .anyMatch(d -> d.id().equals("operation-log") && d.required())
                .anyMatch(d -> d.id().equals("menu") && d.required())
                .anyMatch(d -> d.id().equals("persistence") && d.required());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("product-reference");
        assertThat(files).hasSize(17);
        assertThat(files).anyMatch(f -> f.target().endsWith("domain/entity/Product.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("application/product/ProductService.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("api/product/ProductController.java"));
        assertThat(files).anyMatch(f -> f.target().endsWith("db/migration/V008__product_reference.sql"));
        assertThat(files).anyMatch(f -> f.target().endsWith("ProductHttpE2ETest.java"));
    }

    // 2. registry consistency
    @Test
    void registryConsistency() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities().keySet()).contains("product-reference");
    }

    // 3. resolver dependency closure (enterprise foundation auto-composed)
    @Test
    void resolverDependencyClosure() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution = resolve(repo);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        List<String> ids = resolution.effectiveProject().capabilities().stream().map(c -> c.id()).toList();
        assertThat(ids).contains("product-reference", "platform-core", "authentication", "rbac",
                "organization", "data-permission", "menu", "dictionary", "operation-log", "persistence");
        assertThat(resolution.effectiveProject().providers())
                .anyMatch(p -> p.id().equals("mybatis-plus"));
    }

    // 3b. spec point 22: single-declaration manifest (capabilities: [product-reference])
    // must auto-compose the whole enterprise foundation via the asset dependency closure.
    @Test
    void singleDeclarationAutoComposesFoundation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> platform = realPlatform();
        Map<String, Object> minimal = Map.of(
                "schemaVersion", 1,
                "project", Map.of(
                        "id", "core-demo", "name", "Core Demo Service", "version", "1.0.0",
                        "basePackage", "com.acme.core", "groupId", "com.acme", "artifactId", "core-demo"),
                "platform", Map.of("id", "engineering-platform"),
                "capabilities", List.of(Map.of("id", "product-reference")),
                "quality", Map.of("minimum", "Q2"));
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, platform, minimal);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        List<String> ids = resolution.effectiveProject().capabilities().stream().map(c -> c.id()).toList();
        // foundation auto-composed from product-reference's declared dependencies
        assertThat(ids).contains("product-reference", "platform-core", "authentication", "rbac",
                "organization", "data-permission", "menu", "dictionary", "operation-log", "persistence");
    }

    // 4. EPM includes assets
    @Test
    void epmIncludesAssets() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("product-reference"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("data-permission"));
    }

    // 5. generated files
    @Test
    void generatedFiles() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("files");
        generate(repo, epm, out);

        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/api/product/ProductController.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/product/ProductService.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/infrastructure/persistence/MybatisProductRepository.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/product/ProductCreateRequest.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/product/ProductUpdateRequest.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/product/ProductResponse.java"))).isTrue();
    }

    // 6. migration
    @Test
    void migrationGenerated() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("mig");
        generate(repo, epm, out);

        String v8 = Files.readString(out.resolve("src/main/resources/db/migration/V008__product_reference.sql"),
                StandardCharsets.UTF_8);
        assertThat(v8).contains("ALTER TABLE product");
        assertThat(v8).contains("code");
        assertThat(v8).contains("status");
        assertThat(v8).contains("uk_product_code");
    }

    // 7. permission codes
    @Test
    void permissionCodes() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("perm");
        generate(repo, epm, out);

        String controller = Files.readString(
                out.resolve("src/main/java/com/acme/core/api/product/ProductController.java"), StandardCharsets.UTF_8);
        assertThat(controller).contains("product:item:create");
        assertThat(controller).contains("product:item:read");
        assertThat(controller).contains("product:item:update");
        assertThat(controller).contains("product:item:disable");

        String seed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zzz-product.sql"), StandardCharsets.UTF_8);
        assertThat(seed).contains("product:item:create");
        assertThat(seed).contains("product:item:update");
        assertThat(seed).contains("product:item:disable");
    }

    // 8. menu integration
    @Test
    void menuIntegration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("menu");
        generate(repo, epm, out);

        String menuSeed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zz-menu.sql"), StandardCharsets.UTF_8);
        assertThat(menuSeed).contains("product:item:read");
    }

    // 9. dictionary integration
    @Test
    void dictionaryIntegration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("dict");
        generate(repo, epm, out);

        String seed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zzz-product.sql"), StandardCharsets.UTF_8);
        assertThat(seed).contains("product_status");

        String service = Files.readString(
                out.resolve("src/main/java/com/acme/core/application/product/ProductService.java"), StandardCharsets.UTF_8);
        assertThat(service).contains("DictionaryPort");
        assertThat(service).contains("product_status");
    }

    // 10. op-log integration
    @Test
    void operationLogIntegration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("oplog");
        generate(repo, epm, out);

        String controller = Files.readString(
                out.resolve("src/main/java/com/acme/core/api/product/ProductController.java"), StandardCharsets.UTF_8);
        assertThat(controller).contains("@OperationLog");
        assertThat(controller).contains("PRODUCT_CREATE");
        assertThat(controller).contains("PRODUCT_UPDATE");
        assertThat(controller).contains("PRODUCT_DISABLE");
    }

    // 11. MyBatis leakage scan on application/domain/api layers
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

    // 12. conformance PASS
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

    // 13. broken enforcement conformance fail (delete ProductRepository enforcement -> FAIL)
    @Test
    void brokenEnforcementFails() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolve(repo).effectiveProject();
        Path out = tempDir.resolve("broken");
        generate(repo, epm, out);

        Files.delete(out.resolve("src/main/java/com/acme/core/infrastructure/persistence/MybatisProductRepository.java"));
        ConformanceResult result = new ConformanceValidator(repo).validate(epm, out);
        assertThat(result.status()).as("missing enforcement impl must FAIL conformance")
                .isEqualTo(ConformanceResult.Status.FAIL);
        assertThat(result.errors()).anyMatch(f -> f.ruleId().equals("asset.required-file"));
    }

    // 14. deterministic generation
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

    // 15. repeated generation no drift
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

    // 16-19. generated project mvn test + real CRUD/DataScope/Composition E2E
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
        // all product test classes ran (spec points 16-19)
        assertThat(output).as("ProductModelUnitTest must have run").contains("ProductModelUnitTest");
        assertThat(output).as("ProductHttpE2ETest must have run").contains("ProductHttpE2ETest");
        assertThat(output).as("ProductDataScopeE2ETest must have run").contains("ProductDataScopeE2ETest");
        assertThat(output).as("EnterpriseCompositionE2ETest must have run").contains("EnterpriseCompositionE2ETest");
        assertThat(output).as("DataScopeE2ETest must have run").contains("DataScopeE2ETest");
    }

    // 20. V0.2/V0.3/WORK-001-004 regression
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
        assertThat(epm.capabilities()).noneMatch(c -> c.id().equals("product-reference"));
        assertThat(epm.capabilities()).noneMatch(c -> c.id().equals("data-permission"));

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
