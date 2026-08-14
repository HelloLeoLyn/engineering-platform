package com.engineeringplatform.cli;

import com.engineeringplatform.generator.contracts.ConformanceFinding;
import com.engineeringplatform.generator.contracts.ConformanceResult;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.core.AssetAwareResolver;
import com.engineeringplatform.generator.core.AssetProjectGenerator;
import com.engineeringplatform.generator.core.AssetRepository;
import com.engineeringplatform.generator.core.AssetYamlReader;
import com.engineeringplatform.generator.core.ConformanceValidator;
import com.engineeringplatform.generator.core.GenerationException;
import com.engineeringplatform.generator.core.ManifestRuntimeValidator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Engineering Platform CLI V1 (V03-WORK-002).
 *
 * Commands: validate / resolve / generate / conformance (+ --help / --version).
 * Reuses the existing V0.2/V0.3 core (AssetRepository / AssetAwareResolver /
 * AssetProjectGenerator / ConformanceValidator) — no rewritten pipeline.
 *
 * Exit codes (contract): 0 = SUCCESS, 1 = platform/validation/resolution/
 * generation/conformance failure, 2 = CLI usage error.
 */
public final class EngineeringPlatformCli {

    public static final String VERSION = "0.3.0";

    private final Path platformRoot;
    private final PrintStream out;
    private final PrintStream err;

    public static void main(String[] args) {
        int code = new EngineeringPlatformCli(discoverRoot(args), System.out, System.err).run(args);
        System.exit(code);
    }

    /** Locates the platform root: --platform-root argument, else current directory. */
    static Path discoverRoot(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--platform-root")) {
                return Path.of(args[i + 1]).toAbsolutePath().normalize();
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    public EngineeringPlatformCli(Path platformRoot, PrintStream out, PrintStream err) {
        this.platformRoot = platformRoot;
        this.out = out;
        this.err = err;
    }

    /** Package-visible for tests; returns the exit code without System.exit. */
    int run(String[] args) {
        String[] clean = stripGlobalOptions(args);
        if (clean.length == 0) {
            usage(err);
            return 2;
        }
        String command = clean[0];
        switch (command) {
            case "--help", "-h", "help" -> {
                usage(out);
                return 0;
            }
            case "--version", "-V", "version" -> {
                out.println("Engineering Platform CLI " + VERSION);
                return 0;
            }
            case "validate" -> {
                return validate(rest(clean));
            }
            case "resolve" -> {
                return resolve(rest(clean));
            }
            case "generate" -> {
                return generate(rest(clean));
            }
            case "conformance" -> {
                return conformance(rest(clean));
            }
            default -> {
                err.println("ERROR: unknown command: " + command);
                usage(err);
                return 2;
            }
        }
    }

    /** Removes global options (--platform-root <dir>) before command dispatch. */
    private static String[] stripGlobalOptions(String[] args) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--platform-root") && i + 1 < args.length) {
                i++; // skip the option value
                continue;
            }
            result.add(args[i]);
        }
        return result.toArray(new String[0]);
    }

    // ---- commands ----

    private int validate(String[] args) {
        if (args.length != 1 || args[0].startsWith("--")) {
            err.println("usage: ep validate <project.yaml>");
            return 2;
        }
        Path manifestPath = resolvePath(args[0]);
        if (!Files.isRegularFile(manifestPath)) {
            err.println("ERROR: manifest file not found: " + manifestPath);
            return 1;
        }
        try {
            Map<String, Object> manifest = readYaml(manifestPath);
            ManifestRuntimeValidator validator = new ManifestRuntimeValidator();
            if (validator.isValid("project", manifest)) {
                out.println("[OK] Manifest valid: " + manifestPath);
                return 0;
            }
            err.println("[FAIL] Manifest invalid: " + manifestPath);
            for (String message : validator.validationErrors("project", manifest)) {
                err.println("  - " + message);
            }
            return 1;
        } catch (Exception e) {
            err.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    private int resolve(String[] args) {
        if (args.length != 1 || args[0].startsWith("--")) {
            err.println("usage: ep resolve <project.yaml>");
            return 2;
        }
        Path manifestPath = resolvePath(args[0]);
        if (!Files.isRegularFile(manifestPath)) {
            err.println("ERROR: manifest file not found: " + manifestPath);
            return 1;
        }
        try {
            ResolutionResult resolution = resolveProject(manifestPath);
            if (resolution.status() != ResolutionResult.Status.SUCCESS) {
                err.println("[FAIL] Resolution failed: " + manifestPath);
                for (var error : resolution.errors()) {
                    err.println("  - " + error.code() + ": " + error.message());
                }
                return 1;
            }
            printResolveSummary(resolution.effectiveProject());
            return 0;
        } catch (Exception e) {
            err.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    private int generate(String[] args) {
        String manifestArg = null;
        String outputArg = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output")) {
                if (i + 1 >= args.length) {
                    err.println("ERROR: --output requires a directory");
                    return 2;
                }
                outputArg = args[i + 1];
                i++;
            } else if (!args[i].startsWith("--")) {
                manifestArg = args[i];
            } else {
                err.println("ERROR: unknown option: " + args[i]);
                return 2;
            }
        }
        if (manifestArg == null) {
            err.println("usage: ep generate <project.yaml> --output <dir>");
            return 2;
        }
        if (outputArg == null) {
            err.println("ERROR: --output is required");
            return 2;
        }
        Path manifestPath = resolvePath(manifestArg);
        if (!Files.isRegularFile(manifestPath)) {
            err.println("ERROR: manifest file not found: " + manifestPath);
            return 1;
        }
        Path outputDir = resolvePath(outputArg);
        try {
            if (Files.exists(outputDir) && !isEmptyDir(outputDir)) {
                err.println("ERROR: output directory is not empty (refusing to overwrite): " + outputDir);
                err.println("  use a fresh output directory, e.g. ep generate <project.yaml> --output ./" + manifestPath.getFileName() + "-gen");
                return 1;
            }
            ResolutionResult resolution = resolveProject(manifestPath);
            if (resolution.status() != ResolutionResult.Status.SUCCESS) {
                err.println("[FAIL] Resolution failed: " + manifestPath);
                for (var error : resolution.errors()) {
                    err.println("  - " + error.code() + ": " + error.message());
                }
                return 1;
            }
            Files.createDirectories(outputDir);
            AssetRepository repo = AssetRepository.load(platformRoot);
            AssetProjectGenerator.GenerationResult result =
                    new AssetProjectGenerator().generate(resolution.effectiveProject(), repo, outputDir);
            if (result.execution().status() != com.engineeringplatform.generator.contracts.ExecutionResult.ExecutionStatus.SUCCESS) {
                err.println("[FAIL] Generation failed: " + manifestPath);
                for (String message : result.execution().messages()) {
                    err.println("  - " + message);
                }
                return 1;
            }
            out.println("Generated: " + outputDir);
            out.println("Files: " + result.generatedFiles().size());
            out.println();
            out.println("Next:");
            out.println("  cd " + outputDir);
            out.println("  mvn test");
            printPlaceholderGuidance(repo, resolution.effectiveProject());
            return 0;
        } catch (GenerationException | IOException e) {
            err.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    private int conformance(String[] args) {
        String manifestArg = null;
        String projectDirArg = null;
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                if (manifestArg == null) {
                    manifestArg = arg;
                } else if (projectDirArg == null) {
                    projectDirArg = arg;
                }
            }
        }
        if (manifestArg == null || projectDirArg == null) {
            err.println("usage: ep conformance <project.yaml> <project-dir>");
            return 2;
        }
        Path manifestPath = resolvePath(manifestArg);
        if (!Files.isRegularFile(manifestPath)) {
            err.println("ERROR: manifest file not found: " + manifestPath);
            return 1;
        }
        Path projectDir = resolvePath(projectDirArg);
        if (!Files.isDirectory(projectDir)) {
            err.println("ERROR: project directory not found: " + projectDir);
            return 1;
        }
        try {
            ResolutionResult resolution = resolveProject(manifestPath);
            if (resolution.status() != ResolutionResult.Status.SUCCESS) {
                err.println("[FAIL] Resolution failed: " + manifestPath);
                for (var error : resolution.errors()) {
                    err.println("  - " + error.code() + ": " + error.message());
                }
                return 1;
            }
            AssetRepository repo = AssetRepository.load(platformRoot);
            ConformanceResult conformance = new ConformanceValidator(repo)
                    .validate(resolution.effectiveProject(), projectDir);
            out.println("Conformance: " + conformance.status());
            for (ConformanceFinding finding : conformance.findings()) {
                out.println("  [" + finding.severity() + "] " + finding.ruleId()
                        + (finding.path() != null ? " " + finding.path() : "")
                        + (finding.message() != null ? " - " + finding.message() : ""));
            }
            return conformance.status() == ConformanceResult.Status.PASS ? 0 : 1;
        } catch (IOException e) {
            err.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    // ---- helpers ----

    private ResolutionResult resolveProject(Path manifestPath) throws IOException {
        Map<String, Object> projectManifest = readYaml(manifestPath);
        Path platformFile = platformRoot.resolve("platform.yaml");
        if (!Files.isRegularFile(platformFile)) {
            throw new IOException("platform.yaml not found under " + platformRoot);
        }
        Map<String, Object> platformManifest = readYaml(platformFile);
        AssetRepository repo = AssetRepository.load(platformRoot);
        return new AssetAwareResolver(new ManifestRuntimeValidator())
                .resolve(repo, platformManifest, projectManifest);
    }

    private void printResolveSummary(EffectiveProjectModel epm) {
        String id = epm.identity() == null ? "?" : String.valueOf(epm.identity().getOrDefault("id", "?"));
        Object java = epm.technology() == null ? null : epm.technology().get("java");
        Object quality = epm.quality() == null ? null : epm.quality().get("minimum");
        out.println("Project: " + id);
        if (java != null) {
            out.println("Java: " + java);
        }
        if (quality != null) {
            out.println("Quality: " + quality);
        }
        out.println("Capabilities:");
        for (var capability : epm.capabilities()) {
            out.println("  " + capability.id() + " " + capability.activation().code().toLowerCase());
        }
        out.println("Providers:");
        for (var provider : epm.providers()) {
            out.println("  " + String.join(", ", provider.implementsList()) + " -> " + provider.id());
        }
        if (epm.warnings() != null && !epm.warnings().isEmpty()) {
            out.println("Warnings:");
            for (String warning : epm.warnings()) {
                out.println("  - " + warning);
            }
        }
    }

    private static Map<String, Object> readYaml(Path path) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(path, StandardCharsets.UTF_8));
        return manifest;
    }

    /** User-supplied paths resolve against the caller's working directory (cwd-independent). */
    private Path resolvePath(String input) {
        return Path.of(input).toAbsolutePath().normalize();
    }

    private static boolean isEmptyDir(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** V03-WORK-003: tells the developer which configuration references need values (never outputs secrets). */
    private void printPlaceholderGuidance(AssetRepository repo, EffectiveProjectModel epm) {
        List<String> referenceKeys = new ArrayList<>();
        List<String> enabled = new ArrayList<>();
        epm.capabilities().forEach(c -> enabled.add(c.id()));
        epm.providers().forEach(p -> enabled.add(p.id()));
        for (String assetId : enabled) {
            for (AssetRepository.ConfigSpec config : repo.assetConfiguration(assetId)) {
                if (config.type().equals("secretRef") || config.type().equals("configRef")) {
                    referenceKeys.add(config.key());
                }
            }
        }
        if (!referenceKeys.isEmpty()) {
            out.println();
            out.println("Note: the following configuration references need values before runtime:");
            for (String key : referenceKeys) {
                out.println("  " + key + " -> env " + key.toUpperCase(java.util.Locale.ROOT)
                        .replace('.', '_').replace('-', '_'));
            }
        }
    }

    private static String[] rest(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    private void usage(PrintStream target) {
        target.println("Engineering Platform CLI " + VERSION);
        target.println("usage: ep <command> [options]");
        target.println();
        target.println("commands:");
        target.println("  validate <project.yaml>                 validate project manifest");
        target.println("  resolve <project.yaml>                  resolve manifest into EffectiveProjectModel");
        target.println("  generate <project.yaml> --output <dir>  generate a Spring Boot project");
        target.println("  conformance <project.yaml> <project-dir> run engineering conformance");
        target.println();
        target.println("options:");
        target.println("  --platform-root <dir>   platform root (default: current directory)");
        target.println("  --help / --version      show help / version");
        target.println();
        target.println("exit codes: 0 = SUCCESS, 1 = failure, 2 = usage error");
    }
}
