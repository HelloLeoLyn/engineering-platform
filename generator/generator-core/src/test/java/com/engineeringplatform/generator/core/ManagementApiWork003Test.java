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
 * V05-WORK-003 — Enterprise Management APIs.
 *
 * EP-side tests: asset validation, resolver/EPM, generated API files,
 * permission codes, op-log integration, MyBatis leakage, conformance,
 * determinism, no drift, generated project tests (incl. ManagementE2E),
 * V0.4 regression.
 */
class ManagementApiWork003Test {

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

    // 1. asset validation (management files present in owning assets)
    @Test
    void managementAssetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<AssetRepository.AssetFileSpec> rbac = repo.assetFiles("rbac");
        assertThat(rbac).anyMatch(f -> f.target().endsWith("api/user/UserManagementController.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("api/role/RoleManagementController.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("api/permission/PermissionController.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("application/rbac/UserManagementService.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("application/rbac/UserManagementPort.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("infrastructure/persistence/MybatisUserManagementRepository.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("application/rbac/RoleManagementService.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("application/rbac/RoleManagementPort.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("infrastructure/persistence/MybatisRoleManagementRepository.java"));
        assertThat(rbac).anyMatch(f -> f.target().endsWith("ManagementE2ETest.java"));

        List<AssetRepository.AssetFileSpec> org = repo.assetFiles("organization");
        assertThat(org).anyMatch(f -> f.target().endsWith("DepartmentManagementController.java"));
        assertThat(org).anyMatch(f -> f.target().endsWith("application/organization/DepartmentManagementService.java"));
        assertThat(org).anyMatch(f -> f.target().endsWith("application/organization/DepartmentManagementPort.java"));
        assertThat(org).anyMatch(f -> f.target().endsWith("infrastructure/persistence/MybatisDepartmentManagementRepository.java"));

        List<AssetRepository.AssetFileSpec> menu = repo.assetFiles("menu");
        assertThat(menu).anyMatch(f -> f.target().endsWith("MenuManagementController.java"));
        assertThat(menu).anyMatch(f -> f.target().endsWith("application/menu/MenuManagementService.java"));
        assertThat(menu).anyMatch(f -> f.target().endsWith("application/menu/MenuManagementPort.java"));
        assertThat(menu).anyMatch(f -> f.target().endsWith("infrastructure/persistence/MybatisMenuManagementRepository.java"));

        List<AssetRepository.AssetFileSpec> dict = repo.assetFiles("dictionary");
        assertThat(dict).anyMatch(f -> f.target().endsWith("DictionaryManagementController.java"));
        assertThat(dict).anyMatch(f -> f.target().endsWith("application/dictionary/DictionaryManagementService.java"));
        assertThat(dict).anyMatch(f -> f.target().endsWith("application/dictionary/DictionaryManagementPort.java"));
        assertThat(dict).anyMatch(f -> f.target().endsWith("infrastructure/persistence/MybatisDictionaryManagementRepository.java"));
    }

    // 2. resolver/EPM
    @Test
    void resolverEpm() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution = resolveV05(repo);
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolution.effectiveProject();
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("rbac"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("organization"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("menu"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("dictionary"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("operation-log"));
    }

    // 3. generated API files
    @Test
    void generatedApiFiles() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("gen");
        generate(repo, epm, out);
        for (String f : List.of(
                "src/main/java/com/acme/core/api/user/UserManagementController.java",
                "src/main/java/com/acme/core/api/role/RoleManagementController.java",
                "src/main/java/com/acme/core/api/permission/PermissionController.java",
                "src/main/java/com/acme/core/api/organization/DepartmentManagementController.java",
                "src/main/java/com/acme/core/api/menu/MenuManagementController.java",
                "src/main/java/com/acme/core/api/dictionary/DictionaryManagementController.java",
                "src/main/java/com/acme/core/api/operationlog/OperationLogReferenceController.java",
                "src/test/java/com/acme/core/ManagementE2ETest.java")) {
            assertThat(Files.exists(out.resolve(f))).as("file: " + f).isTrue();
        }
    }

    // 4. permission codes
    @Test
    void permissionCodes() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("perm");
        generate(repo, epm, out);
        String seed = Files.readString(out.resolve("src/test/resources/db/seed/seed-test-data.sql"),
                StandardCharsets.UTF_8);
        for (String code : List.of("system:user:create", "system:user:update", "system:user:disable",
                "system:user:assign-role", "system:role:create", "system:role:update",
                "system:role:assign-permission", "system:permission:read",
                "system:department:read", "system:menu:create", "system:dictionary:read",
                "system:operation-log:read")) {
            assertThat(seed).as("seed contains " + code).contains(code);
        }
        // controllers enforce permissions
        String userMgmt = Files.readString(out.resolve("src/main/java/com/acme/core/api/user/UserManagementController.java"),
                StandardCharsets.UTF_8);
        assertThat(userMgmt).contains("@RequirePermission(\"system:user:create\")")
                .contains("@OperationLog(operation = \"USER_CREATE\"");
    }

    // 5. op-log integration on management writes
    @Test
    void operationLogIntegration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("oplog");
        generate(repo, epm, out);
        for (String f : List.of(
                "src/main/java/com/acme/core/api/user/UserManagementController.java",
                "src/main/java/com/acme/core/api/role/RoleManagementController.java",
                "src/main/java/com/acme/core/api/organization/DepartmentManagementController.java",
                "src/main/java/com/acme/core/api/menu/MenuManagementController.java",
                "src/main/java/com/acme/core/api/dictionary/DictionaryManagementController.java")) {
            String text = Files.readString(out.resolve(f), StandardCharsets.UTF_8);
            assertThat(text).as(f + " has @OperationLog").contains("@OperationLog");
        }
    }

    // 6. MyBatis leakage (application/domain/api clean)
    @Test
    void mybatisLeakageScan() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
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
        // management services must live in application and stay MyBatis-free
        for (String svc : List.of(
                "src/main/java/com/acme/core/application/rbac/UserManagementService.java",
                "src/main/java/com/acme/core/application/rbac/RoleManagementService.java",
                "src/main/java/com/acme/core/application/rbac/PermissionManagementService.java",
                "src/main/java/com/acme/core/application/organization/DepartmentManagementService.java",
                "src/main/java/com/acme/core/application/menu/MenuManagementService.java",
                "src/main/java/com/acme/core/application/dictionary/DictionaryManagementService.java",
                "src/main/java/com/acme/core/application/operationlog/OperationLogQueryService.java")) {
            assertThat(Files.exists(out.resolve(svc))).as("app service exists: " + svc).isTrue();
            String text = Files.readString(out.resolve(svc), StandardCharsets.UTF_8);
            assertThat(text).as(svc + " free of QueryWrapper")
                    .doesNotContain("QueryWrapper")
                    .doesNotContain("BaseMapper")
                    .doesNotContain("com.baomidou");
        }
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

    // 8. deterministic
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

    // 9. no drift
    @Test
    void repeatedGenerationNoDrift() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
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

    // 10-11. generated project tests + management HTTP E2E + frontend compat + V0.4 regression
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void generatedProjectE2E() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("real");
        AssetProjectGenerator.GenerationResult generated = generate(repo, epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);

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
                .as("generated project mvn test must pass:\n%s", output)
                .isEqualTo(0);
        assertThat(output).as("ManagementE2ETest must have run").contains("ManagementE2ETest");

        // frontend still installs/tests/builds (users/me API unchanged)
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

    // 12. V0.4 regression (backend-only fixture unchanged)
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
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/api/user/UserManagementController.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/resources/db/migration/V008__product_reference.sql"))).isTrue();
    }
}
