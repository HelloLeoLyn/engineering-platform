package com.engineeringplatform.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V03-WORK-002 — real CLI smoke test via the ./ep launcher (no Java integration code).
 * Runs the full developer workflow: validate -> resolve -> generate -> conformance -> mvn test.
 */
class CliSmokeTest {

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

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void fullDeveloperWorkflowViaEpCli() throws Exception {
        Path root = repoRoot();
        Path manifest = root.resolve("tests/fixtures/v03-reference/inventory-service/project.yaml");
        Path output = tempDir.resolve("smoke-project");
        String ep = root.resolve("ep").toString();

        // validate
        ProcessResult validate = exec(root, List.of(ep, "validate", manifest.toString()));
        assertThat(validate.code()).as("ep validate:\n%s", validate.output()).isZero();
        assertThat(validate.output()).contains("[OK] Manifest valid");

        // resolve
        ProcessResult resolve = exec(root, List.of(ep, "resolve", manifest.toString()));
        assertThat(resolve.code()).as("ep resolve:\n%s", resolve.output()).isZero();
        assertThat(resolve.output()).contains("inventory-service");
        assertThat(resolve.output()).contains("mybatis-plus");

        // generate
        ProcessResult generate = exec(root, List.of(ep, "generate", manifest.toString(),
                "--output", output.toString()));
        assertThat(generate.code()).as("ep generate:\n%s", generate.output()).isZero();
        assertThat(generate.output()).contains("Generated:");
        assertThat(Files.exists(output.resolve("pom.xml"))).isTrue();

        // conformance
        ProcessResult conformance = exec(root, List.of(ep, "conformance", manifest.toString(),
                output.toString()));
        assertThat(conformance.code()).as("ep conformance:\n%s", conformance.output()).isZero();
        assertThat(conformance.output()).contains("Conformance: PASS");

        // generated project mvn test -> BUILD SUCCESS
        List<String> mvn = new ArrayList<>(List.of("mvn", "-B", "test"));
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        if (Files.exists(settings)) {
            mvn.add(1, "-s");
            mvn.add(2, settings.toString());
        }
        ProcessResult build = exec(output, mvn);
        assertThat(build.code()).as("generated mvn test:\n%s", build.output()).isZero();
        assertThat(build.output()).contains("BUILD SUCCESS");
    }
}
