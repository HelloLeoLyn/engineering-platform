package com.engineeringplatform.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V03-WORK-002 — CLI behavior tests (in-process, no System.exit).
 * Exit code contract: 0 = SUCCESS, 1 = failure, 2 = usage error.
 */
class CliTest {

    @TempDir
    Path tempDir;

    private record CliResult(int code, String out, String err) {
    }

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

    private CliResult run(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = new EngineeringPlatformCli(repoRoot(), new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8)).run(args);
        return new CliResult(code, outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
    }

    private Path writeManifest(String name, Map<String, Object> manifest) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, toYaml(manifest), StandardCharsets.UTF_8);
        return file;
    }

    private static String toYaml(Map<String, Object> manifest) {
        StringBuilder sb = new StringBuilder("schemaVersion: 1\n");
        for (Map.Entry<String, Object> e : manifest.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    private static Map<String, Object> validManifest() {
        return Map.of(
                "project", Map.of("id", "inventory-service", "name", "Inventory Service",
                        "version", "2.1.0", "basePackage", "com.acme.inventory",
                        "groupId", "com.acme", "artifactId", "inventory-service"),
                "platform", Map.of("id", "engineering-platform"),
                "capabilities", java.util.List.of(Map.of("id", "web"), Map.of("id", "validation"),
                        Map.of("id", "exception-handling"), Map.of("id", "audit")),
                "quality", Map.of("minimum", "Q3"));
    }

    private Path referenceManifest() {
        return repoRoot().resolve("tests/fixtures/v03-reference/inventory-service/project.yaml");
    }

    // 1-4. help / version / unknown command / missing argument

    @Test
    void help() {
        CliResult r = run("--help");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("usage: ep");
    }

    @Test
    void version() {
        CliResult r = run("--version");
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("Engineering Platform CLI");
    }

    @Test
    void unknownCommand() {
        CliResult r = run("frobnicate");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("unknown command");
    }

    @Test
    void missingArgument() {
        CliResult r = run("validate");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("usage");
    }

    // 5-7. validate

    @Test
    void validateValid() {
        CliResult r = run("validate", referenceManifest().toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("[OK] Manifest valid");
    }

    @Test
    void validateInvalid() throws Exception {
        Path bad = writeManifest("bad.yaml", Map.of("project", Map.of("id", "x")));
        CliResult r = run("validate", bad.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("[FAIL]");
    }

    @Test
    void validateMissingFile() {
        CliResult r = run("validate", tempDir.resolve("nope.yaml").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("not found");
    }

    // 8-10. resolve

    @Test
    void resolveSuccess() {
        CliResult r = run("resolve", referenceManifest().toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("Project: inventory-service");
    }

    @Test
    void resolveFailure() throws Exception {
        Map<String, Object> bad = new java.util.LinkedHashMap<>(validManifest());
        bad.put("capabilities", java.util.List.of(Map.of("id", "no-such-capability")));
        Path file = writeManifest("bad-resolve.yaml", bad);
        CliResult r = run("resolve", file.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("Resolution failed");
    }

    @Test
    void resolveReadableSummary() {
        CliResult r = run("resolve", referenceManifest().toString());
        assertThat(r.out()).contains("Java: 25");
        assertThat(r.out()).contains("Quality: Q3");
        assertThat(r.out()).contains("Capabilities:");
        assertThat(r.out()).contains("persistence required");
        assertThat(r.out()).contains("persistence -> mybatis-plus");
    }

    // 11-15. generate

    @Test
    void generateSuccess() {
        Path out = tempDir.resolve("gen");
        CliResult r = run("generate", referenceManifest().toString(), "--output", out.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("Result: SUCCESS");
        assertThat(Files.exists(out.resolve("pom.xml"))).isTrue();
    }

    @Test
    void generateOutputCreated() {
        Path out = tempDir.resolve("gen2");
        run("generate", referenceManifest().toString(), "--output", out.toString());
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/inventory/InventoryServiceApplication.java")))
                .isTrue();
    }

    @Test
    void generateInvalidOutput() {
        CliResult r = run("generate", referenceManifest().toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("--output");
    }

    @Test
    void generateExistingConflict() throws Exception {
        Path out = tempDir.resolve("gen3");
        Files.createDirectories(out);
        Files.writeString(out.resolve("existing.txt"), "keep");
        CliResult r = run("generate", referenceManifest().toString(), "--output", out.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("not empty");
        assertThat(Files.readString(out.resolve("existing.txt"))).isEqualTo("keep");
    }

    @Test
    void generateResolutionFailure() throws Exception {
        Map<String, Object> bad = new java.util.LinkedHashMap<>(validManifest());
        bad.put("capabilities", java.util.List.of(Map.of("id", "no-such-capability")));
        Path file = writeManifest("bad-gen.yaml", bad);
        Path out = tempDir.resolve("gen4");
        CliResult r = run("generate", file.toString(), "--output", out.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("Resolution failed");
        assertThat(Files.exists(out)).isFalse();
    }

    // 16-18. conformance

    @Test
    void conformancePass() {
        Path out = tempDir.resolve("conf1");
        run("generate", referenceManifest().toString(), "--output", out.toString());
        CliResult r = run("conformance", referenceManifest().toString(), out.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("Conformance: PASS");
    }

    @Test
    void conformanceFail() throws Exception {
        Path out = tempDir.resolve("conf2");
        run("generate", referenceManifest().toString(), "--output", out.toString());
        Files.delete(out.resolve(
                "src/main/java/com/acme/inventory/common/error/GlobalExceptionHandler.java"));
        CliResult r = run("conformance", referenceManifest().toString(), out.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("Conformance: FAIL");
        assertThat(r.out()).contains("asset.required-file");
    }

    @Test
    void conformanceMissingProjectDir() {
        CliResult r = run("conformance", referenceManifest().toString(),
                tempDir.resolve("missing-dir").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("not found");
    }

    // 19-21. exit code contract

    @Test
    void exitCodeZeroOnSuccess() {
        assertThat(run("validate", referenceManifest().toString()).code()).isZero();
        assertThat(run("resolve", referenceManifest().toString()).code()).isZero();
    }

    @Test
    void exitCodeOneOnFailure() throws Exception {
        Path bad = writeManifest("exit1.yaml", Map.of("project", Map.of("id", "x")));
        assertThat(run("validate", bad.toString()).code()).isEqualTo(1);
        assertThat(run("validate", tempDir.resolve("missing.yaml").toString()).code()).isEqualTo(1);
    }

    @Test
    void exitCodeTwoOnUsageError() {
        assertThat(run("validate").code()).isEqualTo(2);
        assertThat(run("nonsense").code()).isEqualTo(2);
        assertThat(run("generate", "x.yaml").code()).isEqualTo(2);
    }

    // 22. no Java stacktrace for expected user errors

    @Test
    void noStacktraceForExpectedErrors() throws Exception {
        Path bad = writeManifest("quiet.yaml", Map.of("project", Map.of("id", "x")));
        CliResult r = run("validate", bad.toString());
        assertThat(r.err()).doesNotContain("at com.engineeringplatform");
        assertThat(r.err()).doesNotContain("Exception");
        assertThat(r.err()).contains("[FAIL]");
    }

    // 23. inventory-service manifest works end to end via CLI

    @Test
    void inventoryServiceManifestWorks() {
        assertThat(run("validate", referenceManifest().toString()).code()).isZero();
        CliResult resolve = run("resolve", referenceManifest().toString());
        assertThat(resolve.code()).isZero();
        assertThat(resolve.out()).contains("inventory-service");
        assertThat(resolve.out()).doesNotContain("demo-order");
    }
}
