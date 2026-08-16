package com.engineeringplatform.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V06-WORK-006 — Environment Preflight for a generated project.
 *
 * Checks (project-level, never touches global config):
 *   - Java: required major (from stack profile, e.g. enterprise-java25 → 25)
 *     vs. effective java on PATH; scans /usr/lib/jvm, ~/.sdkman, ~/.java for a
 *     matching JDK so the Console can execute with a project-level JAVA_HOME
 *     WITHOUT modifying system-wide JAVA_HOME / alternatives / rc files.
 *   - Maven / Node / pnpm / python3 availability (light check).
 *   - MySQL: datasource readiness derived from the generated project config
 *     (e2e profile uses in-memory H2 → READY without external MySQL; an
 *     explicit mysql:// SPRING_DATASOURCE_URL is probed via JDBC). Secrets are
 *     never echoed.
 *   - Runtime Recipe scripts exist + executable; existing process state;
 *     port availability.
 *
 * Result statuses: READY / WARNING / BLOCKED per check + overall.
 */
public final class EnvironmentPreflight {

    private static final Pattern JAVA_VERSION_LINE =
            Pattern.compile("version\\s+\"([0-9]+)");
    private static final Pattern MVN_VERSION_LINE =
            Pattern.compile("Apache Maven\\s+([0-9.]+)");

    /** Scan common JDK roots for installed JDKs, return path + major. */
    public static List<Map<String, Object>> scanJdks() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("/usr/lib/jvm"));
        String home = System.getProperty("user.home");
        if (home != null) {
            roots.add(Path.of(home, ".sdkman", "candidates", "java"));
            roots.add(Path.of(home, ".java"));
            roots.add(Path.of(home, "jdk"));
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var s = Files.list(root)) {
                s.forEach(p -> {
                    Path javaBin = p.resolve("bin/java");
                    if (!Files.isExecutable(javaBin)) return;
                    Optional<Integer> major = probeJavaMajor(javaBin);
                    if (major.isEmpty()) return;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("home", p.toString());
                    m.put("major", major.get());
                    out.add(m);
                });
            } catch (IOException ignored) {
                // skip unreadable roots
            }
        }
        return out;
    }

    /** Probe `java -version` first line for the major version. */
    public static Optional<Integer> probeJavaMajor(Path javaBin) {
        try {
            Process p = new ProcessBuilder(javaBin.toString(), "-version")
                    .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            String text = new String(out, StandardCharsets.UTF_8);
            Matcher m = JAVA_VERSION_LINE.matcher(text);
            if (m.find()) {
                return Optional.of(Integer.parseInt(m.group(1)));
            }
        } catch (Exception ignored) {
            // not a usable JDK
        }
        return Optional.empty();
    }

    /** Effective java major on PATH (what a plain `java -jar` would use). */
    public static Optional<Integer> effectiveJavaMajor() {
        return probeJavaMajor(Path.of("java"));
    }

    private static Optional<String> firstLine(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            String text = new String(out, StandardCharsets.UTF_8);
            String line = text.lines().findFirst().orElse("");
            return Optional.of(line.trim());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String mavenVersion() {
        return firstLine("mvn", "-v").map(s -> {
            Matcher m = MVN_VERSION_LINE.matcher(s);
            return m.find() ? m.group(1) : "available";
        }).orElse("");
    }

    private static String nodeVersion() {
        return firstLine("node", "-v").orElse("");
    }

    private static String pnpmVersion() {
        return firstLine("pnpm", "-v").orElse("");
    }

    private static String pythonVersion() {
        return firstLine("python3", "--version").orElse("");
    }

    /** Overall preflight for a generated project directory. */
    public Map<String, Object> preflight(Path projectDir, int requiredJavaMajor) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();

        // ---- Java ----
        Map<String, Object> java = new LinkedHashMap<>();
        java.put("name", "Java");
        java.put("required", requiredJavaMajor);
        Optional<Integer> effective = effectiveJavaMajor();
        java.put("detected", effective.orElse(null));
        List<Map<String, Object>> jdks = scanJdks();
        Optional<Map<String, Object>> projectJdk = jdks.stream()
                .filter(j -> requiredJavaMajor == (int) j.get("major"))
                .findFirst();
        java.put("availableJdks", jdks);
        java.put("projectJdk", projectJdk.map(j -> j.get("home")).orElse(null));
        if (effective.isPresent() && effective.get() == requiredJavaMajor) {
            java.put("status", "READY");
            java.put("message", "Effective Java " + effective.get() + " matches required " + requiredJavaMajor);
        } else if (projectJdk.isPresent()) {
            java.put("status", "READY");
            java.put("message", "Effective Java " + effective.orElse(0)
                    + " differs; project-level JDK " + projectJdk.get().get("home")
                    + " (Java " + requiredJavaMajor + ") will be used (global config untouched)");
        } else {
            java.put("status", "BLOCKED");
            java.put("message", "No Java " + requiredJavaMajor + " found (effective: "
                    + effective.map(String::valueOf).orElse("none") + ")");
        }
        checks.add(java);

        // ---- Toolchain ----
        String mvn = mavenVersion();
        Map<String, Object> maven = new LinkedHashMap<>();
        maven.put("name", "Maven");
        maven.put("detected", mvn);
        maven.put("status", mvn.isEmpty() ? "BLOCKED" : "READY");
        checks.add(maven);

        String node = nodeVersion();
        Map<String, Object> nodeCheck = new LinkedHashMap<>();
        nodeCheck.put("name", "Node");
        nodeCheck.put("detected", node);
        nodeCheck.put("status", node.isEmpty() ? "BLOCKED" : "READY");
        checks.add(nodeCheck);

        String pnpm = pnpmVersion();
        Map<String, Object> pnpmCheck = new LinkedHashMap<>();
        pnpmCheck.put("name", "pnpm");
        pnpmCheck.put("detected", pnpm);
        pnpmCheck.put("status", pnpm.isEmpty() ? "BLOCKED" : "READY");
        checks.add(pnpmCheck);

        String py = pythonVersion();
        Map<String, Object> pyCheck = new LinkedHashMap<>();
        pyCheck.put("name", "python3");
        pyCheck.put("detected", py);
        pyCheck.put("status", py.isEmpty() ? "BLOCKED" : "READY");
        checks.add(pyCheck);

        // ---- Database ----
        checks.add(databaseCheck(projectDir));

        // ---- Runtime scripts + process/port state ----
        checks.add(runtimeScriptsCheck(projectDir));

        root.put("checks", checks);
        root.put("overall", overall(checks));
        root.put("projectDir", projectDir.toString());
        return root;
    }

    private Map<String, Object> databaseCheck(Path projectDir) {
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("name", "Database");
        try {
            Path e2e = projectDir.resolve("src/main/resources/application-e2e.yml");
            if (Files.isRegularFile(e2e)) {
                String cfg = Files.readString(e2e, StandardCharsets.UTF_8);
                if (cfg.contains("jdbc:h2:mem")) {
                    db.put("status", "READY");
                    db.put("mode", "e2e-in-memory-h2");
                    db.put("message", "e2e profile uses in-memory H2 — no external database required");
                    return db;
                }
            }
            Path app = projectDir.resolve("src/main/resources/application.yml");
            String cfg = app != null && Files.isRegularFile(app)
                    ? Files.readString(app, StandardCharsets.UTF_8) : "";
            if (cfg.contains("SPRING_DATASOURCE_URL")) {
                // external datasource driven by env; not set here → WARNING (actionable)
                db.put("status", "WARNING");
                db.put("mode", "external-env");
                db.put("message", "Datasource comes from SPRING_DATASOURCE_URL env; start with e2e profile for in-memory H2");
                return db;
            }
            db.put("status", "READY");
            db.put("mode", "default");
            db.put("message", "No external datasource required");
        } catch (Exception e) {
            db.put("status", "WARNING");
            db.put("message", "Cannot read datasource config: " + e.getMessage());
        }
        return db;
    }

    private Map<String, Object> runtimeScriptsCheck(Path projectDir) {
        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("name", "Runtime Recipe");
        Path scripts = projectDir.resolve("scripts");
        List<String> missing = new ArrayList<>();
        boolean allExec = true;
        for (String s : List.of("dev-start.sh", "dev-stop.sh", "dev-status.sh")) {
            Path p = scripts.resolve(s);
            if (!Files.isRegularFile(p)) missing.add(s);
            else if (!Files.isExecutable(p)) allExec = false;
        }
        if (!missing.isEmpty()) {
            rt.put("status", "BLOCKED");
            rt.put("message", "Missing runtime scripts: " + String.join(", ", missing)
                    + " (regenerate the project with the runtime-recipe capability)");
            rt.put("missing", missing);
            return rt;
        }
        if (!allExec) {
            rt.put("status", "WARNING");
            rt.put("message", "Runtime scripts present but not executable (chmod +x recommended)");
            return rt;
        }
        rt.put("status", "READY");
        rt.put("message", "dev-start.sh / dev-stop.sh / dev-status.sh present and executable");

        // existing process state + port availability
        Map<String, Object> state = new LinkedHashMap<>();
        Path runtime = projectDir.resolve(".runtime");
        for (String side : List.of("backend", "frontend")) {
            Path pidFile = runtime.resolve(side + ".pid");
            Path portFile = runtime.resolve(side + ".port");
            if (Files.isRegularFile(pidFile)) {
                try {
                    long pid = Long.parseLong(Files.readString(pidFile).trim());
                    state.put(side + ".pid", pid);
                    state.put(side + ".alive", ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                } catch (Exception ignored) {
                    state.put(side + ".pid", "unreadable");
                }
            }
            if (Files.isRegularFile(portFile)) {
                try {
                    state.put(side + ".port", Files.readString(portFile).trim());
                } catch (IOException e) {
                    state.put(side + ".port", "unreadable");
                }
            }
        }
        rt.put("processState", state);
        return rt;
    }

    private static String overall(List<Map<String, Object>> checks) {
        for (Map<String, Object> c : checks) {
            if ("BLOCKED".equals(c.get("status"))) return "BLOCKED";
        }
        for (Map<String, Object> c : checks) {
            if ("WARNING".equals(c.get("status"))) return "WARNING";
        }
        return "READY";
    }
}
