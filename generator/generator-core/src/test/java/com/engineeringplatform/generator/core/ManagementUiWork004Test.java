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
 * V05-WORK-004 — Enterprise Management UI.
 *
 * EP-side tests: asset validation (views/routes/components), route registry,
 * request-client-only rule, permission integration, menu seed alignment,
 * conformance, determinism, generated project tests + frontend test/build.
 */
class ManagementUiWork004Test {

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

    // 1. asset validation: views + reusable UI + routes + tests
    @Test
    void managementUiAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<AssetRepository.AssetFileSpec> files = repo.assetFiles("frontend-enterprise-management");
        for (String target : List.of(
                "frontend/src/views/system/UserManagementView.vue",
                "frontend/src/views/system/RoleManagementView.vue",
                "frontend/src/views/system/PermissionRegistryView.vue",
                "frontend/src/views/system/DepartmentManagementView.vue",
                "frontend/src/views/system/MenuManagementView.vue",
                "frontend/src/views/system/DictionaryManagementView.vue",
                "frontend/src/views/system/OperationLogView.vue",
                "frontend/src/components/AppTable.vue",
                "frontend/src/components/AppForm.vue",
                "frontend/src/components/SearchForm.vue",
                "frontend/src/components/StatusTag.vue",
                "frontend/src/components/ConfirmAction.vue",
                "frontend/src/components/FormDrawer.vue",
                "frontend/src/components/DepartmentTree.vue",
                "frontend/src/components/DictionarySelect.vue",
                "frontend/src/router/enterprise.ts",
                "frontend/src/api/enterprise.ts",
                "frontend/src/types/enterprise.ts")) {
            assertThat(files).as("asset file: " + target)
                    .anyMatch(f -> f.target().equals(target));
        }
        assertThat(repo.assetFiles("frontend-enterprise-management")).hasSize(28);
    }

    // 2. routes: /system/* registered in frontend-auth router via enterprise routes
    @Test
    void routeRegistry() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("routes");
        generate(repo, epm, out);

        String router = Files.readString(out.resolve("frontend/src/router/index.ts"), StandardCharsets.UTF_8);
        assertThat(router).contains("import { enterpriseRoutes } from './enterprise'")
                .contains("...enterpriseRoutes");

        String enterprise = Files.readString(out.resolve("frontend/src/router/enterprise.ts"), StandardCharsets.UTF_8);
        for (String route : List.of(
                "/system/users", "/system/roles", "/system/permissions",
                "/system/departments", "/system/menus", "/system/dictionaries",
                "/system/operation-logs")) {
            assertThat(enterprise).as("route " + route).contains(route);
        }
    }

    // 3. request-client-only rule: no raw axios/fetch in management code
    @Test
    void requestClientOnly() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("http");
        generate(repo, epm, out);

        try (var stream = Files.walk(out.resolve("frontend/src/views/system"))) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                assertThat(text).as("no fetch in " + f.getFileName())
                        .doesNotContain("fetch(")
                        .doesNotContain("axios");
            }
        }
        // api service uses platform request client (import check; comment mentions are fine)
        String api = Files.readString(out.resolve("frontend/src/api/enterprise.ts"), StandardCharsets.UTF_8);
        assertThat(api).contains("import { http } from './request'");
        for (String line : api.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            assertThat(t).as("no raw http in enterprise.ts")
                    .doesNotContain("fetch(")
                    .doesNotContain("axios");
        }
    }

    // 4. permission integration: PermissionButton used in views, no hardcoded auth
    @Test
    void permissionIntegration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("perm");
        generate(repo, epm, out);

        for (String view : List.of("UserManagementView.vue", "RoleManagementView.vue",
                "DepartmentManagementView.vue", "MenuManagementView.vue",
                "DictionaryManagementView.vue")) {
            String text = Files.readString(
                    out.resolve("frontend/src/views/system/" + view), StandardCharsets.UTF_8);
            assertThat(text).as(view + " uses PermissionButton").contains("PermissionButton");
        }
        // no token literals / localStorage access in views
        for (String view : List.of("UserManagementView.vue", "RoleManagementView.vue",
                "PermissionRegistryView.vue", "OperationLogView.vue")) {
            String text = Files.readString(
                    out.resolve("frontend/src/views/system/" + view), StandardCharsets.UTF_8);
            assertThat(text).as(view + " no localStorage").doesNotContain("localStorage");
        }
    }

    // 5. menu seed alignment: paths match /system/* routes
    @Test
    void menuSeedAlignedWithRoutes() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("seed");
        generate(repo, epm, out);
        String seed = Files.readString(out.resolve("src/test/resources/db/seed/seed-zz-menu.sql"),
                StandardCharsets.UTF_8);
        for (String path : List.of(
                "'/system/users'", "'/system/roles'", "'/system/permissions'",
                "'/system/departments'", "'/system/menus'", "'/system/dictionaries'",
                "'/system/operation-logs'")) {
            assertThat(seed).as("menu seed contains " + path).contains(path);
        }
        // permission codes reference existing registry codes
        assertThat(seed).contains("system:user:read").contains("system:role:read")
                .contains("system:permission:read").contains("system:department:read")
                .contains("system:menu:read").contains("system:dictionary:read")
                .contains("system:operation-log:read");
    }

    // 6. conformance PASS
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

    // 7. deterministic
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

    // 8. V0.4 regression: backend-only fixture stays frontend-free
    @Test
    void v04Regression() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Map<String, Object> project = (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v04-reference/platform-core/project.yaml"), StandardCharsets.UTF_8));
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), project);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolution.effectiveProject();
        Path out = tempDir.resolve("v04");
        generate(repo, epm, out);
        assertThat(Files.exists(out.resolve("frontend"))).isFalse();
    }

    // 9-10. generated project: backend tests + frontend test/build (WORK-004 acceptance)
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

        // backend: full mvn test (includes ManagementE2ETest + MenuE2ETest + DictionaryE2ETest)
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "test"));
        if (Files.exists(settings)) {
            cmd.add(2, "-s");
            cmd.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(900, TimeUnit.SECONDS);
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue())
                .as("generated backend mvn test must pass:\n%s", output)
                .isEqualTo(0);
        assertThat(output).as("ManagementE2ETest ran").contains("ManagementE2ETest");
        assertThat(output).as("MenuE2ETest ran (menu seed alignment)").contains("MenuE2ETest");

        // frontend: pnpm test + pnpm build
        Path frontend = out.resolve("frontend");
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
