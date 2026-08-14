package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V03-WORK-001 — Project Manifest usability tests.
 * project.yaml is the only project-level generation input; identity/configuration
 * flow manifest -> EPM -> generation without requiring Java Options from developers.
 */
class ProjectManifestUsabilityTest {

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
    private static Map<String, Object> readManifest(String relativePath) throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(repoRoot().resolve(relativePath), StandardCharsets.UTF_8));
    }

    private static EffectiveProjectModel resolve(Map<String, Object> project) throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        ResolutionResult result = new AssetAwareResolver(ALWAYS_VALID)
                .resolve(repo, readManifest("platform.yaml"), project);
        if (result.status() != ResolutionResult.Status.SUCCESS) {
            throw new IllegalStateException("expected resolution success: " + result.errors());
        }
        return result.effectiveProject();
    }

    private static Map<String, Object> projectWith(Map<String, Object> extraProjectFields) {
        Map<String, Object> project = new java.util.LinkedHashMap<>(Map.of(
                "id", "demo", "name", "demo", "version", "1.0.0",
                "basePackage", "com.engineeringplatform.demo"));
        project.putAll(extraProjectFields);
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("project", project);
        manifest.put("platform", Map.of("id", "engineering-platform"));
        manifest.put("capabilities", List.of(Map.of("id", "web"), Map.of("id", "validation"),
                Map.of("id", "exception-handling"), Map.of("id", "audit")));
        manifest.put("quality", Map.of("minimum", "Q3"));
        return manifest;
    }

    // 1. explicit groupId

    @Test
    void explicitGroupId() throws Exception {
        Map<String, Object> project = projectWith(Map.of("groupId", "com.acme.corp"));
        EffectiveProjectModel epm = resolve(project);
        assertThat(epm.identity()).containsEntry("groupId", "com.acme.corp");

        AssetProjectGenerator.Options options = AssetProjectGenerator.Options.fromEpm(epm);
        assertThat(options.groupId()).isEqualTo("com.acme.corp");
    }

    // 2. derived groupId (first two segments of basePackage)

    @Test
    void derivedGroupId() throws Exception {
        EffectiveProjectModel epm = resolve(projectWith(Map.of()));
        assertThat(epm.identity()).doesNotContainKey("groupId");

        AssetProjectGenerator.Options options = AssetProjectGenerator.Options.fromEpm(epm);
        assertThat(options.groupId()).isEqualTo("com.engineeringplatform");
    }

    // 3. explicit artifactId

    @Test
    void explicitArtifactId() throws Exception {
        Map<String, Object> project = projectWith(Map.of("artifactId", "order-api"));
        EffectiveProjectModel epm = resolve(project);
        assertThat(AssetProjectGenerator.Options.fromEpm(epm).artifactId()).isEqualTo("order-api");
    }

    // 4. default artifactId = project id

    @Test
    void defaultArtifactIdEqualsId() throws Exception {
        EffectiveProjectModel epm = resolve(projectWith(Map.of()));
        assertThat(AssetProjectGenerator.Options.fromEpm(epm).artifactId()).isEqualTo("demo");
    }

    // 5. explicit version

    @Test
    void explicitVersion() throws Exception {
        Map<String, Object> project = projectWith(Map.of("version", "3.2.1"));
        EffectiveProjectModel epm = resolve(project);
        assertThat(AssetProjectGenerator.Options.fromEpm(epm).projectVersion()).isEqualTo("3.2.1");
    }

    // 6. default version = stable default

    @Test
    void defaultVersion() throws Exception {
        Map<String, Object> project = projectWith(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> projectNode = (Map<String, Object>) project.get("project");
        projectNode.remove("version"); // schema-required in real validation; generation keeps a stable default
        EffectiveProjectModel epm = resolve(project);
        assertThat(AssetProjectGenerator.Options.fromEpm(epm).projectVersion()).isEqualTo("0.1.0");
    }

    // 7. configuration propagation into generated application.yml

    @Test
    void configurationPropagation() throws Exception {
        Map<String, Object> project = projectWith(Map.of(
                "groupId", "com.acme", "artifactId", "inventory-service", "version", "2.1.0",
                "basePackage", "com.acme.inventory",
                "configuration", Map.of("server.port", 9090,
                        "mybatis-plus.mapper-locations", "classpath*:mapper/inventory/**/*.xml")));
        EffectiveProjectModel epm = resolve(project);
        Path out = tempDir.resolve("out-cfg");
        Files.createDirectories(out);
        new AssetProjectGenerator().generate(epm, AssetRepository.load(repoRoot()), out);

        String yml = Files.readString(out.resolve("src/main/resources/application.yml"));
        assertThat(yml).contains("server.port: 9090");
        assertThat(yml).contains("mybatis-plus.mapper-locations: classpath*:mapper/inventory/**/*.xml");
        assertThat(yml).contains("spring.application.name: demo");
    }

    // 8. invalid Maven coordinates rejected by real schema validation

    @Test
    void invalidMavenCoordinatesRejected() throws Exception {
        ManifestValidationPort real = new ManifestRuntimeValidator();
        Map<String, Object> badGroup = projectWith(Map.of("groupId", "Bad_Group!"));
        assertThat(real.isValid("project", badGroup)).isFalse();

        Map<String, Object> badArtifact = projectWith(Map.of("artifactId", "Bad Artifact"));
        assertThat(real.isValid("project", badArtifact)).isFalse();
    }

    // 9. V0.2 reference manifest backward compatibility (no new fields -> defaults)

    @Test
    void v02ManifestBackwardCompatible() throws Exception {
        Map<String, Object> project = readManifest("tests/fixtures/v02-reference/project.yaml");
        EffectiveProjectModel epm = resolve(project);

        AssetProjectGenerator.Options options = AssetProjectGenerator.Options.fromEpm(epm);
        assertThat(options.artifactId()).isEqualTo("demo-order-service");   // default = id
        assertThat(options.groupId()).isEqualTo("com.engineeringplatform"); // derived from basePackage
        assertThat(options.projectVersion()).isEqualTo("1.0.0");
        assertThat(options.basePackage()).isEqualTo("com.engineeringplatform.demoorderservice");
        assertThat(options.providedConfig()).isEmpty();
    }

    // 10. inventory-service parameters do not leak demo defaults

    @Test
    void inventoryServiceParametersIndependent() throws Exception {
        Map<String, Object> project = readManifest("tests/fixtures/v03-reference/inventory-service/project.yaml");
        EffectiveProjectModel epm = resolve(project);

        AssetProjectGenerator.Options options = AssetProjectGenerator.Options.fromEpm(epm);
        assertThat(options.artifactId()).isEqualTo("inventory-service");
        assertThat(options.groupId()).isEqualTo("com.acme");
        assertThat(options.projectVersion()).isEqualTo("2.1.0");
        assertThat(options.basePackage()).isEqualTo("com.acme.inventory");
        assertThat(options.projectName()).isEqualTo("Inventory Service");
        assertThat(options.providedConfig()).containsEntry("server.port", 9090);
        assertThat(options.providedConfig().toString()).doesNotContain("demo");
        assertThat(options.groupId()).doesNotContain("engineeringplatform");
    }

    // 11. manifest-driven generation entry works without Java Options (inventory)

    @Test
    void manifestDrivenGenerationWithoutOptions() throws Exception {
        Map<String, Object> project = readManifest("tests/fixtures/v03-reference/inventory-service/project.yaml");
        EffectiveProjectModel epm = resolve(project);
        Path out = tempDir.resolve("out-inv");
        Files.createDirectories(out);

        AssetProjectGenerator.GenerationResult result =
                new AssetProjectGenerator().generate(epm, AssetRepository.load(repoRoot()), out);
        assertThat(result.plan()).isNotNull();
        assertThat(Files.exists(out.resolve("pom.xml"))).isTrue();
        String pom = Files.readString(out.resolve("pom.xml"));
        assertThat(pom).contains("<groupId>com.acme</groupId>");
        assertThat(pom).contains("<artifactId>inventory-service</artifactId>");
        assertThat(pom).contains("<version>2.1.0</version>");
        assertThat(pom).contains("<java.version>25</java.version>");
        assertThat(Files.exists(out.resolve(
                "src/main/java/com/acme/inventory/InventoryServiceApplication.java"))).isTrue();
    }
}
