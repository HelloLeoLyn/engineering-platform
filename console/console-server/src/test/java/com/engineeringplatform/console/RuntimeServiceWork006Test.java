package com.engineeringplatform.console;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V06-WORK-006 — Console runtime targeted tests.
 *
 * Environment Preflight (Java 25 detection, script presence, toolchain),
 * RuntimeService status parsing (.runtime state → RUNNING/STOPPED/STALE),
 * log tailing with secret redaction, build task lifecycle plumbing.
 * No real mvn/pnpm builds here (Verification Budget: one project build is
 * exercised once in the real-lifecycle acceptance, not in unit tests).
 */
class RuntimeServiceWork006Test {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Environment Preflight
    // ------------------------------------------------------------------

    @Test
    void preflightDetectsJava25OnThisHost() {
        // Host has java-21 and java-25 under /usr/lib/jvm (verified in WORK-006).
        List<Map<String, Object>> jdks = EnvironmentPreflight.scanJdks();
        assertThat(jdks).isNotEmpty();
        assertThat(jdks.stream().map(j -> (int) j.get("major")))
                .as("JDK majors scanned on host").contains(25);
        // effective java on PATH is 25 on this machine
        var effective = EnvironmentPreflight.effectiveJavaMajor();
        assertThat(effective).isPresent();
    }

    @Test
    void preflightBlocksWhenScriptsMissing() {
        Path project = tempDir.resolve("no-scripts");
        Map<String, Object> result = new EnvironmentPreflight().preflight(project, 25);
        Map<String, Object> rt = (Map<String, Object>) ((List<?>) result.get("checks")).stream()
                .filter(c -> "Runtime Recipe".equals(((Map<?, ?>) c).get("name")))
                .findFirst().orElseThrow();
        assertThat(rt.get("status")).isEqualTo("BLOCKED");
        assertThat(result.get("overall")).isEqualTo("BLOCKED");
    }

    @Test
    void preflightReadyWhenScriptsPresentAndExecutable() throws Exception {
        Path project = tempDir.resolve("with-scripts");
        Files.createDirectories(project.resolve("scripts"));
        for (String s : List.of("dev-start.sh", "dev-stop.sh", "dev-status.sh")) {
            Path p = project.resolve("scripts").resolve(s);
            Files.writeString(p, "#!/usr/bin/env bash\necho ok\n", StandardCharsets.UTF_8);
            p.toFile().setExecutable(true);
        }
        Map<String, Object> result = new EnvironmentPreflight().preflight(project, 25);
        Map<String, Object> rt = (Map<String, Object>) ((List<?>) result.get("checks")).stream()
                .filter(c -> "Runtime Recipe".equals(((Map<?, ?>) c).get("name")))
                .findFirst().orElseThrow();
        assertThat(rt.get("status")).isEqualTo("READY");
    }

    @Test
    void preflightReportsToolchain() {
        Map<String, Object> result = new EnvironmentPreflight().preflight(tempDir, 25);
        Map<String, Object> maven = (Map<String, Object>) ((List<?>) result.get("checks")).stream()
                .filter(c -> "Maven".equals(((Map<?, ?>) c).get("name")))
                .findFirst().orElseThrow();
        assertThat(maven.get("status")).isEqualTo("READY");
        assertThat(String.valueOf(maven.get("detected"))).isNotBlank();
    }

    // ------------------------------------------------------------------
    // RuntimeService — status parsing from .runtime state
    // ------------------------------------------------------------------

    @Test
    void statusStoppedWhenNoRuntimeState() {
        RuntimeService svc = new RuntimeService();
        Map<String, Object> status = svc.status(tempDir);
        assertThat(((Map<?, ?>) status.get("backend")).get("status")).isEqualTo("STOPPED");
        assertThat(((Map<?, ?>) status.get("frontend")).get("status")).isEqualTo("STOPPED");
        assertThat(status.get("overall")).isEqualTo("STOPPED");
    }

    @Test
    void statusRunningWhenPidAlive() throws Exception {
        // Use THIS JVM's pid — guaranteed alive, so .runtime state parses as RUNNING.
        Path runtime = tempDir.resolve(".runtime");
        Files.createDirectories(runtime);
        long self = ProcessHandle.current().pid();
        Files.writeString(runtime.resolve("backend.pid"), String.valueOf(self));
        Files.writeString(runtime.resolve("backend.port"), "19999");
        Files.writeString(runtime.resolve("backend.url"), "http://localhost:19999");

        RuntimeService svc = new RuntimeService();
        Map<String, Object> status = svc.status(tempDir);
        Map<?, ?> backend = (Map<?, ?>) status.get("backend");
        assertThat(String.valueOf(backend.get("pid"))).isEqualTo(String.valueOf(self));
        assertThat(String.valueOf(backend.get("status")))
                .isIn("RUNNING", "RUNNING-READY"); // ready depends on /api/health probe
    }

    @Test
    void statusStaleWhenPidFileButProcessGone() throws Exception {
        Path runtime = tempDir.resolve(".runtime");
        Files.createDirectories(runtime);
        // 999999999 is very unlikely to be alive
        Files.writeString(runtime.resolve("frontend.pid"), "999999999");
        RuntimeService svc = new RuntimeService();
        Map<String, Object> status = svc.status(tempDir);
        assertThat(((Map<?, ?>) status.get("frontend")).get("status")).isEqualTo("STALE");
    }

    // ------------------------------------------------------------------
    // Logs — tail + secret redaction
    // ------------------------------------------------------------------

    @Test
    void logsTailAndRedactSecrets() throws Exception {
        Path runtime = tempDir.resolve(".runtime");
        Files.createDirectories(runtime);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) sb.append("line ").append(i).append("\n");
        sb.append("password=hunter2\n");
        sb.append("token=abc123\n");
        sb.append("auth secret=supersecret\n");
        Files.writeString(runtime.resolve("backend.log"), sb.toString());

        RuntimeService svc = new RuntimeService();
        Map<String, Object> logs = svc.logs(tempDir, "backend", 100);
        assertThat(logs.get("exists")).isEqualTo(true);
        assertThat(logs.get("totalLines")).isEqualTo(153);
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) logs.get("lines");
        assertThat(lines).hasSize(100);
        String joined = String.join("\n", lines);
        assertThat(joined).contains("password=***").contains("token=***")
                .contains("secret=***").doesNotContain("hunter2").doesNotContain("abc123")
                .doesNotContain("supersecret");
    }

    @Test
    void logsMissingFile() {
        RuntimeService svc = new RuntimeService();
        Map<String, Object> logs = svc.logs(tempDir, "backend", 100);
        assertThat(logs.get("exists")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Sanitize
    // ------------------------------------------------------------------

    @Test
    void sanitizeRedactsKnownSecretKeys() {
        String out = RuntimeService.sanitize(
                "started with password=123456, token=abcdef, SPRING_DATASOURCE_PASSWORD=root");
        assertThat(out).doesNotContain("123456").doesNotContain("abcdef").doesNotContain("root");
        assertThat(out).contains("password=***").contains("token=***");
    }

    // ------------------------------------------------------------------
    // Build task plumbing (no real build executed here)
    // ------------------------------------------------------------------

    @Test
    void buildTaskUnknownWhenNotStarted() {
        RuntimeService svc = new RuntimeService();
        assertThat(svc.buildTask(tempDir, "backend")).isNull();
    }

    @Test
    void openUrlsEmptyWhenNoState() {
        RuntimeService svc = new RuntimeService();
        Map<String, String> urls = svc.openUrls(tempDir);
        assertThat(urls.get("backend")).isEmpty();
        assertThat(urls.get("frontend")).isEmpty();
    }

    @Test
    void openUrlsReadActualDynamicPorts() throws Exception {
        Path runtime = tempDir.resolve(".runtime");
        Files.createDirectories(runtime);
        Files.writeString(runtime.resolve("backend.url"), "http://localhost:18099");
        Files.writeString(runtime.resolve("frontend.url"), "http://localhost:15199");
        RuntimeService svc = new RuntimeService();
        Map<String, String> urls = svc.openUrls(tempDir);
        assertThat(urls.get("backend")).isEqualTo("http://localhost:18099");
        assertThat(urls.get("frontend")).isEqualTo("http://localhost:15199");
    }
}
