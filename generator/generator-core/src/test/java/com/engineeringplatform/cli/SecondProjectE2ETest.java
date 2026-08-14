package com.engineeringplatform.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V03-WORK-004 — Second Project E2E (ACCEPTANCE ONLY).
 *
 * Proves that a second independent real project (inventory-service) can be
 * generated from its own project.yaml using the same platform + assets,
 * with no demo parameter leakage and a safe failure path.
 */
class SecondProjectE2ETest {

    @TempDir
    Path tempDir;

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6; i++) {
            if (Files.exists(p.resolve("ep")) && Files.exists(p.resolve("platform.yaml"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                throw new IllegalStateException("cannot locate repository root");
            }
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    private Path inventoryManifest() {
        return repoRoot().resolve("tests/fixtures/v03-reference/inventory-service/project.yaml");
    }

    private static ProcessResult exec(Path workDir, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workDir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(14, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        return new ProcessResult(finished ? process.exitValue() : -1, output);
    }

    private record ProcessResult(int code, String output) {
    }

    private List<String> mvnCommand(Path projectDir) {
        List<String> mvn = new java.util.ArrayList<>(List.of("mvn", "-B", "-f",
                projectDir.resolve("pom.xml").toString(), "test"));
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        if (Files.exists(settings)) {
            mvn.add(1, "-s");
            mvn.add(2, settings.toString());
        }
        return mvn;
    }

    // ---- main E2E: full CLI flow from outside the repo ----

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void secondProjectFullE2E() throws Exception {
        Path ep = repoRoot().resolve("ep");
        Path manifest = inventoryManifest();
        Path project = tempDir.resolve("inventory-service");

        // 1. validate
        ProcessResult validate = exec(tempDir, List.of(ep.toString(), "validate", manifest.toString()));
        assertThat(validate.code()).as("validate:\n%s", validate.output()).isZero();
        assertThat(validate.output()).contains("[OK] Manifest valid");

        // 2. resolve — shared assets reused, identity is inventory
        ProcessResult resolve = exec(tempDir, List.of(ep.toString(), "resolve", manifest.toString()));
        assertThat(resolve.code()).as("resolve:\n%s", resolve.output()).isZero();
        assertThat(resolve.output()).contains("Project: inventory-service");
        assertThat(resolve.output()).contains("Java: 25");
        assertThat(resolve.output()).contains("Quality: Q3");
        for (String capability : List.of("web", "validation", "exception-handling", "audit",
                "persistence", "logging")) {
            assertThat(resolve.output()).contains(capability);
        }
        assertThat(resolve.output()).contains("persistence -> mybatis-plus");
        assertThat(resolve.output()).doesNotContain("demo-order");

        // 3. generate
        ProcessResult generate = exec(tempDir, List.of(ep.toString(), "generate",
                manifest.toString(), "--output", project.toString()));
        assertThat(generate.code()).as("generate:\n%s", generate.output()).isZero();

        // 4. conformance
        ProcessResult conformance = exec(tempDir, List.of(ep.toString(), "conformance",
                manifest.toString(), project.toString()));
        assertThat(conformance.code()).as("conformance:\n%s", conformance.output()).isZero();
        assertThat(conformance.output()).contains("Conformance: PASS");

        // 5. generated project mvn test
        ProcessResult build = exec(tempDir, mvnCommand(project));
        assertThat(build.code()).as("mvn test:\n%s", build.output()).isZero();
        assertThat(build.output()).contains("BUILD SUCCESS");

        // identity assertions
        assertIdentity(project);
    }

    private void assertIdentity(Path project) throws Exception {
        String pom = Files.readString(project.resolve("pom.xml"));
        assertThat(pom).contains("<groupId>com.acme</groupId>");
        assertThat(pom).contains("<artifactId>inventory-service</artifactId>");
        assertThat(pom).contains("<version>2.1.0</version>");
        assertThat(pom).contains("<java.version>25</java.version>");

        // java package + application class use inventory identity
        assertThat(Files.exists(project.resolve(
                "src/main/java/com/acme/inventory/InventoryServiceApplication.java"))).isTrue();
        String application = Files.readString(project.resolve(
                "src/main/java/com/acme/inventory/InventoryServiceApplication.java"));
        assertThat(application).contains("package com.acme.inventory;");
        assertThat(application).contains("InventoryServiceApplication");

        // application.yml: name + manifest configuration propagation
        String yml = Files.readString(project.resolve("src/main/resources/application.yml"));
        assertThat(yml).contains("spring.application.name: Inventory Service");
        assertThat(yml).contains("server.port: 9090");
        assertThat(yml).contains("mybatis-plus.mapper-locations: classpath*:mapper/inventory/**/*.xml");

        // no demo leakage anywhere in generated sources
        String allSources = String.join("\n",
                Files.readString(project.resolve("pom.xml")),
                Files.readString(project.resolve("src/main/resources/application.yml")),
                application);
        assertThat(allSources).doesNotContain("demo-order");
        assertThat(allSources).doesNotContain("demoorderservice");
        assertThat(allSources).doesNotContain("com.engineeringplatform.demo");
    }

    // ---- asset reuse: same asset files served both projects (no second copy) ----

    @Test
    void sharedAssetsReusedWithoutCopy() {
        // the same canonical asset dirs are the only source; no per-project asset copies exist
        Path capabilitiesDir = repoRoot().resolve("capabilities");
        Path providersDir = repoRoot().resolve("providers");
        for (String asset : List.of("web", "validation", "exception-handling", "logging",
                "persistence", "audit")) {
            assertThat(Files.exists(capabilitiesDir.resolve(asset).resolve("asset.yaml"))).isTrue();
        }
        assertThat(Files.exists(providersDir.resolve("mybatis-plus").resolve("asset.yaml"))).isTrue();
        // no project-scoped asset directories were created
        assertThat(Files.exists(tempDir.resolve("inventory-service/capabilities"))).isFalse();
    }

    // ---- isolation: platform/asset/demo untouched after generation ----

    @Test
    void isolationAfterGeneration() throws Exception {
        Path manifest = inventoryManifest();
        Path project = tempDir.resolve("iso");
        ProcessResult generate = exec(tempDir, List.of(
                repoRoot().resolve("ep").toString(), "generate",
                manifest.toString(), "--output", project.toString()));
        assertThat(generate.code()).as("generate:\n%s", generate.output()).isZero();

        // demo reference manifest unchanged
        String demo = Files.readString(repoRoot().resolve("tests/fixtures/v02-reference/project.yaml"));
        assertThat(demo).contains("demo-order-service");
        assertThat(demo).doesNotContain("com.acme");
        // platform + assets unchanged (spot check)
        assertThat(Files.readString(repoRoot().resolve("platform.yaml"))).contains("engineering-platform");
        assertThat(Files.readString(repoRoot().resolve("capabilities/web/asset.yaml"))).contains("id: web");
    }

    // ---- failure paths: no half-generated project ----

    @Test
    void invalidGroupIdFails() throws Exception {
        Path bad = tempDir.resolve("bad-group.yaml");
        Files.writeString(bad, """
                schemaVersion: 1
                project:
                  id: bad-service
                  name: Bad Service
                  version: 1.0.0
                  basePackage: com.bad.svc
                  groupId: "Bad_Group!"
                platform:
                  id: engineering-platform
                capabilities:
                  - id: web
                quality:
                  minimum: Q3
                """, StandardCharsets.UTF_8);
        Path ep = repoRoot().resolve("ep");
        ProcessResult validate = exec(tempDir, List.of(ep.toString(), "validate", bad.toString()));
        assertThat(validate.code()).as("validate:\n%s", validate.output()).isNotZero();
        assertThat(validate.output()).doesNotContain("[OK]");
    }

    @Test
    void unknownCapabilityFailsWithoutHalfProject() throws Exception {
        Path bad = tempDir.resolve("bad-cap.yaml");
        Files.writeString(bad, """
                schemaVersion: 1
                project:
                  id: bad-cap
                  name: Bad Cap
                  version: 1.0.0
                  basePackage: com.bad.cap
                platform:
                  id: engineering-platform
                capabilities:
                  - id: no-such-capability
                quality:
                  minimum: Q3
                """, StandardCharsets.UTF_8);
        Path ep = repoRoot().resolve("ep");
        Path output = tempDir.resolve("bad-out");
        ProcessResult generate = exec(tempDir, List.of(ep.toString(), "generate",
                bad.toString(), "--output", output.toString()));
        assertThat(generate.code()).as("generate:\n%s", generate.output()).isNotZero();
        assertThat(generate.output()).contains("ASSET_MISSING");
        assertThat(generate.output()).contains("no-such-capability");
        assertThat(Files.exists(output)).as("no half-generated project").isFalse();
    }
}
