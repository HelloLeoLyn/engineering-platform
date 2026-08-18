package com.engineeringplatform.console;

import com.engineeringplatform.generator.core.AssetYamlReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * V06-WORK-004 — Engineering Platform Console Server.
 *
 * Lightweight HTTP adapter exposing the EXISTING generator pipeline as a
 * visual Console API. No second resolver/planner/generator: all generation
 * flows through GenerationService → CompleteResolver → AssetProjectGenerator.
 *
 * Endpoints (JSON):
 *   GET  /api/meta                 catalog: profiles / stacks / templates / modules
 *   GET  /api/overview             console dashboard data
 *   GET  /api/projects             filesystem-backed project metadata list
 *   POST /api/validate             validate a Console project contract
 *   POST /api/preview              render project.yaml (Contract Preview) text
 *   POST /api/generate             generate the project (existing pipeline)
 */
public final class ConsoleServer {

    private final Path platformRoot;
    private final Path dataDir;
    private final GenerationService generation;
    private final ProjectStore projects;
    private final ModuleStore modules;
    private final MySqlImportService mysql;
    private final ImportCandidateService candidates;

    public ConsoleServer(Path platformRoot, Path dataDir) {
        this.platformRoot = platformRoot;
        this.dataDir = dataDir;
        this.generation = new GenerationService(platformRoot, dataDir);
        this.projects = new ProjectStore(dataDir.resolve("projects.json"));
        this.modules = new ModuleStore(dataDir.resolve("modules"));
        this.mysql = new MySqlImportService();
        this.candidates = new ImportCandidateService();
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/meta", this::handleMeta);
        server.createContext("/api/overview", this::handleOverview);
        server.createContext("/api/projects", this::handleProjects);
        server.createContext("/api/validate", this::handleValidate);
        server.createContext("/api/preview", this::handlePreview);
        server.createContext("/api/generate", this::handleGenerate);
        server.createContext("/api/modules", this::handleModules);
        server.createContext("/api/runtime", this::handleRuntime);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("[console] Engineering Platform Console listening on http://localhost:" + port);
        System.out.println("[console] platformRoot=" + platformRoot + " dataDir=" + dataDir);
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void handleMeta(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("applicationProfiles", generation.applicationProfiles());
            out.put("stackProfiles", generation.stackProfiles());
            out.put("frontendTemplates", generation.frontendTemplates());
            out.put("modules", generation.moduleCatalog());
            out.put("platform", Map.of(
                    "id", "engineering-platform",
                    "name", "Engineering Platform",
                    "generator", "0.1.0 (existing pipeline)"));
            respond(ex, 200, Json.write(out));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleOverview(HttpExchange ex) throws IOException {
        try {
            List<Map<String, Object>> list = projects.list();
            int modules = 0;
            for (Map<String, Object> p : list) {
                Object m = p.get("modules");
                if (m instanceof List<?> l) modules += l.size();
            }
            List<Map<String, Object>> certified = new ArrayList<>();
            for (Map<String, Object> p : generation.applicationProfiles()) {
                if ("certified".equals(p.get("status"))) certified.add(p);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projects", list.size());
            out.put("generatedModules", modules);
            out.put("certifiedTemplates", certified.size());
            out.put("lastGeneration", list.isEmpty() ? null : list.get(0));
            out.put("recentProjects", list.size() > 5 ? list.subList(0, 5) : list);
            respond(ex, 200, Json.write(out));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleProjects(HttpExchange ex) throws IOException {
        try {
            if ("POST".equals(ex.getRequestMethod())) {
                // body: { name, profile, stack, frontend, modules, location }
                Map<String, Object> body = Json.parseObject(readBody(ex));
                Map<String, Object> saved = projects.add(body);
                respond(ex, 200, Json.write(saved));
                return;
            }
            respond(ex, 200, Json.write(projects.list()));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleValidate(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> contract = Json.parseObject(readBody(ex));
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> errors = ConsoleContractValidator.validate(contract);
            if (errors.isEmpty()) {
                result.put("valid", true);
            } else {
                result.put("valid", false);
                result.put("errors", errors);
            }
            respond(ex, 200, Json.write(result));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("valid", false, "error", String.valueOf(e.getMessage()))));
        }
    }

    private void handlePreview(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> contract = Json.parseObject(readBody(ex));
            String yaml = YamlDumper.dump(contract);
            respond(ex, 200, Json.write(Map.of("yaml", yaml)));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleGenerate(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> body = Json.parseObject(readBody(ex));
            @SuppressWarnings("unchecked")
            Map<String, Object> contract = (Map<String, Object>) body.get("contract");
            String location = String.valueOf(body.getOrDefault("location", dataDir.resolve("generated").toString()));
            Path outputDir = Path.of(location);

            // Guard: refuse to overwrite non-empty directory (same contract as CLI)
            if (Files.exists(outputDir) && !isEmptyDir(outputDir)) {
                respond(ex, 409, Json.write(Map.of(
                        "status", "FAILED",
                        "category", "Output Path Invalid",
                        "message", "Output directory is not empty: " + outputDir)));
                return;
            }

            // V06-FINAL: Console-created Business Modules (stored in ModuleStore)
            // must be injected into the generation pipeline as extra manifests —
            // otherwise /api/generate silently drops them (Golden Path blocker).
            List<Map<String, Object>> extraManifests = new ArrayList<>();
            Object mods = contract.get("modules");
            if (mods instanceof List<?> ml) {
                for (Object o : ml) {
                    String mid = o instanceof Map<?, ?> m
                            ? String.valueOf(((Map<?, ?>) m).get("id")) : String.valueOf(o);
                    Map<String, Object> stored = modules.get(mid);
                    if (stored != null && stored.get("yaml") != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> manifest = (Map<String, Object>) AssetYamlReader.parse(
                                String.valueOf(stored.get("yaml")));
                        extraManifests.add(manifest);
                    }
                }
            }

            Map<String, Object> result = generation.generateWithModules(contract, extraManifests, outputDir);
            if ("SUCCESS".equals(result.get("status"))) {
                projects.add(Map.of(
                        "name", contractName(contract),
                        "profile", String.valueOf(contractValue(contract, "application", "profile")),
                        "stack", String.valueOf(contractValue(contract, "stack", "profile")),
                        "frontend", String.valueOf(contractValue(contract, "frontends", "template")),
                        "modules", contract.getOrDefault("modules", List.of()),
                        "location", outputDir.toString(),
                        "lastGenerated", java.time.Instant.now().toString(),
                        "status", "SUCCESS"));
            }
            respond(ex, 200, Json.write(result));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of(
                    "status", "FAILED",
                    "category", "Generation Conflict",
                    "message", String.valueOf(e.getMessage()))));
        }
    }

    // ------------------------------------------------------------------
    // Business Modules API (V06-WORK-005)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Runtime API (V06-WORK-006)
    // ------------------------------------------------------------------

    private final RuntimeService runtime = new RuntimeService();
    private final EnvironmentPreflight preflight = new EnvironmentPreflight();

    /**
     * /api/runtime/* — all operations delegate to the generated project's
     * Runtime Recipe (scripts/dev-start.sh | dev-stop.sh | dev-status.sh) or
     * to the project build toolchain with a project-scoped JDK.
     *
     *   POST /api/runtime/preflight       { location }        → Environment Preflight
     *   POST /api/runtime/build           { location, target } → async build (backend|frontend|all)
     *   GET  /api/runtime/build/status    ?location=&target=   → build task state
     *   POST /api/runtime/start           { location, target } → dev-start.sh (--backend|--frontend|all)
     *   POST /api/runtime/stop            { location }         → dev-stop.sh
     *   POST /api/runtime/restart         { location, target } → stop + start
     *   GET  /api/runtime/status          ?location=           → .runtime parsed status
     *   GET  /api/runtime/logs            ?location=&target=&lines= → tail log (redacted)
     *   GET  /api/runtime/open            ?location=           → actual URLs (dynamic ports)
     */
    private void handleRuntime(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String rest = path.substring("/api/runtime".length());

            if (rest.equals("/preflight") && "POST".equals(method)) {
                Map<String, Object> body = Json.parseObject(readBody(ex));
                Path dir = Path.of(String.valueOf(body.getOrDefault("location", "")));
                int requiredJava = requiredJavaMajor(dir);
                respond(ex, 200, Json.write(preflight.preflight(dir, requiredJava)));
                return;
            }
            if (rest.equals("/build") && "POST".equals(method)) {
                Map<String, Object> body = Json.parseObject(readBody(ex));
                Path dir = Path.of(String.valueOf(body.getOrDefault("location", "")));
                String target = String.valueOf(body.getOrDefault("target", "all"));
                RuntimeService.BuildTask task = runtime.build(dir, target, requiredJavaMajor(dir));
                respond(ex, 200, Json.write(Map.of(
                        "id", task.id, "target", task.target, "state", task.state.name())));
                return;
            }
            if (rest.equals("/build/status") && "GET".equals(method)) {
                Path dir = Path.of(String.valueOf(queryParam(ex, "location", "")));
                String target = String.valueOf(queryParam(ex, "target", "all"));
                RuntimeService.BuildTask task = runtime.buildTask(dir, target);
                if (task == null) {
                    respond(ex, 200, Json.write(Map.of("state", "UNKNOWN", "message", "no build task")));
                    return;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", task.id);
                out.put("target", task.target);
                out.put("state", task.state.name());
                out.put("startedAt", task.startedAt);
                out.put("durationMs", task.durationMs());
                out.put("exitCode", task.exitCode);
                out.put("error", task.error);
                out.put("log", task.log.toString());
                respond(ex, 200, Json.write(out));
                return;
            }
            if (rest.equals("/start") && "POST".equals(method)) {
                Map<String, Object> body = Json.parseObject(readBody(ex));
                Path dir = Path.of(String.valueOf(body.getOrDefault("location", "")));
                String target = String.valueOf(body.getOrDefault("target", "all"));
                respond(ex, 200, Json.write(runtime.start(dir, target, requiredJavaMajor(dir))));
                return;
            }
            if (rest.equals("/stop") && "POST".equals(method)) {
                Map<String, Object> body = Json.parseObject(readBody(ex));
                Path dir = Path.of(String.valueOf(body.getOrDefault("location", "")));
                respond(ex, 200, Json.write(runtime.stop(dir, requiredJavaMajor(dir))));
                return;
            }
            if (rest.equals("/restart") && "POST".equals(method)) {
                Map<String, Object> body = Json.parseObject(readBody(ex));
                Path dir = Path.of(String.valueOf(body.getOrDefault("location", "")));
                String target = String.valueOf(body.getOrDefault("target", "all"));
                respond(ex, 200, Json.write(runtime.restart(dir, target, requiredJavaMajor(dir))));
                return;
            }
            if (rest.equals("/status") && "GET".equals(method)) {
                Path dir = Path.of(String.valueOf(queryParam(ex, "location", "")));
                respond(ex, 200, Json.write(runtime.status(dir)));
                return;
            }
            if (rest.equals("/logs") && "GET".equals(method)) {
                Path dir = Path.of(String.valueOf(queryParam(ex, "location", "")));
                String target = String.valueOf(queryParam(ex, "target", "backend"));
                int lines = Integer.parseInt(String.valueOf(queryParam(ex, "lines", "100")));
                respond(ex, 200, Json.write(runtime.logs(dir, target, lines)));
                return;
            }
            if (rest.equals("/open") && "GET".equals(method)) {
                Path dir = Path.of(String.valueOf(queryParam(ex, "location", "")));
                respond(ex, 200, Json.write(runtime.openUrls(dir)));
                return;
            }
            respond(ex, 404, Json.write(Map.of("error", "unknown runtime route: " + rest)));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    /** Infer required Java major from the generated project.yaml stack profile. */
    private static int requiredJavaMajor(Path projectDir) {
        try {
            Path projectYaml = projectDir.resolve("project.yaml");
            if (Files.isRegularFile(projectYaml)) {
                Map<String, Object> doc = (Map<String, Object>) AssetYamlReader.parse(
                        Files.readString(projectYaml, StandardCharsets.UTF_8));
                Object stack = doc.get("stack");
                if (stack instanceof Map<?, ?> sm) {
                    String profile = String.valueOf(sm.get("profile"));
                    int idx = profile.indexOf("java");
                    if (idx >= 0) {
                        return Integer.parseInt(profile.substring(idx + 4).replaceAll("[^0-9].*", ""));
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to default
        }
        return 25;
    }

    private static String queryParam(HttpExchange ex, String key, String def) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return def;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return def;
    }

    private void handleModules(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            // sub-routes: /api/modules/import/...
            if (path.startsWith("/api/modules/import/mysql")) {
                handleMySqlImport(ex, path, method);
                return;
            }
            if (path.startsWith("/api/modules/import/excel")) {
                handleExcelImport(ex, path, method);
                return;
            }
            if (path.startsWith("/api/modules/import/review")) {
                handleReviewResolve(ex);
                return;
            }

            if ("/api/modules".equals(path)) {
                if ("GET".equals(method)) {
                    respond(ex, 200, Json.write(modules.list()));
                } else if ("POST".equals(method)) {
                    Map<String, Object> body = Json.parseObject(readBody(ex));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> manifest = (Map<String, Object>) body.get("manifest");
                    if (manifest == null) {
                        respond(ex, 400, Json.write(Map.of("error", "manifest required")));
                        return;
                    }
                    respond(ex, 200, Json.write(modules.save(manifest)));
                } else {
                    respond(ex, 405, Json.write(Map.of("error", "method not allowed")));
                }
                return;
            }

            // V07-WORK-004: reference target catalog (id + fields) for the
            // Reference Designer — targets come from the SAME contract sources
            // the pipeline consumes (console modules → platform modules → fixtures).
            if ("/api/modules/targets".equals(path) && "GET".equals(method)) {
                respond(ex, 200, Json.write(moduleTargetCatalog()));
                return;
            }

            // V07-WORK-004: module contract validation (lightweight, Builder feedback)
            if ("/api/modules/validate".equals(path) && "POST".equals(method)) {
                Map<String, Object> body = Json.parseObject(readBody(ex));
                @SuppressWarnings("unchecked")
                Map<String, Object> manifest = (Map<String, Object>) body.get("manifest");
                List<Map<String, Object>> errors = ModuleContractValidator.validate(manifest);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("valid", errors.isEmpty());
                out.put("errors", errors);
                respond(ex, 200, Json.write(out));
                return;
            }

            // V07-WORK-004: full contract round-trip (YAML → parsed manifest JSON)
            if (path.startsWith("/api/modules/") && path.endsWith("/contract") && "GET".equals(method)) {
                String id = path.substring("/api/modules/".length(), path.length() - "/contract".length());
                Map<String, Object> module = modules.get(id);
                if (module == null) {
                    respond(ex, 404, Json.write(Map.of("error", "module not found: " + id)));
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> manifest = (Map<String, Object>) AssetYamlReader.parse(
                        String.valueOf(module.get("yaml")));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("manifest", manifest);
                respond(ex, 200, Json.write(out));
                return;
            }

            // /api/modules/{id} or /api/modules/{id}/generate
            String rest = path.substring("/api/modules".length());
            if (rest.startsWith("/") && rest.length() > 1) {
                String[] parts = rest.substring(1).split("/", 2);
                String id = parts[0];
                boolean generate = parts.length > 1 && "generate".equals(parts[1]);
                if (generate && "POST".equals(method)) {
                    handleModuleGenerate(ex, id);
                    return;
                }
                if ("GET".equals(method)) {
                    respond(ex, 200, Json.write(modules.get(id)));
                } else if ("DELETE".equals(method)) {
                    modules.delete(id);
                    respond(ex, 200, Json.write(Map.of("deleted", id)));
                } else {
                    respond(ex, 405, Json.write(Map.of("error", "method not allowed")));
                }
                return;
            }

            respond(ex, 404, Json.write(Map.of("error", "not found")));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleMySqlImport(HttpExchange ex, String path, String method) throws IOException {
        try {
            Map<String, Object> body = Json.parseObject(readBody(ex));
            MySqlImportService.ConnectionInfo info = new MySqlImportService.ConnectionInfo(
                    String.valueOf(body.getOrDefault("host", "127.0.0.1")),
                    Integer.parseInt(String.valueOf(body.getOrDefault("port", 3306))),
                    String.valueOf(body.getOrDefault("database", "")),
                    String.valueOf(body.getOrDefault("username", "")),
                    String.valueOf(body.getOrDefault("password", "")));
            if (path.endsWith("/test")) {
                respond(ex, 200, Json.write(Map.of("ok", mysql.testConnection(info))));
            } else if (path.endsWith("/tables")) {
                respond(ex, 200, Json.write(Map.of("tables", mysql.loadTables(info))));
            } else if (path.endsWith("/discover")) {
                // V07-WORK-005: multi-table metadata discovery → module drafts + candidates.
                // Password is used ONLY for the JDBC connection; never returned/persisted.
                @SuppressWarnings("unchecked")
                List<String> tables = (List<String>) body.getOrDefault("tables", List.of());
                if (tables.isEmpty()) {
                    respond(ex, 400, Json.write(Map.of("error", "tables required")));
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Map<String, String>> mapping =
                        (Map<String, Map<String, String>>) body.get("mapping");
                List<Map<String, Object>> drafts = candidates.discoverMysql(info, tables, mapping);
                respond(ex, 200, Json.write(Map.of("drafts", drafts)));
            } else if (path.endsWith("/import")) {
                String table = String.valueOf(body.getOrDefault("table", ""));
                respond(ex, 200, Json.write(Map.of("fields", mysql.importTable(info, table))));
            } else {
                respond(ex, 404, Json.write(Map.of("error", "unknown mysql import route")));
            }
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleExcelImport(HttpExchange ex, String path, String method) throws IOException {
        try {
            if (path.endsWith("/template") && "GET".equals(method)) {
                byte[] template = XlsxSupport.writeTemplate(new String[]{
                        "column", "field", "type", "label", "required", "primaryKey",
                        "unique", "length", "comment", "searchable", "listVisible",
                        "formVisible", "detailVisible", "dictionary"});
                ex.getResponseHeaders().set("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=module-fields-template.xlsx");
                ex.sendResponseHeaders(200, template.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(template);
                }
                return;
            }
            if (path.endsWith("/template-v2") && "GET".equals(method)) {
                // V07-WORK-005: extended template with optional reference/relation columns
                byte[] template = XlsxSupport.writeTemplate(new String[]{
                        "column", "field", "type", "label", "required", "primaryKey",
                        "unique", "length", "comment", "searchable", "listVisible",
                        "formVisible", "detailVisible", "dictionary",
                        "referenceTarget", "referenceValueField", "referenceLabelField",
                        "relationType", "relationTarget", "mappedBy", "composition"}, null);
                ex.getResponseHeaders().set("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=module-fields-template-v2.xlsx");
                ex.sendResponseHeaders(200, template.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(template);
                }
                return;
            }
            if (path.endsWith("/import") && "POST".equals(method)) {
                byte[] xlsx = ex.getRequestBody().readAllBytes();
                List<List<String>> rows = XlsxSupport.parseRows(xlsx);
                respond(ex, 200, Json.write(Map.of("rows", rows)));
            } else if (path.endsWith("/discover") && "POST".equals(method)) {
                // V07-WORK-005: Excel → field/reference/relation candidates (Review required)
                String moduleId = String.valueOf(queryParam(ex, "moduleId", "excel-module"));
                String entity = String.valueOf(queryParam(ex, "entity", ""));
                byte[] xlsx = ex.getRequestBody().readAllBytes();
                List<List<String>> rows = XlsxSupport.parseRows(xlsx);
                List<String> headers = rows.isEmpty() ? List.of() : rows.get(0);
                List<List<String>> data = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();
                Map<String, Object> draft = candidates.discoverExcel(headers, data, moduleId, entity);
                respond(ex, 200, Json.write(Map.of("draft", draft)));
            } else {
                respond(ex, 404, Json.write(Map.of("error", "unknown excel import route")));
            }
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    /**
     * V07-WORK-005 — Import Review resolution: only CONFIRMED candidates may
     * enter the formal Business Module Contract manifest. Everything else
     * (DETECTED / SUGGESTED / IGNORED) is dropped.
     *
     *   POST /api/modules/import/review/resolve
     *   body: { draft: {...}, decisions: {candidateId: "accept"|"ignore"}, edits: {candidateId: {...}} }
     */
    private void handleReviewResolve(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> body = Json.parseObject(readBody(ex));
            @SuppressWarnings("unchecked")
            Map<String, Object> draft = (Map<String, Object>) body.get("draft");
            if (draft == null) {
                respond(ex, 400, Json.write(Map.of("error", "draft required")));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> decisions = (Map<String, String>) body.get("decisions");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> edits = (Map<String, Map<String, Object>>) body.get("edits");
            Map<String, Object> manifest = candidates.resolveToManifest(draft, decisions, edits);
            respond(ex, 200, Json.write(Map.of("manifest", manifest)));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleModuleGenerate(HttpExchange ex, String id) throws IOException {
        try {
            Map<String, Object> module = modules.get(id);
            if (module == null) {
                respond(ex, 404, Json.write(Map.of("status", "FAILED", "message", "module not found: " + id)));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> manifest = (Map<String, Object>) AssetYamlReader.parse(
                    String.valueOf(module.get("yaml")));
            @SuppressWarnings("unchecked")
            Map<String, Object> moduleDef = (Map<String, Object>) manifest.get("module");

            Map<String, Object> body = Json.parseObject(readBody(ex));
            String location = String.valueOf(body.getOrDefault("location",
                    dataDir.resolve("generated").resolve(id).toString()));
            Path outputDir = Path.of(location);
            if (Files.exists(outputDir) && !isEmptyDir(outputDir)) {
                respond(ex, 409, Json.write(Map.of(
                        "status", "FAILED",
                        "category", "Output Path Invalid",
                        "message", "Output directory is not empty: " + outputDir)));
                return;
            }

            // Build a full project contract referencing this module (existing pipeline)
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("schemaVersion", 1);
            contract.put("project", Map.of(
                    "id", id,
                    "name", String.valueOf(moduleDef.get("name")),
                    "version", "1.0.0",
                    "basePackage", "com.acme.core",
                    "groupId", "com.acme",
                    "artifactId", id));
            contract.put("platform", Map.of("id", "engineering-platform"));
            contract.put("application", Map.of("profile", "enterprise"));
            contract.put("stack", Map.of("profile", "enterprise-java25"));
            contract.put("frontends", List.of(Map.of("id", "admin", "template", "enterprise-admin")));
            contract.put("modules", List.of(id));
            contract.put("capabilities", List.of(
                    Map.of("id", "web"), Map.of("id", "validation"), Map.of("id", "exception-handling"),
                    Map.of("id", "platform-core"), Map.of("id", "authentication"), Map.of("id", "rbac"),
                    Map.of("id", "organization"), Map.of("id", "data-permission"), Map.of("id", "menu"),
                    Map.of("id", "dictionary"), Map.of("id", "operation-log"),
                    Map.of("id", "frontend-shell"), Map.of("id", "frontend-auth"),
                    Map.of("id", "frontend-permission"), Map.of("id", "frontend-enterprise-management"),
                    // V06-WORK-006: module-generated projects also carry the Runtime Recipe
                    Map.of("id", "runtime-recipe")));
            contract.put("quality", Map.of("minimum", "Q2"));

            Map<String, Object> result = generation.generateWithModules(contract, List.of(manifest), outputDir);
            if ("SUCCESS".equals(result.get("status"))) {
                projects.add(Map.of(
                        "name", moduleDef.get("name"),
                        "profile", "enterprise",
                        "stack", "enterprise-java25",
                        "frontend", "enterprise-admin",
                        "modules", List.of(id),
                        "location", outputDir.toString(),
                        "lastGenerated", java.time.Instant.now().toString(),
                        "status", "SUCCESS"));
            }
            respond(ex, 200, Json.write(result));
        } catch (Exception e) {
            respond(ex, 500, Json.write(Map.of(
                    "status", "FAILED",
                    "category", "Generation Conflict",
                    "message", String.valueOf(e.getMessage()))));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String contractName(Map<String, Object> contract) {
        Object p = contract.get("project");
        if (p instanceof Map<?, ?> m && m.get("name") != null) return String.valueOf(m.get("name"));
        return "Unnamed project";
    }

    @SuppressWarnings("unchecked")
    private static Object contractValue(Map<String, Object> contract, String section, String key) {
        Object s = contract.get(section);
        if (s instanceof Map<?, ?> m) return m.get(key);
        if ("frontends".equals(section)) {
            Object f = contract.get("frontends");
            if (f instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> fm) {
                return fm.get(key);
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // V07-WORK-004: reference target catalog for the Reference Designer.
    // Targets come from the SAME contract sources the pipeline consumes:
    //   console module store (dataDir/modules) + platform fixtures.
    // ------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> moduleTargetCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        // 1. console module store
        try {
            for (Map<String, Object> m : modules.list()) {
                String id = String.valueOf(m.get("id"));
                if (!seen.add(id)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("fields", moduleFieldsOf((String) m.get("yaml")));
                out.add(entry);
            }
        } catch (IOException e) {
            // ignore store read errors — continue with fixtures
        }

        // 2. platform fixtures (v07 reference, v06 generic, v06 supplier)
        List<Path> fixtureDirs = List.of(
                platformRoot.resolve("tests/fixtures/v07-reference/generic/modules"),
                platformRoot.resolve("tests/fixtures/v06-reference/generic/modules"),
                platformRoot.resolve("tests/fixtures/v06-reference/supplier/modules"));
        for (Path dir : fixtureDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path f : stream.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                    String id = f.getFileName().toString().replace(".yaml", "");
                    if (!seen.add(id)) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id);
                    entry.put("fields", moduleFieldsOf(Files.readString(f, StandardCharsets.UTF_8)));
                    out.add(entry);
                }
            } catch (IOException ignored) {
                // skip unreadable fixture dir
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> moduleFieldsOf(String yaml) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Map<String, Object> manifest = (Map<String, Object>) AssetYamlReader.parse(yaml);
            Map<String, Object> biz = (Map<String, Object>) manifest.get("business");
            if (biz == null) return out;
            Map<String, Object> entity = (Map<String, Object>) biz.get("entity");
            if (entity == null) return out;
            Object fields = entity.get("fields");
            if (!(fields instanceof List<?> fl)) return out;
            for (Object o : fl) {
                if (!(o instanceof Map<?, ?> fm)) continue;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", fm.get("name"));
                f.put("type", fm.get("type"));
                out.add(f);
            }
        } catch (Exception ignored) {
            // malformed manifest → no fields
        }
        return out;
    }

    private static boolean isEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) return true;
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0]
                : "/home/administrator/workspace/engineering-platform");
        Path dataDir = Path.of(args.length > 1 ? args[1]
                : root.resolve("console/console-data").toString());
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8099;
        Files.createDirectories(dataDir);
        new ConsoleServer(root, dataDir).start(port);
    }
}
