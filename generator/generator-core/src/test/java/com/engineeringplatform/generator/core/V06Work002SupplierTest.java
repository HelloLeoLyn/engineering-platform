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
 * V06-WORK-002 — Full-stack Business Module Generation (Supplier proof domain).
 *
 * Supplier is the SECOND business domain (Product = pattern source, Supplier =
 * proof): both domains must resolve/generate/conform through the same
 * deterministic asset pipeline, proving Generic Module Generation rather than
 * copy-paste. FAST_DEV budget: supplier-related tests + frontend affected tests.
 */
class V06Work002SupplierTest {

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
    private static Map<String, Object> v06Project() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v06-reference/supplier/project.yaml"), StandardCharsets.UTF_8));
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.acme.core", "core-demo", "com.acme", "core-demo", "1.0.0", Map.of());
    }

    private static ResolutionResult resolveV06(AssetRepository repo) throws Exception {
        return new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v06Project());
    }

    private static AssetProjectGenerator.GenerationResult generate(AssetRepository repo,
                                                                   EffectiveProjectModel epm,
                                                                   Path out) throws Exception {
        Files.createDirectories(out);
        return new AssetProjectGenerator().generate(epm, repo, options(), out);
    }

    // 1. registry: supplier-reference + frontend-supplier-reference registered
    @Test
    void supplierAssetsRegistered() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities()).containsKeys("supplier-reference", "frontend-supplier-reference");
        assertThat(repo.capabilities().get("supplier-reference").description()).contains("Supplier");
    }

    // 2. backend asset file set complete
    @Test
    void supplierBackendAssetValid() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("supplier-reference");
        for (String target : List.of(
                "src/main/java/{package}/domain/entity/Supplier.java",
                "src/main/java/{package}/infrastructure/persistence/mapper/SupplierMapper.java",
                "src/main/java/{package}/application/supplier/SupplierPort.java",
                "src/main/java/{package}/infrastructure/persistence/MybatisSupplierRepository.java",
                "src/main/java/{package}/application/supplier/SupplierService.java",
                "src/main/java/{package}/api/supplier/SupplierController.java",
                "src/main/resources/db/migration/V009__supplier_reference.sql",
                "src/test/java/{package}/SupplierHttpE2ETest.java",
                "src/test/java/{package}/SupplierDataScopeE2ETest.java")) {
            assertThat(files).as("backend asset file: " + target)
                    .anyMatch(f -> f.target().equals(target));
        }
    }

    // 3. frontend asset file set complete (reuses enterprise-management UI)
    @Test
    void supplierFrontendAssetValid() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("frontend-supplier-reference");
        for (String target : List.of(
                "frontend/src/types/supplier.ts",
                "frontend/src/api/supplier.ts",
                "frontend/src/router/business/supplier.ts",
                "frontend/src/views/supplier/SupplierListView.vue",
                "frontend/src/views/supplier/SupplierDetailView.vue",
                "frontend/src/views/supplier/SupplierEditView.vue",
                "frontend/tests/supplier-api.spec.ts",
                "frontend/tests/supplier-list.spec.ts",
                "frontend/tests/supplier-detail.spec.ts",
                "frontend/tests/supplier-route.spec.ts")) {
            assertThat(files).as("frontend asset file: " + target)
                    .anyMatch(f -> f.target().equals(target));
        }
        assertThat(files).hasSize(10);
    }

    // 4. supplier routes registered (aggregated via business/*.ts glob by frontend-auth router, V06-WORK-002B)
    @Test
    void supplierRoutesRegistered() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("routes");
        generate(repo, epm, out);

        String router = Files.readString(out.resolve("frontend/src/router/index.ts"), StandardCharsets.UTF_8);
        assertThat(router).contains("import.meta.glob('../router/business/*.ts', { eager: true })")
                .contains("...businessRoutes")
                .doesNotContain("../router/supplier.ts");

        String supplier = Files.readString(out.resolve("frontend/src/router/business/supplier.ts"), StandardCharsets.UTF_8);
        assertThat(supplier).contains("export const businessRoutes");
        for (String route : List.of("/suppliers", "/suppliers/:id", "/suppliers/:id/edit")) {
            assertThat(supplier).as("route " + route).contains(route);
        }
    }

    // 5. request-client-only: supplier api uses platform client; views have no fetch/axios
    @Test
    void requestClientOnly() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("http");
        generate(repo, epm, out);

        String api = Files.readString(out.resolve("frontend/src/api/supplier.ts"), StandardCharsets.UTF_8);
        assertThat(api).contains("import { http } from './request'");
        for (String line : api.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            assertThat(t).as("no raw http in supplier.ts")
                    .doesNotContain("fetch(").doesNotContain("axios");
        }
        try (var stream = Files.walk(out.resolve("frontend/src/views/supplier"))) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                assertThat(text).as("no fetch in " + f.getFileName())
                        .doesNotContain("fetch(").doesNotContain("axios");
            }
        }
    }

    // 6. dictionary-driven status (supplier_status consumed via DictionarySelect)
    @Test
    void dictionaryDrivenStatus() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("dict");
        generate(repo, epm, out);

        String listView = Files.readString(
                out.resolve("frontend/src/views/supplier/SupplierListView.vue"), StandardCharsets.UTF_8);
        assertThat(listView).contains("DictionarySelect")
                .contains("code=\"supplier_status\"")
                .contains("code=\"supplier_category\"");
        assertThat(listView).doesNotContain("<el-option label=\"Enabled\"");
        String supplierSeed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zzz-supplier.sql"), StandardCharsets.UTF_8);
        assertThat(supplierSeed).contains("supplier_status").contains("'ENABLED'").contains("'DISABLED'");
        String dictSeed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zz-dictionary.sql"), StandardCharsets.UTF_8);
        assertThat(dictSeed).doesNotContain("supplier_status");
    }

    // 7. permission integration on supplier actions
    @Test
    void permissionIntegration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("perm");
        generate(repo, epm, out);

        String list = Files.readString(
                out.resolve("frontend/src/views/supplier/SupplierListView.vue"), StandardCharsets.UTF_8);
        assertThat(list).contains("PermissionButton")
                .contains("permission=\"supplier:item:create\"")
                .contains("permission=\"supplier:item:disable\"");
        String detail = Files.readString(
                out.resolve("frontend/src/views/supplier/SupplierDetailView.vue"), StandardCharsets.UTF_8);
        assertThat(detail).contains("permission=\"supplier:item:update\"")
                .contains("permission=\"supplier:item:disable\"");
    }

    // 8. backend filter enhancement (keyword/status/category)
    @Test
    void backendFilterEnhancement() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("filter");
        generate(repo, epm, out);

        String controller = Files.readString(
                out.resolve("src/main/java/com/acme/core/api/supplier/SupplierController.java"), StandardCharsets.UTF_8);
        assertThat(controller).contains("String keyword").contains("String status").contains("String category")
                .contains("pageFiltered");
        String port = Files.readString(
                out.resolve("src/main/java/com/acme/core/application/supplier/SupplierPort.java"), StandardCharsets.UTF_8);
        assertThat(port).contains("findPageByScopeFiltered");
    }

    // 9. menu seed: /suppliers aligned with route
    @Test
    void menuSeedAligned() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("menu");
        generate(repo, epm, out);
        String seed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zzz-supplier.sql"), StandardCharsets.UTF_8);
        assertThat(seed).contains("'/suppliers'").contains("supplier:item:read");
    }

    // 10. conformance PASS
    @Test
    void conformancePass() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("conf");
        generate(repo, epm, out);
        ConformanceResult result = new ConformanceValidator(repo).validate(epm, out);
        assertThat(result.status()).as("conformance must PASS:\n%s", result.summary())
                .isEqualTo(ConformanceResult.Status.PASS);
    }

    // 11. deterministic
    @Test
    void deterministicGeneration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
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

    // 12. Dual-domain proof: Product (pattern) + Supplier (proof) both generate
    @Test
    void dualDomainProof() throws Exception {
        // v05 fixture (product-reference) and v06 fixture (supplier-reference) must
        // both resolve + generate + conform through the SAME deterministic pipeline.
        AssetRepository repo = AssetRepository.load(repoRoot());
        for (String fixture : List.of("tests/fixtures/v05-reference/frontend-auth/project.yaml",
                "tests/fixtures/v06-reference/supplier/project.yaml")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> project = (Map<String, Object>) AssetYamlReader.parse(
                    Files.readString(repoRoot().resolve(fixture), StandardCharsets.UTF_8));
            ResolutionResult resolution = new AssetAwareResolver(ALWAYS_VALID)
                    .resolve(repo, realPlatform(), project);
            assertThat(resolution.status()).as("resolve " + fixture).isEqualTo(ResolutionResult.Status.SUCCESS);
            Path out = tempDir.resolve("dual-" + fixture.hashCode());
            AssetProjectGenerator.GenerationResult generated = generate(repo, resolution.effectiveProject(), out);
            assertThat(generated.execution().status()).as("generate " + fixture)
                    .isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
            ConformanceResult conformance = new ConformanceValidator(repo).validate(resolution.effectiveProject(), out);
            assertThat(conformance.status()).as("conformance " + fixture)
                    .isEqualTo(ConformanceResult.Status.PASS);
        }
        // both domain entities exist in their respective outputs
        Path v05Out = tempDir.resolve("dual-" + "tests/fixtures/v05-reference/frontend-auth/project.yaml".hashCode());
        Path v06Out = tempDir.resolve("dual-" + "tests/fixtures/v06-reference/supplier/project.yaml".hashCode());
        assertThat(Files.exists(v05Out.resolve("src/main/java/com/acme/core/domain/entity/Product.java"))).isTrue();
        assertThat(Files.exists(v06Out.resolve("src/main/java/com/acme/core/domain/entity/Supplier.java"))).isTrue();
    }

    // 13. WORK-002 acceptance: generated project supplier tests + frontend test/build
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void generatedProjectAcceptance() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV06(repo).effectiveProject();
        Path out = tempDir.resolve("real");
        AssetProjectGenerator.GenerationResult generated = generate(repo, epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);

        // backend: supplier-related tests only (FAST_DEV budget)
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "test",
                "-Dtest=SupplierHttpE2ETest,SupplierDataScopeE2ETest,EnterpriseCompositionE2ETest,SupplierModelUnitTest,DictionaryE2ETest",
                "-Dsurefire.failIfNoSpecifiedTests=false"));
        if (Files.exists(settings)) {
            cmd.add(2, "-s");
            cmd.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(900, TimeUnit.SECONDS);
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue())
                .as("generated backend supplier tests must pass:\n%s", output)
                .isEqualTo(0);
        for (String suite : List.of("com.acme.core.SupplierHttpE2ETest",
                "com.acme.core.SupplierDataScopeE2ETest",
                "com.acme.core.EnterpriseCompositionE2ETest")) {
            Path report = out.resolve("target/surefire-reports/" + suite + ".txt");
            assertThat(Files.exists(report)).as(suite + " report exists").isTrue();
            String text = Files.readString(report, StandardCharsets.UTF_8);
            assertThat(text).as(suite + " ran").contains("Tests run:");
            assertThat(text).as(suite + " no failures").contains("Failures: 0");
        }

        // frontend: supplier affected tests + build (once, per budget)
        Path frontend = out.resolve("frontend");
        assertThat(run(frontend, "pnpm", "install")).as("pnpm install").isEqualTo(0);
        assertThat(run(frontend, "pnpm", "test")).as("pnpm test").isEqualTo(0);
        assertThat(run(frontend, "pnpm", "build")).as("pnpm build").isEqualTo(0);
    }

    private static int run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        byte[] out = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(600, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timeout: " + String.join(" ", command));
        }
        return process.exitValue();
    }
}
