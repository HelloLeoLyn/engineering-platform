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
 * V05-WORK-006 — API Boundary ID String Contract.
 *
 * EP-side regression: every domain ID crossing the JSON boundary must be
 * serialized as a String (Snowflake IDs exceed JS Number.MAX_SAFE_INTEGER).
 * Checks that generated Response DTOs / entities carry ToStringSerializer on
 * ID fields, request DTOs accept String IDs (LongIdDeserializer), and the
 * generated E2E round-trips a > MAX_SAFE_INTEGER id end-to-end.
 */
class IdStringContractWork006Test {

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

    // 1. Response DTOs: ID fields serialized as String (ToStringSerializer)
    @Test
    void responseDtosSerializeIdsAsString() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("resp");
        generate(repo, epm, out);

        // each entity/response ID field must carry ToStringSerializer
        String[] files = {
                "src/main/java/com/acme/core/api/auth/LoginResponse.java",
                "src/main/java/com/acme/core/api/user/CurrentUserResponse.java",
                "src/main/java/com/acme/core/application/rbac/UserResponse.java",
                "src/main/java/com/acme/core/application/rbac/RoleResponse.java",
                "src/main/java/com/acme/core/application/product/ProductResponse.java",
                "src/main/java/com/acme/core/domain/entity/SysDepartment.java",
                "src/main/java/com/acme/core/domain/entity/SysMenu.java",
                "src/main/java/com/acme/core/domain/entity/DictionaryType.java",
                "src/main/java/com/acme/core/domain/entity/DictionaryItem.java",
                "src/main/java/com/acme/core/domain/entity/SysOperationLog.java",
        };
        for (String f : files) {
            String text = Files.readString(out.resolve(f), StandardCharsets.UTF_8);
            assertThat(text).as(f + " imports ToStringSerializer")
                    .contains("import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;");
            assertThat(text).as(f + " uses @JsonSerialize on ids")
                    .contains("@JsonSerialize(using = ToStringSerializer.class)");
        }
        // list-typed ids use contentUsing
        String userResp = Files.readString(
                out.resolve("src/main/java/com/acme/core/application/rbac/UserResponse.java"), StandardCharsets.UTF_8);
        assertThat(userResp).contains("@JsonSerialize(contentUsing = ToStringSerializer.class)");
    }

    // 2. Request DTOs: String IDs accepted (LongIdDeserializer)
    @Test
    void requestDtosAcceptStringIds() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("req");
        generate(repo, epm, out);
        for (String f : List.of(
                "src/main/java/com/acme/core/application/rbac/UserCreateRequest.java",
                "src/main/java/com/acme/core/application/rbac/UserUpdateRequest.java",
                "src/main/java/com/acme/core/application/rbac/RoleCreateRequest.java",
                "src/main/java/com/acme/core/application/rbac/RoleUpdateRequest.java")) {
            String text = Files.readString(out.resolve(f), StandardCharsets.UTF_8);
            assertThat(text).as(f + " uses LongIdDeserializer")
                    .contains("LongIdDeserializer");
        }
        assertThat(Files.exists(out.resolve(
                "src/main/java/com/acme/core/common/core/LongIdDeserializer.java"))).isTrue();
    }

    // 3. Frontend: EntityId = string everywhere for domain ids; metrics stay number
    @Test
    void frontendEntityIdString() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("fe");
        generate(repo, epm, out);
        String types = Files.readString(out.resolve("frontend/src/types/enterprise.ts"), StandardCharsets.UTF_8);
        assertThat(types).contains("export type EntityId = string;");
        assertThat(types).contains("id: EntityId;").contains("departmentId: EntityId | null;")
                .contains("roleIds: EntityId[];").contains("parentId: EntityId | null;")
                .contains("typeId: EntityId;");
        // numeric metrics stay number
        assertThat(types).contains("total: number;").contains("sort: number;");
        String productTypes = Files.readString(out.resolve("frontend/src/types/product.ts"), StandardCharsets.UTF_8);
        assertThat(productTypes).contains("id: EntityId;").contains("createdBy: EntityId | null;");
    }

    // 4. E2E round-trip: > MAX_SAFE_INTEGER id through create/detail/update/disable
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void snowflakeIdRoundTrip() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        EffectiveProjectModel epm = resolveV05(repo).effectiveProject();
        Path out = tempDir.resolve("rt");
        AssetProjectGenerator.GenerationResult generated = generate(repo, epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ConformanceResult conformance = new ConformanceValidator(repo).validate(epm, out);
        assertThat(conformance.status()).isEqualTo(ConformanceResult.Status.PASS);

        // ProductHttpE2ETest round-trips the created id through detail/update/disable
        // (the generated E2E suite below is the real proof; this is a sanity check)
        String test = Files.readString(
                out.resolve("src/test/java/com/acme/core/ProductHttpE2ETest.java"), StandardCharsets.UTF_8);
        assertThat(test).as("E2E exercises detail with created id").contains("/api/products/");
        // run the product E2E suite (create -> detail/update/disable with string id)
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "test",
                "-Dtest=ProductHttpE2ETest,ProductDataScopeE2ETest", "-Dsurefire.failIfNoSpecifiedTests=false"));
        if (Files.exists(settings)) {
            cmd.add(2, "-s");
            cmd.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(600, TimeUnit.SECONDS);
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue())
                .as("product E2E must pass:\n%s", new String(output, StandardCharsets.UTF_8))
                .isEqualTo(0);
    }
}
