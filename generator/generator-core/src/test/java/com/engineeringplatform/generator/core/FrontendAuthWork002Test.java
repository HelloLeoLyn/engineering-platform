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
 * V05-WORK-002 — Login + App Shell + Request + Router/Menu/Permission + UX Baseline.
 *
 * EP-side tests for spec points 1-8, 10-13, 15-22, 24-26; points 9/14/16-17/23
 * (login render/success/failure, token persistence, auth header, 401 logout,
 * 403 handling, router guard, menu mapping, permission guard, shell render,
 * direct HTTP violation, generated frontend test/build) are covered by the
 * generated frontend's Vitest suites, executed via generatedFrontendE2E().
 */
class FrontendAuthWork002Test {

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> v04Project() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v04-reference/platform-core/project.yaml"), StandardCharsets.UTF_8));
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.acme.core", "core-demo", "com.acme", "core-demo", "1.0.0", Map.of());
    }

    private static ResolutionResult resolveV05(AssetRepository repo) throws Exception {
        return new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v05Project());
    }

    private static ResolutionResult resolveV04(AssetRepository repo) throws Exception {
        return new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
    }

    private static AssetProjectGenerator.GenerationResult generate(AssetRepository repo,
                                                                   EffectiveProjectModel epm,
                                                                   Path out) throws Exception {
        Files.createDirectories(out);
        return new AssetProjectGenerator().generate(epm, repo, options(), out);
    }

    // 1. frontend-auth asset
    @Test
    void frontendAuthAsset() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        var asset = repo.capability("frontend-auth");
        assertThat(asset).isNotNull();
        assertThat(asset.dependencies())
                .anyMatch(d -> d.id().equals("frontend-shell") && d.required())
                .anyMatch(d -> d.id().equals("authentication") && d.required())
                .anyMatch(d -> d.id().equals("rbac") && d.required())
                .anyMatch(d -> d.id().equals("menu") && d.required());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("frontend-auth");
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/api/request.ts"));
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/stores/auth.ts"));
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/views/LoginView.vue"));
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/router/index.ts"));
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/layouts/AppLayout.vue"));
    }

    // 2. frontend-permission asset
    @Test
    void frontendPermissionAsset() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        var asset = repo.capability("frontend-permission");
        assertThat(asset).isNotNull();
        assertThat(asset.dependencies())
                .anyMatch(d -> d.id().equals("frontend-auth") && d.required())
                .anyMatch(d -> d.id().equals("rbac") && d.required());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("frontend-permission");
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/permission/PermissionGuard.vue"));
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/permission/PermissionButton.vue"));
        assertThat(files).anyMatch(f -> f.target().equals("frontend/src/components/PageContainer.vue"));
    }

    // 3. registry
    @Test
    void registryRegistration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities().keySet())
                .contains("frontend-shell", "frontend-auth", "frontend-permission");
    }

    // 4. resolver closure
    @Test
    void resolverClosure() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution = resolveV05(repo);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        List<String> ids = resolution.effectiveProject().capabilities().stream().map(c -> c.id()).toList();
        assertThat(ids).contains("frontend-shell", "frontend-auth", "frontend-permission",
                "authentication", "rbac", "menu");
    }

    // 5. EPM
    @Test
    void epmIncludesAssets() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("frontend-auth"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("frontend-permission"));
    }

    // 6. generation produces frontend app files
    @Test
    void generationProducesFrontendApp() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("gen");
        generate(repo, epm, out);
        for (String f : List.of(
                "frontend/src/main.ts", "frontend/src/App.vue",
                "frontend/src/api/request.ts", "frontend/src/api/auth.ts", "frontend/src/api/menu.ts",
                "frontend/src/stores/auth.ts",
                "frontend/src/router/index.ts", "frontend/src/router/guard.ts",
                "frontend/src/layouts/AppLayout.vue",
                "frontend/src/views/LoginView.vue", "frontend/src/views/HomeView.vue",
                "frontend/src/permission/PermissionGuard.vue", "frontend/src/permission/PermissionButton.vue",
                "frontend/src/components/PageContainer.vue", "frontend/src/components/PageHeader.vue",
                "frontend/src/components/StateViews.vue",
                "frontend/tests/auth.spec.ts", "frontend/tests/request.spec.ts",
                "frontend/tests/permission.spec.ts", "frontend/tests/smoke.spec.ts")) {
            assertThat(Files.exists(out.resolve(f))).as("file: " + f).isTrue();
        }
    }

    // 7-8. login render/success/failure -> covered by generated Vitest (auth.spec)
    // 9. token persistence -> auth.spec (localStorage)
    // 10. auth header -> request.spec
    // 11. 401 logout -> request.spec
    // 12. 403 handling -> request.spec
    // 13. router guard -> generated router/guard.ts exists + main wires it
    @Test
    void routerGuardWired() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("rg");
        generate(repo, epm, out);
        String guard = Files.readString(out.resolve("frontend/src/router/guard.ts"), StandardCharsets.UTF_8);
        assertThat(guard).contains("/login");
        assertThat(guard).contains("authenticated");
        String router = Files.readString(out.resolve("frontend/src/router/index.ts"), StandardCharsets.UTF_8);
        assertThat(router).contains("/login").contains("/403").contains("/404");
    }

    // 14. menu mapping -> generated api/menu.ts consumes /menus/me
    @Test
    void menuMappingConsumesBackend() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("mm");
        generate(repo, epm, out);
        String menu = Files.readString(out.resolve("frontend/src/api/menu.ts"), StandardCharsets.UTF_8);
        assertThat(menu).contains("/menus/me");
    }

    // 15. permission guard -> permission.spec covers; here assert files + wiring
    @Test
    void permissionWiring() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("pw");
        generate(repo, epm, out);
        String main = Files.readString(out.resolve("frontend/src/main.ts"), StandardCharsets.UTF_8);
        assertThat(main).contains("installPermissionDirective");
        String perm = Files.readString(out.resolve("frontend/src/permission/index.ts"), StandardCharsets.UTF_8);
        assertThat(perm).contains("hasAnyPermission");
    }

    // 16. shell render -> smoke.spec covers
    // 17. direct HTTP violation detection -> scan generated frontend business dirs
    @Test
    void directHttpViolationDetection() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("viol");
        generate(repo, epm, out);
        // business dirs must not call fetch/axios directly (request.ts is the only allowed place)
        for (String dir : List.of("views", "layouts", "components", "permission", "stores", "router")) {
            Path d = out.resolve("frontend/src/" + dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (var stream = Files.walk(d)) {
                for (Path f : stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".vue")).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    assertThat(text).as("no direct fetch/axios in " + dir + "/" + f.getFileName())
                            .doesNotContain("axios")
                            .doesNotContain("fetch(");
                }
            }
        }
        // request.ts is the single allowed HTTP entry
        String request = Files.readString(out.resolve("frontend/src/api/request.ts"), StandardCharsets.UTF_8);
        assertThat(request).contains("fetch(");
    }

    // 18. conformance
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

    // 19. deterministic
    @Test
    void deterministicGeneration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out1 = tempDir.resolve("det1");
        Path out2 = tempDir.resolve("det2");
        generate(repo, epm, out1);
        generate(repo, epm, out2);
        List<Path> files1;
        try (var stream = Files.walk(out1.resolve("frontend"))) {
            files1 = stream.filter(Files::isRegularFile).sorted().toList();
        }
        assertThat(files1).isNotEmpty();
        for (Path f1 : files1) {
            Path rel = out1.relativize(f1);
            assertThat(Files.exists(out2.resolve(rel))).as("same: " + rel).isTrue();
            assertThat(Files.readAllBytes(f1)).as("byte-identical: " + rel)
                    .isEqualTo(Files.readAllBytes(out2.resolve(rel)));
        }
    }

    // 20. no drift
    @Test
    void repeatedGenerationNoDrift() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("drift");
        generate(repo, epm, out);
        Map<Path, byte[]> before = new java.util.LinkedHashMap<>();
        try (var stream = Files.walk(out.resolve("frontend"))) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                before.put(out.relativize(f), Files.readAllBytes(f));
            }
        }
        generate(repo, epm, out);
        for (Map.Entry<Path, byte[]> e : before.entrySet()) {
            assertThat(Files.readAllBytes(out.resolve(e.getKey())))
                    .as("no drift: " + e.getKey()).isEqualTo(e.getValue());
        }
    }

    // 21. backend-only compatibility (no frontend without frontend assets)
    @Test
    void backendOnlyCompatibility() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV04(repo).effectiveProject();
        assertThat(epm.capabilities()).noneMatch(c -> c.id().startsWith("frontend-"));
        Path out = tempDir.resolve("v04");
        generate(repo, epm, out);
        assertThat(Files.exists(out.resolve("frontend"))).isFalse();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/api/user/CurrentUserResponse.java"))).isTrue();
    }

    // 22. generated frontend test/build + backend tests (real E2E)
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void generatedFrontendE2E() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("real");
        AssetProjectGenerator.GenerationResult generated = generate(repo, epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);

        Path frontend = out.resolve("frontend");
        assertThat(run(frontend, "pnpm", "install")).as("pnpm install").isEqualTo(0);
        assertThat(run(frontend, "pnpm", "test")).as("pnpm test").isEqualTo(0);
        assertThat(run(frontend, "pnpm", "build")).as("pnpm build").isEqualTo(0);

        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "test"));
        if (Files.exists(settings)) {
            cmd.add(2, "-s");
            cmd.add(3, settings.toString());
        }
        assertThat(run(out, cmd.toArray(String[]::new))).as("backend mvn test").isEqualTo(0);
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
