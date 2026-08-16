package com.engineeringplatform.console;

import com.engineeringplatform.generator.core.AssetProjectGenerator;
import com.engineeringplatform.generator.core.AssetRepository;
import com.engineeringplatform.generator.core.AssetResolution;
import com.engineeringplatform.generator.core.AssetYamlReader;
import com.engineeringplatform.generator.core.CompleteResolver;
import com.engineeringplatform.generator.core.ManifestRuntimeValidator;
import com.engineeringplatform.generator.core.AssetContext;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V06-WORK-004 — Console GenerationService.
 *
 * Adapter over the EXISTING generator pipeline. No second resolver/planner/
 * generator/execution engine: this service only translates a Console project
 * contract (already shaped as Project Contract V2 YAML) into the existing
 * AssetRepository → CompleteResolver → AssetProjectGenerator flow — exactly the
 * same flow the CLI (`ep generate`) uses.
 */
public final class GenerationService {

    private final Path platformRoot;
    private final Path dataDir;

    public GenerationService(Path platformRoot, Path dataDir) {
        this.platformRoot = platformRoot;
        this.dataDir = dataDir;
    }

    /** Catalog of selectable business modules, sourced from existing registry data. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> moduleCatalog() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        // modules registry (module manifests) — entries live at top level
        Path modulesYaml = platformRoot.resolve("registry/modules.yaml");
        if (Files.isRegularFile(modulesYaml)) {
            Map<String, Object> reg = (Map<String, Object>) AssetYamlReader.parse(Files.readString(modulesYaml));
            Object entries = reg.get("entries");
            if (entries instanceof List<?> list) {
                for (Object e : list) {
                    Map<String, Object> entry = (Map<String, Object>) e;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", entry.get("id"));
                    m.put("description", entry.get("description"));
                    m.put("kind", "module");
                    out.add(m);
                }
            }
        }
        // reference business capabilities (product-reference → Product)
        // Only expose a *-reference capability when there is NO matching module
        // registry entry (supplier-reference is shadowed by the supplier module).
        Set<String> moduleIds = out.stream().map(m -> String.valueOf(m.get("id"))).collect(Collectors.toSet());
        Path capsYaml = platformRoot.resolve("registry/capabilities.yaml");
        if (Files.isRegularFile(capsYaml)) {
            Map<String, Object> reg = (Map<String, Object>) AssetYamlReader.parse(Files.readString(capsYaml));
            Object entries = reg.get("entries");
            if (entries instanceof List<?> list) {
                for (Object e : list) {
                    Map<String, Object> entry = (Map<String, Object>) e;
                    String id = String.valueOf(entry.get("id"));
                    if (id.endsWith("-reference") && !id.startsWith("frontend-")) {
                        String moduleShadow = id.substring(0, id.length() - "-reference".length());
                        if (moduleIds.contains(moduleShadow)) {
                            continue; // already offered as a module (e.g. supplier)
                        }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", id);
                        m.put("description", entry.get("description"));
                        m.put("kind", "capability");
                        out.add(m);
                    }
                }
            }
        }
        return out;
    }

    /** Catalog of certified application profiles from platform.yaml. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> applicationProfiles() throws IOException {
        Map<String, Object> platform = readPlatform();
        Map<String, Object> profiles = (Map<String, Object>) platform.get("applicationProfiles");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : profiles.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("status", v.get("status"));
            m.put("description", v.get("description"));
            out.add(m);
        }
        return out;
    }

    /** Catalog of certified stack profiles from platform.yaml. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> stackProfiles() throws IOException {
        Map<String, Object> platform = readPlatform();
        Map<String, Object> tech = (Map<String, Object>) platform.get("technology");
        Map<String, Object> stacks = (Map<String, Object>) tech.get("stackProfiles");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : stacks.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("status", v.get("status"));
            m.put("description", v.get("description"));
            m.put("details", v);
            out.add(m);
        }
        return out;
    }

    /** Catalog of frontend templates from platform.yaml. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> frontendTemplates() throws IOException {
        Map<String, Object> platform = readPlatform();
        Map<String, Object> templates = (Map<String, Object>) platform.get("frontendTemplates");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : templates.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("status", v.get("status"));
            m.put("description", v.get("description"));
            out.add(m);
        }
        return out;
    }

    /** Generate a project from a Console contract (Project Contract V2 map). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(Map<String, Object> projectContract, Path outputDir) throws IOException {
        return generateWithModules(projectContract, List.of(), outputDir);
    }

    /**
     * Generate a project, additionally loading the given module manifests
     * (Console-created Business Module Contracts) into the pipeline.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateWithModules(Map<String, Object> projectContract,
                                                   List<Map<String, Object>> extraManifests,
                                                   Path outputDir) throws IOException {
        // 1. write contract to output (for traceability, like CLI)
        Files.createDirectories(outputDir);
        Path manifestPath = outputDir.resolve("project.yaml");
        Files.writeString(manifestPath, YamlDumper.dump(projectContract), StandardCharsets.UTF_8);

        // 2. existing pipeline — identical to CLI `ep generate`
        AssetRepository repo = AssetRepository.load(platformRoot);
        Map<String, Object> platform = readPlatform();
        List<Object> caps = (List<Object>) projectContract.get("capabilities");
        AssetContext ctx = AssetResolution.resolve(repo, ids(caps), platform);

        Map<String, Map<String, Object>> manifests = loadModuleManifests(projectContract);
        for (Map<String, Object> m : extraManifests) {
            Map<String, Object> mm = (Map<String, Object>) m.get("module");
            if (mm != null) {
                manifests.put(String.valueOf(mm.get("id")), m);
            }
        }
        Map<String, Set<String>> registry = new LinkedHashMap<>();
        Set<String> knownModules = new LinkedHashSet<>(Set.of("sample-customer", "supplier", "customer-lite", "warehouse-lite"));
        // V06-WORK-005: registry snapshot must include modules referenced by the
        // Console-created contract (they live in console-data/modules, not the
        // platform modules registry) — otherwise resolution fails as unknown.
        Object refModules = projectContract.get("modules");
        if (refModules instanceof List<?> ml) {
            for (Object o : ml) {
                knownModules.add(o instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : String.valueOf(o));
            }
        }
        registry.put("modules", Set.copyOf(knownModules));
        registry.put("capabilities", Set.copyOf(repo.capabilities().keySet()));
        registry.put("providers", Set.copyOf(repo.providers().keySet()));

        ResolverInput input = new ResolverInput(platform, projectContract, manifests,
                repo.toProviderManifests(), registry);
        ResolutionResult result = new CompleteResolver(new ManifestRuntimeValidator(), ctx).resolve(input);

        Map<String, Object> response = new LinkedHashMap<>();
        if (result.status() != ResolutionResult.Status.SUCCESS) {
            response.put("status", "FAILED");
            List<Map<String, Object>> errors = new ArrayList<>();
            for (var error : result.errors()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("code", error.code());
                em.put("message", error.message());
                em.put("category", classifyError(error.code(), error.message()));
                errors.add(em);
            }
            response.put("errors", errors);
            return response;
        }

        EffectiveProjectModel epm = result.effectiveProject();
        AssetProjectGenerator.GenerationResult gen =
                new AssetProjectGenerator().generate(epm, repo, outputDir);

        if (gen.execution().status() != com.engineeringplatform.generator.contracts.ExecutionResult.ExecutionStatus.SUCCESS) {
            response.put("status", "FAILED");
            response.put("errors", List.of(Map.of(
                    "code", "GENERATION_FAILED", "category", "Generation Conflict",
                    "message", String.join("; ", gen.execution().messages()))));
            return response;
        }

        // V06-WORK-006: make Runtime Recipe scripts executable so dev-start.sh /
        // dev-stop.sh / dev-status.sh can run via ./scripts/* (Console + CLI both benefit).
        makeScriptsExecutable(outputDir);

        response.put("status", "SUCCESS");
        response.put("generatedFiles", gen.generatedFiles().size());
        response.put("outputDir", outputDir.toString());
        response.put("modules", epm.businessModules().stream()
                .map(m -> m.id()).collect(Collectors.toList()));
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadModuleManifests(Map<String, Object> contract) throws IOException {
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
        Object modules = contract.get("modules");
        if (modules instanceof List<?> list) {
            for (Object o : list) {
                String moduleId = o instanceof Map<?, ?> m ? String.valueOf(((Map<?, ?>) m).get("id")) : String.valueOf(o);
                Path candidate = platformRoot.resolve("modules").resolve(moduleId + ".yaml");
                if (Files.isRegularFile(candidate)) {
                    manifests.put(moduleId,
                            (Map<String, Object>) AssetYamlReader.parse(Files.readString(candidate)));
                    continue;
                }
                // fall back to fixture manifests (V06 reference modules live in fixtures)
                Path fixture = platformRoot.resolve("tests/fixtures/v06-reference/generic-supplier/modules")
                        .resolve(moduleId + ".yaml");
                if (Files.isRegularFile(fixture)) {
                    manifests.put(moduleId,
                            (Map<String, Object>) AssetYamlReader.parse(Files.readString(fixture)));
                    continue;
                }
                // generic proof modules (customer-lite / warehouse-lite) live under
                // tests/fixtures/v06-reference/generic/modules
                Path genericFixture = platformRoot.resolve("tests/fixtures/v06-reference/generic/modules")
                        .resolve(moduleId + ".yaml");
                if (Files.isRegularFile(genericFixture)) {
                    manifests.put(moduleId,
                            (Map<String, Object>) AssetYamlReader.parse(Files.readString(genericFixture)));
                }
            }
        }
        return manifests;
    }

    /** Best-effort chmod +x on generated Runtime Recipe scripts. */
    private static void makeScriptsExecutable(Path projectDir) {
        Path scripts = projectDir.resolve("scripts");
        if (!Files.isDirectory(scripts)) return;
        for (String name : List.of("dev-start.sh", "dev-stop.sh", "dev-status.sh")) {
            Path p = scripts.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    p.toFile().setExecutable(true, false);
                } catch (Exception ignored) {
                    // non-fatal: scripts still runnable via `bash scripts/...`
                }
            }
        }
    }

    private Map<String, Object> readPlatform() throws IOException {
        return (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(platformRoot.resolve("platform.yaml")));
    }

    private static List<String> ids(List<Object> caps) {
        List<String> out = new ArrayList<>();
        if (caps == null) return out;
        for (Object o : caps) {
            if (o instanceof String s) out.add(s);
            else if (o instanceof Map<?, ?> m && m.get("id") != null) out.add(String.valueOf(m.get("id")));
        }
        return out;
    }

    private static String classifyError(String code, String message) {
        String m = (message == null ? "" : message).toLowerCase(java.util.Locale.ROOT);
        if (code != null && code.contains("PROFILE")) return "Unsupported Application Profile";
        if (code != null && code.contains("STACK")) return "Unsupported Stack Profile";
        if (code != null && code.contains("TEMPLATE")) return "Unsupported Frontend Template";
        if (code != null && code.contains("MODULE")) return "Unknown Module";
        if (code != null && code.contains("OUTPUT") || m.contains("output")) return "Output Path Invalid";
        if (code != null && code.contains("CONFLICT") || m.contains("conflict")) return "Generation Conflict";
        return "Invalid Project Configuration";
    }
}
