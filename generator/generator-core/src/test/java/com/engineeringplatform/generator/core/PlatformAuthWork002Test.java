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
 * V04-WORK-002 — Authentication + User/Role/Permission.
 *
 * Covers the spec acceptance points:
 *   asset validation / registry consistency / resolver closure / EPM assets /
 *   generation files / migrations / configuration / conformance / determinism /
 *   no drift / failure safety / generated project tests / generated project boot /
 *   real authentication & RBAC E2E.
 */
class PlatformAuthWork002Test {

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

    // ---- 1. Asset validation ----

    @Test
    void assetValidation() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capability("authentication")).as("authentication asset must load").isNotNull();
        assertThat(repo.capability("rbac")).as("rbac asset must load").isNotNull();
        // authentication: 11 files; rbac: 21 files (incl. UserQueryPort + MybatisUserQueryRepository)
        assertThat(repo.assetFiles("authentication")).hasSize(11);
        assertThat(repo.assetFiles("rbac")).hasSize(21);
        // dependencies declared
        assertThat(repo.capability("authentication").dependencies())
                .anyMatch(d -> d.id().equals("rbac") && d.required());
        assertThat(repo.capability("rbac").dependencies())
                .anyMatch(d -> d.id().equals("persistence") && d.required());
    }

    // ---- 2. Registry consistency ----

    @Test
    void registryConsistency() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities().keySet())
                .contains("platform-core", "authentication", "rbac");
    }

    // ---- 3. Resolver dependency closure ----

    @Test
    void resolverDependencyClosure() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        assertThat(resolution.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolution.effectiveProject();
        // explicit + auto-added closure
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("authentication"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("rbac"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("platform-core"));
        assertThat(epm.capabilities()).anyMatch(c -> c.id().equals("persistence")); // auto-added by rbac
        assertThat(epm.providers()).anyMatch(p -> p.id().equals("mybatis-plus"));
    }

    // ---- 4. EPM assets ----

    @Test
    void epmAssets() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        List<String> ids = resolution.effectiveProject().capabilities().stream()
                .map(c -> c.id()).toList();
        assertThat(ids).contains("authentication", "rbac", "platform-core", "persistence");
    }

    // ---- 5. Generation files + migrations + configuration ----

    @Test
    void generationFilesMigrationsConfiguration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("gen");
        Files.createDirectories(out);
        AssetProjectGenerator.GenerationResult generated =
                new AssetProjectGenerator().generate(epm, repo, options(), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        String main = "src/main/java/com/acme/core/";
        // authentication components
        for (String f : List.of("common/security/TokenService.java", "common/security/PasswordEncoder.java",
                "common/security/SecurityErrorCodes.java", "api/auth/AuthController.java",
                "infrastructure/security/AuthInterceptor.java", "infrastructure/security/SecurityWebConfig.java",
                "infrastructure/security/SecurityExceptionHandler.java")) {
            assertThat(Files.exists(out.resolve(main + f))).as("auth file: " + f).isTrue();
        }
        // rbac components
        for (String f : List.of("domain/entity/SysUser.java", "domain/entity/SysRole.java",
                "domain/entity/SysPermission.java", "infrastructure/persistence/mapper/SysUserMapper.java",
                "application/rbac/UserQueryPort.java", "application/rbac/UserQueryService.java",
                "infrastructure/persistence/MybatisUserQueryRepository.java",
                "common/security/RequirePermission.java",
                "infrastructure/security/PermissionAspect.java", "api/user/UserController.java")) {
            assertThat(Files.exists(out.resolve(main + f))).as("rbac file: " + f).isTrue();
        }
        // migration
        Path migration = out.resolve("src/main/resources/db/migration/V002__rbac.sql");
        assertThat(Files.exists(migration)).as("migration V002__rbac.sql").isTrue();
        String migrationText = Files.readString(migration, StandardCharsets.UTF_8);
        for (String table : List.of("sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission")) {
            assertThat(migrationText).as("migration contains table " + table).contains("CREATE TABLE IF NOT EXISTS " + table);
        }
        assertThat(migrationText).contains("PRIMARY KEY").contains("UNIQUE").contains("FOREIGN KEY");
        // test profile config + seed
        assertThat(Files.exists(out.resolve("src/test/resources/application-test.yml"))).isTrue();
        assertThat(Files.exists(out.resolve("src/test/resources/db/seed/seed-test-data.sql"))).isTrue();
        // secret placeholder, never plaintext in main config
        String appYml = Files.readString(out.resolve("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertThat(appYml).contains("auth.token.secret: ${AUTH_TOKEN_SECRET}");
        assertThat(appYml).doesNotContain("secret-key:");
    }

    // ---- 6. Conformance PASS ----

    @Test
    void conformancePass() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("conf");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(epm, repo, options(), out);

        ConformanceResult result = new ConformanceValidator(repo).validate(epm, out);
        assertThat(result.status()).as("conformance must PASS:\n%s", result.summary())
                .isEqualTo(ConformanceResult.Status.PASS);
    }

    // ---- 7. Determinism ----

    @Test
    void deterministicGeneration() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out1 = tempDir.resolve("det1");
        Path out2 = tempDir.resolve("det2");
        Files.createDirectories(out1);
        Files.createDirectories(out2);
        new AssetProjectGenerator().generate(epm, repo, options(), out1);
        new AssetProjectGenerator().generate(epm, repo, options(), out2);

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

    // ---- 8. No drift on repeated generation ----

    @Test
    void repeatedGenerationNoDrift() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("drift");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(epm, repo, options(), out);

        Map<Path, byte[]> before = new java.util.LinkedHashMap<>();
        try (var stream = Files.walk(out)) {
            for (Path f : stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".generator")).toList()) {
                before.put(out.relativize(f), Files.readAllBytes(f));
            }
        }
        new AssetProjectGenerator().generate(epm, repo, options(), out);
        for (Map.Entry<Path, byte[]> e : before.entrySet()) {
            assertThat(Files.readAllBytes(out.resolve(e.getKey())))
                    .as("no drift: " + e.getKey()).isEqualTo(e.getValue());
        }
    }

    // ---- 9. Failure safety: missing auth secret must not generate plaintext ----

    @Test
    void secretNeverPlaintextInGeneratedSource() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("secret");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(epm, repo, options(), out);

        // walk all generated text files; no real-looking secret values
        try (var stream = Files.walk(out)) {
            for (Path f : stream.filter(Files::isRegularFile).toList()) {
                if (!f.toString().endsWith(".java") && !f.toString().endsWith(".yml")
                        && !f.toString().endsWith(".yaml") && !f.toString().endsWith(".sql")) {
                    continue;
                }
                String text = Files.readString(f, StandardCharsets.UTF_8);
                assertThat(text).as("no hardcoded secret pattern in " + out.relativize(f))
                        .doesNotContain("secret-key: abc123")
                        .doesNotContain("tokenSecret = \"");
            }
        }
    }

    // ---- 10. Generated project tests + boot + real auth/RBAC E2E ----

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void realGeneratedProjectAuthRbacE2E() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult resolution =
                new AssetAwareResolver(ALWAYS_VALID).resolve(repo, realPlatform(), v04Project());
        EffectiveProjectModel epm = resolution.effectiveProject();

        Path out = tempDir.resolve("real");
        Files.createDirectories(out);
        AssetProjectGenerator.GenerationResult generated =
                new AssetProjectGenerator().generate(epm, repo, options(), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        // conformance PASS before boot
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);

        // boot + real HTTP E2E via generated AuthRbacE2ETest (SpringBootTest RANDOM_PORT + TestRestTemplate)
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
        assertThat(output).as("AuthRbacE2ETest must have run").contains("AuthRbacE2ETest");
    }

    // ---- 11. V0.3 regression ----

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
        assertThat(epm.capabilities()).noneMatch(c -> c.id().equals("authentication"));

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
