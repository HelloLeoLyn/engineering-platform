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
 * V05-WORK-005 — Product Full-stack Reference Module.
 *
 * EP-side tests: frontend-product-reference asset validation, product routes,
 * request-client-only rule, dictionary-driven status, permission integration,
 * menu seed /products alignment, product API filter enhancement, conformance,
 * determinism, generated project product tests + frontend test/build.
 */
class ProductReferenceWork005Test {

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

    // 1. asset validation
    @Test
    void productFrontendAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("frontend-product-reference");
        for (String target : List.of(
                "frontend/src/types/product.ts",
                "frontend/src/api/product.ts",
                "frontend/src/router/product.ts",
                "frontend/src/views/product/ProductListView.vue",
                "frontend/src/views/product/ProductDetailView.vue",
                "frontend/src/views/product/ProductEditView.vue",
                "frontend/tests/product-api.spec.ts",
                "frontend/tests/product-list.spec.ts",
                "frontend/tests/product-detail.spec.ts",
                "frontend/tests/product-route.spec.ts")) {
            assertThat(files).as("asset file: " + target)
                    .anyMatch(f -> f.target().equals(target));
        }
        assertThat(repo.assetFiles("frontend-product-reference")).hasSize(10);
    }

    // 2. product routes registered (loaded via glob by frontend-auth router)
    @Test
    void productRoutesRegistered() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("routes");
        generate(repo, epm, out);

        String router = Files.readString(out.resolve("frontend/src/router/index.ts"), StandardCharsets.UTF_8);
        assertThat(router).contains("import.meta.glob('../router/product.ts', { eager: true })")
                .contains("...productRoutes");

        String product = Files.readString(out.resolve("frontend/src/router/product.ts"), StandardCharsets.UTF_8);
        for (String route : List.of("/products", "/products/:id", "/products/:id/edit")) {
            assertThat(product).as("route " + route).contains(route);
        }
    }

    // 3. request-client-only: product api uses platform client; views have no fetch/axios
    @Test
    void requestClientOnly() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("http");
        generate(repo, epm, out);

        String api = Files.readString(out.resolve("frontend/src/api/product.ts"), StandardCharsets.UTF_8);
        assertThat(api).contains("import { http } from './request'");
        for (String line : api.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            assertThat(t).as("no raw http in product.ts")
                    .doesNotContain("fetch(").doesNotContain("axios");
        }
        try (var stream = Files.walk(out.resolve("frontend/src/views/product"))) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                assertThat(text).as("no fetch in " + f.getFileName())
                        .doesNotContain("fetch(").doesNotContain("axios");
            }
        }
    }

    // 4. dictionary-driven status (product_status consumed via DictionarySelect)
    @Test
    void dictionaryDrivenStatus() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("dict");
        generate(repo, epm, out);

        String listView = Files.readString(
                out.resolve("frontend/src/views/product/ProductListView.vue"), StandardCharsets.UTF_8);
        assertThat(listView).contains("DictionarySelect")
                .contains("code=\"product_status\"")
                .contains("code=\"product_category\"");
        // no hardcoded ENABLED/DISABLED as business option source
        assertThat(listView).doesNotContain("<el-option label=\"Enabled\"");
        // seed must define the product_status dictionary
        String productSeed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zzz-product.sql"), StandardCharsets.UTF_8);
        assertThat(productSeed).contains("product_status").contains("'ENABLED'").contains("'DISABLED'");
        String dictSeed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zz-dictionary.sql"), StandardCharsets.UTF_8);
        assertThat(dictSeed).doesNotContain("product_status"); // belongs to product seed, no duplicate
    }

    // 5. permission integration on product actions
    @Test
    void permissionIntegration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("perm");
        generate(repo, epm, out);

        String list = Files.readString(
                out.resolve("frontend/src/views/product/ProductListView.vue"), StandardCharsets.UTF_8);
        assertThat(list).contains("PermissionButton")
                .contains("permission=\"product:item:create\"")
                .contains("permission=\"product:item:disable\"");
        String detail = Files.readString(
                out.resolve("frontend/src/views/product/ProductDetailView.vue"), StandardCharsets.UTF_8);
        assertThat(detail).contains("permission=\"product:item:update\"")
                .contains("permission=\"product:item:disable\"");
    }

    // 6. backend filter enhancement (keyword/status/category)
    @Test
    void backendFilterEnhancement() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("filter");
        generate(repo, epm, out);

        String controller = Files.readString(
                out.resolve("src/main/java/com/acme/core/api/product/ProductController.java"), StandardCharsets.UTF_8);
        assertThat(controller).contains("String keyword").contains("String status").contains("String category")
                .contains("pageFiltered");
        String port = Files.readString(
                out.resolve("src/main/java/com/acme/core/application/product/ProductPort.java"), StandardCharsets.UTF_8);
        assertThat(port).contains("findPageByScopeFiltered");
    }

    // 7. menu seed: /products aligned with route
    @Test
    void menuSeedAligned() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("menu");
        generate(repo, epm, out);
        String seed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zz-menu.sql"), StandardCharsets.UTF_8);
        assertThat(seed).contains("'/products'");
    }

    // 8. conformance PASS
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

    // 10. WORK-005 acceptance: generated project product tests + frontend test/build
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void generatedProjectAcceptance() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("real");
        AssetProjectGenerator.GenerationResult generated = generate(repo, epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);

        // backend: product-related tests only (WORK-005 budget)
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "test",
                "-Dtest=ProductHttpE2ETest,ProductDataScopeE2ETest,EnterpriseCompositionE2ETest,ProductModelUnitTest,DictionaryE2ETest",
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
                .as("generated backend product tests must pass:\n%s", output)
                .isEqualTo(0);
        // product-related suites must have run: surefire reports exist with 0 failures
        for (String suite : List.of("com.acme.core.ProductHttpE2ETest",
                "com.acme.core.ProductDataScopeE2ETest",
                "com.acme.core.EnterpriseCompositionE2ETest")) {
            Path report = out.resolve("target/surefire-reports/" + suite + ".txt");
            assertThat(Files.exists(report)).as(suite + " report exists").isTrue();
            String text = Files.readString(report, StandardCharsets.UTF_8);
            assertThat(text).as(suite + " ran").contains("Tests run:");
            assertThat(text).as(suite + " no failures").contains("Failures: 0");
        }

        // frontend: full test + build (once, per budget) — install once first
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
