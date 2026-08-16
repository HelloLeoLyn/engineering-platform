package com.engineeringplatform.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V06-WORK-006 — Runtime orchestration for generated projects.
 *
 * Every operation is delegated to the generated project's Runtime Recipe
 * (scripts/dev-start.sh / dev-stop.sh / dev-status.sh) or to the project-level
 * build toolchain with a project-scoped JDK. This class never starts backend /
 * frontend processes directly (no java -jar / mvn spring-boot:run / pnpm dev
 * scattered here): the Recipe scripts are the single source of truth for
 * runtime state (.runtime/*.pid|port|url).
 *
 * Process safety (inherited from the Recipe, not re-invented):
 *   - stop only touches processes recorded in .runtime/*.pid
 *   - PID verification before signalling (kill -0 / ProcessHandle.isAlive)
 *   - never kills by port, never guesses owners
 *   - duplicate start is a no-op (Recipe checks pid files)
 *   - stop clears its own .runtime state files
 *   - Console shutdown does not kill project processes (setsid detach)
 *
 * Toolchain isolation: builds run with JAVA_HOME pointed at a project-level
 * JDK (detected by EnvironmentPreflight) via ProcessBuilder env — system
 * global JAVA_HOME / alternatives / rc files are never modified.
 */
public final class RuntimeService {

    /** Build task lifecycle. */
    public enum BuildState { QUEUED, RUNNING, PASS, FAIL }

    /** Build task record (in-memory; no distributed job system per WP). */
    public static final class BuildTask {
        public final String id;
        public final String target; // backend | frontend | all
        public final long queuedAt = System.currentTimeMillis();
        public volatile BuildState state = BuildState.QUEUED;
        public volatile long startedAt;
        public volatile long finishedAt;
        public volatile int exitCode = -1;
        public volatile String error = "";
        public final StringBuilder log = new StringBuilder();

        BuildTask(String id, String target) {
            this.id = id;
            this.target = target;
        }

        public long durationMs() {
            long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
            return end - (startedAt > 0 ? startedAt : queuedAt);
        }
    }

    /** Runtime state of one side (backend/frontend) parsed from .runtime. */
    public static final class SideState {
        public String pid = "";
        public String port = "";
        public String url = "";
        public String status = "STOPPED"; // STOPPED | RUNNING | RUNNING-READY | STALE
    }

    private static final List<String> SECRET_PATTERNS = List.of("password", "token", "secret");

    private final ExecutorService buildPool = Executors.newCachedThreadPool();
    private final Map<String, BuildTask> builds = new ConcurrentHashMap<>();
    private final AtomicLong buildSeq = new AtomicLong();

    /** Resolve the Java home to use for this project (project-level isolation). */
    public static Optional<String> projectJavaHome(int requiredMajor) {
        return EnvironmentPreflight.scanJdks().stream()
                .filter(j -> requiredMajor == (int) j.get("major"))
                .map(j -> String.valueOf(j.get("home")))
                .findFirst();
    }

    /** Start a build (async). Returns the task id immediately. */
    public BuildTask build(Path projectDir, String target, int requiredJavaMajor) {
        String id = "b" + buildSeq.incrementAndGet();
        BuildTask task = new BuildTask(id, target);
        builds.put(projectKey(projectDir) + ":" + target, task);
        buildPool.submit(() -> runBuild(projectDir, task, requiredJavaMajor));
        return task;
    }

    private void runBuild(Path projectDir, BuildTask task, int requiredJavaMajor) {
        task.state = BuildState.RUNNING;
        task.startedAt = System.currentTimeMillis();
        try {
            if ("frontend".equals(task.target)) {
                buildFrontend(projectDir, task);
            } else if ("all".equals(task.target)) {
                buildBackend(projectDir, task, requiredJavaMajor);
                if (task.state == BuildState.RUNNING) {
                    task.log.append("\n--- frontend build ---\n");
                    buildFrontend(projectDir, task);
                }
            } else {
                buildBackend(projectDir, task, requiredJavaMajor);
            }
            if (task.state == BuildState.RUNNING) {
                task.state = BuildState.PASS;
            }
        } catch (Exception e) {
            task.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            task.state = BuildState.FAIL;
        }
        task.finishedAt = System.currentTimeMillis();
        // persist build log for the Logs view (incremental tail)
        try {
            Path runtime = projectDir.resolve(".runtime");
            Files.createDirectories(runtime);
            Files.writeString(runtime.resolve("build-" + task.target + ".log"),
                    task.log.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // log persistence is best-effort; in-memory task still returns state
        }
    }

    private void buildBackend(Path projectDir, BuildTask task, int requiredJavaMajor) throws IOException, InterruptedException {
        task.log.append("backend build: mvn -B package -DskipTests\n");
        ProcessBuilder pb = new ProcessBuilder("mvn", "-B", "-q",
                "-f", projectDir.resolve("pom.xml").toString(),
                "package", "-DskipTests");
        projectJavaHome(requiredJavaMajor).ifPresent(javaHome -> {
            pb.environment().put("JAVA_HOME", javaHome);
            pb.environment().put("PATH", javaHome + "/bin:" + pb.environment().getOrDefault("PATH", ""));
        });
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                task.log.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        task.exitCode = p.waitFor();
        if (task.exitCode != 0) {
            task.state = BuildState.FAIL;
            task.error = "backend build failed (exit " + task.exitCode + ")";
        }
    }

    private void buildFrontend(Path projectDir, BuildTask task) throws IOException, InterruptedException {
        task.log.append("frontend build: pnpm build\n");
        ProcessBuilder pb = new ProcessBuilder("pnpm", "--dir",
                projectDir.resolve("frontend").toString(), "build");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                task.log.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        task.exitCode = p.waitFor();
        if (task.exitCode != 0) {
            task.state = BuildState.FAIL;
            task.error = "frontend build failed (exit " + task.exitCode + ")";
        }
    }

    public BuildTask buildTask(Path projectDir, String target) {
        return builds.get(projectKey(projectDir) + ":" + target);
    }

    // ------------------------------------------------------------------
    // Runtime Recipe delegation
    // ------------------------------------------------------------------

    /** Run the Recipe script with a project-scoped JDK on PATH. */
    private static ProcessResult runScript(Path projectDir, String script, String arg, int requiredJavaMajor) {
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(projectDir.resolve("scripts").resolve(script).toString());
        if (arg != null && !arg.isBlank()) cmd.add(arg);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir.toFile());
        projectJavaHome(requiredJavaMajor).ifPresent(javaHome -> {
            pb.environment().put("JAVA_HOME", javaHome);
            pb.environment().put("PATH", javaHome + "/bin:" + pb.environment().getOrDefault("PATH", ""));
        });
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            return new ProcessResult(p.exitValue(), new String(out, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ProcessResult(-1, "failed to run " + script + ": " + e.getMessage());
        }
    }

    private record ProcessResult(int exitCode, String output) {}

    /** dev-start.sh --backend | --frontend | (all). Returns script output. */
    public Map<String, Object> start(Path projectDir, String target, int requiredJavaMajor) {
        if (!Files.isExecutable(projectDir.resolve("scripts/dev-start.sh"))) {
            return failure("NOT_READY", "Runtime Recipe scripts missing — regenerate with runtime-recipe capability");
        }
        String arg = switch (target == null ? "all" : target) {
            case "backend" -> "--backend";
            case "frontend" -> "--frontend";
            default -> null;
        };
        ProcessResult r = runScript(projectDir, "dev-start.sh", arg, requiredJavaMajor);
        boolean ok = r.exitCode() == 0 && !r.output().contains("ERROR");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", ok ? "STARTED" : "START_FAILED");
        out.put("exitCode", r.exitCode());
        out.put("message", sanitize(r.output()));
        return out;
    }

    /** dev-stop.sh — synchronous, only touches .runtime/*.pid processes. */
    public Map<String, Object> stop(Path projectDir, int requiredJavaMajor) {
        if (!Files.isExecutable(projectDir.resolve("scripts/dev-stop.sh"))) {
            return failure("NOT_READY", "Runtime Recipe scripts missing — regenerate with runtime-recipe capability");
        }
        ProcessResult r = runScript(projectDir, "dev-stop.sh", null, requiredJavaMajor);
        boolean ok = r.exitCode() == 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", ok ? "STOPPED" : "STOP_FAILED");
        out.put("exitCode", r.exitCode());
        out.put("message", sanitize(r.output()));
        return out;
    }

    /** restart = stop → start (compose the Recipe; no second start logic). */
    public Map<String, Object> restart(Path projectDir, String target, int requiredJavaMajor) {
        stop(projectDir, requiredJavaMajor);
        return start(projectDir, target, requiredJavaMajor);
    }

    /** Parse .runtime state into backend/frontend side status (like dev-status.sh). */
    public Map<String, Object> status(Path projectDir) {
        Path runtime = projectDir.resolve(".runtime");
        Map<String, Object> out = new LinkedHashMap<>();
        SideState backend = sideStatus(runtime, "backend", "/api/health");
        SideState frontend = sideStatus(runtime, "frontend", "/");
        out.put("backend", Map.of(
                "pid", backend.pid, "port", backend.port, "url", backend.url,
                "status", backend.status));
        out.put("frontend", Map.of(
                "pid", frontend.pid, "port", frontend.port, "url", frontend.url,
                "status", frontend.status));
        boolean anyRunning = "RUNNING-READY".equals(backend.status) || "RUNNING-READY".equals(frontend.status);
        out.put("overall", anyRunning ? "RUNNING" : "STOPPED");
        return out;
    }

    private static SideState sideStatus(Path runtime, String side, String healthPath) {
        SideState s = new SideState();
        s.pid = readState(runtime, side + ".pid");
        s.port = readState(runtime, side + ".port");
        String url = readState(runtime, side + ".url");
        s.url = url;
        if (s.pid.isEmpty() && s.port.isEmpty()) {
            return s; // STOPPED
        }
        boolean alive = false;
        try {
            alive = !s.pid.isEmpty() && ProcessHandle.of(Long.parseLong(s.pid))
                    .map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception ignored) {
            alive = false;
        }
        if (!alive) {
            s.status = s.pid.isEmpty() ? "STOPPED" : "STALE";
            return s;
        }
        boolean ready = !s.url.isEmpty() && isHttpReady(s.url + healthPath);
        s.status = ready ? "RUNNING-READY" : "RUNNING";
        return s;
    }

    private static String readState(Path runtime, String name) {
        Path f = runtime.resolve(name);
        if (!Files.isRegularFile(f)) return "";
        try {
            return Files.readString(f, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isHttpReady(String url) {
        try {
            Process p = new ProcessBuilder("curl", "-fsS", "-m", "2", url)
                    .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Logs
    // ------------------------------------------------------------------

    /** Tail a log file (backend.log / frontend.log / build log). Redacts secrets. */
    public Map<String, Object> logs(Path projectDir, String target, int lines) {
        Path runtime = projectDir.resolve(".runtime");
        Path file = switch (target == null ? "backend" : target) {
            case "frontend" -> runtime.resolve("frontend.log");
            case "build" -> runtime.resolve("build-all.log");
            default -> runtime.resolve("backend.log");
        };
        // build logs are per-target files (build-backend.log / build-frontend.log);
        // a generic "build" request prefers build-all.log then falls back.
        if (target != null && target.startsWith("build")) {
            Path perTarget = runtime.resolve("build-" + target.substring("build".length()) + ".log");
            if (Files.isRegularFile(perTarget)) file = perTarget;
        }
        if (!Files.isRegularFile(file)) {
            return Map.of("target", target, "exists", false, "lines", List.of());
        }
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int n = lines > 0 ? lines : 100;
            List<String> tail = all.size() > n ? all.subList(all.size() - n, all.size()) : all;
            List<String> redacted = tail.stream().map(RuntimeService::sanitize).toList();
            return Map.of("target", target, "exists", true, "totalLines", all.size(), "lines", redacted);
        } catch (IOException e) {
            return Map.of("target", target, "exists", false, "error", e.getMessage());
        }
    }

    /** Redact password/token/secret values from log output. */
    public static String sanitize(String text) {
        String out = text;
        for (String key : SECRET_PATTERNS) {
            out = out.replaceAll("(?i)(" + key + "\\s*[=:]\\s*)[^\\s,;\"']+", "$1***");
        }
        return out;
    }

    /** Actual URLs from .runtime (never guessed; dynamic ports respected). */
    public Map<String, String> openUrls(Path projectDir) {
        Path runtime = projectDir.resolve(".runtime");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("backend", readState(runtime, "backend.url"));
        out.put("frontend", readState(runtime, "frontend.url"));
        return out;
    }

    private static String projectKey(Path projectDir) {
        return projectDir.toAbsolutePath().normalize().toString();
    }

    private static Map<String, Object> failure(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "FAILED");
        out.put("errorCode", code);
        out.put("message", message);
        return out;
    }
}
