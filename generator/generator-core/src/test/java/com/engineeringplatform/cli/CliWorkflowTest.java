package com.engineeringplatform.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V03-WORK-003 — developer workflow hardening tests.
 * Covers working-directory independence, root discovery, repeat generation,
 * actionable error UX, success guidance and full workflow from outside the repo.
 */
class CliWorkflowTest {

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

    private Path referenceManifest() {
        return repoRoot().resolve("tests/fixtures/v03-reference/inventory-service/project.yaml");
    }

    private CliResult run(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = new EngineeringPlatformCli(repoRoot(), new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8)).run(args);
        return new CliResult(code, outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
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

    // ---- internal (absolute paths) ----

    @Test
    void absoluteManifest() {
        assertThat(run("validate", referenceManifest().toString()).code()).isZero();
    }

    @Test
    void absoluteOutput() {
        Path out = tempDir.resolve("abs-out");
        CliResult r = run("generate", referenceManifest().toString(), "--output", out.toString());
        assertThat(r.code()).isZero();
        assertThat(Files.exists(out.resolve("pom.xml"))).isTrue();
    }

    @Test
    void repeatedGenerateIsConsistent() throws Exception {
        Path out1 = tempDir.resolve("rep-1");
        Path out2 = tempDir.resolve("rep-2");
        assertThat(run("generate", referenceManifest().toString(), "--output", out1.toString()).code()).isZero();
        assertThat(run("generate", referenceManifest().toString(), "--output", out2.toString()).code()).isZero();

        List<String> files1 = fileList(out1);
        List<String> files2 = fileList(out2);
        assertThat(files1).isEqualTo(files2);
        for (String file : files1) {
            assertThat(Files.readAllBytes(out1.resolve(file)))
                    .as("content drift: %s", file)
                    .isEqualTo(Files.readAllBytes(out2.resolve(file)));
        }
    }

    @Test
    void userChangeConflictIsProtected() throws Exception {
        Path out = tempDir.resolve("conflict");
        Files.createDirectories(out);
        Files.writeString(out.resolve("user-notes.txt"), "user content");
        CliResult r = run("generate", referenceManifest().toString(), "--output", out.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("not empty");
        assertThat(Files.readString(out.resolve("user-notes.txt"))).isEqualTo("user content");
    }

    @Test
    void actionableManifestError() throws Exception {
        Path bad = tempDir.resolve("bad-manifest.yaml");
        Files.writeString(bad, "schemaVersion: 1\nproject:\n  id: x\n", StandardCharsets.UTF_8);
        CliResult r = run("validate", bad.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("[FAIL] Manifest invalid");
        assertThat(r.err()).contains("missing required field");
    }

    @Test
    void actionableResolutionError() throws Exception {
        Path bad = tempDir.resolve("bad-resolve.yaml");
        Files.writeString(bad, """
                schemaVersion: 1
                project:
                  id: demo
                  name: demo
                  version: 1.0.0
                  basePackage: com.demo.x
                platform:
                  id: engineering-platform
                capabilities:
                  - id: no-such-capability
                quality:
                  minimum: Q3
                """, StandardCharsets.UTF_8);
        CliResult r = run("resolve", bad.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("ASSET_MISSING");
        assertThat(r.err()).contains("no-such-capability");
    }

    @Test
    void noStacktraceForExpectedErrors() throws Exception {
        Path bad = tempDir.resolve("quiet.yaml");
        Files.writeString(bad, "schemaVersion: 1\nproject:\n  id: x\n", StandardCharsets.UTF_8);
        CliResult r = run("validate", bad.toString());
        assertThat(r.err()).doesNotContain("at com.engineeringplatform");
        assertThat(r.err()).doesNotContain("Exception");
    }

    @Test
    void successNextStepGuidance() {
        Path out = tempDir.resolve("next");
        CliResult r = run("generate", referenceManifest().toString(), "--output", out.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("Generated: " + out);
        assertThat(r.out()).contains("Next:");
        assertThat(r.out()).contains("cd " + out);
        assertThat(r.out()).contains("mvn test");
    }

    @Test
    void placeholderGuidanceWithoutSecrets() {
        Path out = tempDir.resolve("ph");
        CliResult r = run("generate", referenceManifest().toString(), "--output", out.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("configuration references need values");
        assertThat(r.out()).contains("spring.datasource.url");
        assertThat(r.out()).doesNotContain("hunter2");
        assertThat(r.out()).doesNotContain("password:");
    }

    // ---- real process (cwd independence / root discovery) ----

    @Test
    void cwdIndependentWithRelativePaths() throws Exception {
        Path ep = repoRoot().resolve("ep");
        // from the repo root, using relative manifest + relative output
        ProcessResult r1 = exec(repoRoot(), List.of(ep.toString(), "validate",
                "tests/fixtures/v03-reference/inventory-service/project.yaml"));
        assertThat(r1.code()).as("relative manifest from repo root:\n%s", r1.output()).isZero();

        // from a subdirectory, absolute ep + relative manifest (relative to cwd)
        Path sub = repoRoot().resolve("generator/generator-core");
        ProcessResult r2 = exec(sub, List.of(ep.toString(), "validate",
                repoRoot().resolve("tests/fixtures/v03-reference/inventory-service/project.yaml").toString()));
        assertThat(r2.code()).as("absolute manifest from subdir:\n%s", r2.output()).isZero();

        // from outside the repo entirely (cwd = tempDir), relative output lands in cwd
        ProcessResult r3 = exec(tempDir, List.of(ep.toString(), "generate",
                repoRoot().resolve("tests/fixtures/v03-reference/inventory-service/project.yaml").toString(),
                "--output", "rel-out"));
        assertThat(r3.code()).as("relative output from outside repo:\n%s", r3.output()).isZero();
        assertThat(Files.exists(tempDir.resolve("rel-out/pom.xml"))).isTrue();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void fullWorkflowFromOutsideRepo() throws Exception {
        Path ep = repoRoot().resolve("ep");
        Path manifest = referenceManifest();
        Path output = tempDir.resolve("outside-project");

        assertThat(exec(tempDir, List.of(ep.toString(), "validate", manifest.toString())).code()).isZero();
        assertThat(exec(tempDir, List.of(ep.toString(), "resolve", manifest.toString())).code()).isZero();
        ProcessResult generate = exec(tempDir, List.of(ep.toString(), "generate",
                manifest.toString(), "--output", output.toString()));
        assertThat(generate.code()).as("generate:\n%s", generate.output()).isZero();
        assertThat(generate.output()).contains("Next:");
        assertThat(exec(tempDir, List.of(ep.toString(), "conformance",
                manifest.toString(), output.toString())).code()).isZero();

        ProcessResult build = exec(tempDir, mvnCommand(output));
        assertThat(build.code()).as("mvn test:\n%s", build.output()).isZero();
        assertThat(build.output()).contains("BUILD SUCCESS");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void repeatedWorkflowStillBuilds() throws Exception {
        Path ep = repoRoot().resolve("ep");
        Path manifest = referenceManifest();
        Path out1 = tempDir.resolve("rw-1");
        Path out2 = tempDir.resolve("rw-2");
        for (Path out : List.of(out1, out2)) {
            ProcessResult generate = exec(tempDir, List.of(ep.toString(), "generate",
                    manifest.toString(), "--output", out.toString()));
            assertThat(generate.code()).as("generate:\n%s", generate.output()).isZero();
            ProcessResult conformance = exec(tempDir, List.of(ep.toString(), "conformance",
                    manifest.toString(), out.toString()));
            assertThat(conformance.code()).as("conformance:\n%s", conformance.output()).isZero();
            assertThat(conformance.output()).contains("Conformance: PASS");
            ProcessResult build = exec(tempDir, mvnCommand(out));
            assertThat(build.code()).as("mvn test:\n%s", build.output()).isZero();
            assertThat(build.output()).contains("BUILD SUCCESS");
        }
    }

    private static List<String> fileList(Path root) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString())
                    .filter(p -> !p.startsWith(".generator"))
                    .sorted()
                    .toList();
        }
    }
}
