package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.OperationType;
import com.engineeringplatform.generator.contracts.OverwritePolicy;
import com.engineeringplatform.generator.contracts.Ownership;
import com.engineeringplatform.generator.contracts.ResolvedCapability;
import com.engineeringplatform.generator.contracts.ResolvedProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asset-driven project generation (V02-WORK-004).
 *
 * Flow: EPM (from the existing resolver) + AssetRepository
 *   -> dependency assembly (dedup/conflict)
 *   -> file set (base project + asset templates + test source)
 *   -> GenerationOperations -> existing GenerationPlanner -> existing GeneratorExecutor
 *
 * No bypass: DryRun / Staging / Precondition / Transaction / Rollback / Path Safety all stay active.
 * Asset content is the source of truth for exception-handling / logging / audit / mybatis-plus;
 * nothing is re-hardcoded in the planner.
 */
public final class AssetProjectGenerator {

    public static final String GENERATOR_VERSION = "0.2.0-work4";
    public static final String SPRING_BOOT_VERSION = "3.5.3";

    public record Options(String basePackage, String projectName, String groupId, String artifactId,
                          String projectVersion, Map<String, Object> providedConfig) {
        public Options {
            providedConfig = providedConfig == null ? Map.of() : Map.copyOf(providedConfig);
        }
    }

    public record GenerationResult(GenerationPlan plan, ExecutionResult execution,
                                   List<AssetRepository.MavenDependency> dependencies,
                                   List<String> generatedFiles) {
    }

    private record ProjectFile(String target, String content, Ownership ownership,
                               String sourceAsset, boolean render) {
    }

    private final GenerationPlanner planner;
    private final GeneratorExecutor executor;

    public AssetProjectGenerator() {
        this(new GenerationPlanner(), new GeneratorExecutor());
    }

    public AssetProjectGenerator(GenerationPlanner planner, GeneratorExecutor executor) {
        this.planner = planner;
        this.executor = executor;
    }

    public GenerationResult generate(EffectiveProjectModel epm, AssetRepository repo,
                                     Options options, java.nio.file.Path targetDir) throws IOException {
        String basePackage = options.basePackage();
        if (basePackage == null || basePackage.isBlank()) {
            throw new GenerationException("basePackage is required (project.yaml project.basePackage)");
        }
        String projectName = options.projectName() == null ? "generated-app" : options.projectName();
        String groupId = options.groupId() == null ? "com.engineeringplatform" : options.groupId();
        String artifactId = options.artifactId() == null ? "generated-app" : options.artifactId();
        String projectVersion = options.projectVersion() == null ? "0.1.0" : options.projectVersion();

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("package", basePackage);
        vars.put("basePackage", basePackage);
        vars.put("basePackagePath", basePackage.replace('.', '/'));
        vars.put("projectName", projectName);
        vars.put("Name", camelize(projectName));
        vars.put("artifactId", artifactId);
        vars.put("groupId", groupId);

        // 1. dependency assembly (conflict -> GenerationException before any write)
        List<AssetRepository.MavenDependency> dependencies = DependencyAssembler.assemble(epm, repo);
        validateRequiredConfig(epm, repo, options);

        // 2. file set
        Map<String, ProjectFile> files = new LinkedHashMap<>();
        addBaseFiles(files, vars, dependencies, groupId, artifactId, projectVersion, repo, epm, options);
        addAssetFiles(files, repo, epm, options, vars);

        // 3. operations (plan stage: path safety checked; failure stops before any write)
        List<GenerationOperation> operations = new ArrayList<>();
        int index = 0;
        for (ProjectFile file : files.values()) {
            index++;
            String target = file.target();
            try {
                PathSafety.validateRelative(target, false);
            } catch (PathSafety.PathSafetyException e) {
                throw new GenerationException("invalid template target '" + target + "': " + e.getMessage());
            }
            operations.add(GenerationOperation.builder()
                    .operationId("op-" + index)
                    .type(OperationType.CREATE_FILE)
                    .targetPath(target)
                    .ownership(file.ownership())
                    .overwritePolicy(OverwritePolicy.ALLOWED)
                    .templateSource(file.sourceAsset())
                    .content(file.content())
                    .reason("asset-driven generation (V02-WORK-004)")
                    .build());
        }

        // 4. existing planner + executor (DryRun/Staging/Precondition/Transaction/Rollback active)
        GenerationPlan plan = planner.plan(epm, GENERATOR_VERSION, "SCAFFOLD", operations);
        ExecutionResult execution = executor.execute(plan, targetDir);
        return new GenerationResult(plan, execution, dependencies, List.copyOf(files.keySet()));
    }

    // ---- base project files ----

    private void addBaseFiles(Map<String, ProjectFile> files, Map<String, String> vars,
                              List<AssetRepository.MavenDependency> dependencies,
                              String groupId, String artifactId, String projectVersion,
                              AssetRepository repo, EffectiveProjectModel epm, Options options) {
        addFile(files, "pom.xml", buildPom(groupId, artifactId, projectVersion, dependencies),
                Ownership.GENERATED, "base-project", false);
        addFile(files, "src/main/resources/application.yml",
                buildApplicationYml(vars, repo, epm, options),
                Ownership.GENERATED, "base-project", false);
        addFile(files, "src/main/java/" + vars.get("basePackagePath") + "/" + vars.get("Name") + "Application.java",
                render(APPLICATION_TEMPLATE, vars), Ownership.GENERATED, "base-project", true);
        addFile(files, "src/test/java/" + vars.get("basePackagePath") + "/common/error/ApiErrorTest.java",
                render(API_ERROR_TEST_TEMPLATE, vars), Ownership.GENERATED, "base-project", true);
    }

    private void addAssetFiles(Map<String, ProjectFile> files, AssetRepository repo,
                               EffectiveProjectModel epm, Options options, Map<String, String> vars)
            throws IOException {
        List<String> enabled = new ArrayList<>();
        for (ResolvedCapability capability : epm.capabilities()) {
            enabled.add(capability.id());
        }
        for (ResolvedProvider provider : epm.providers()) {
            enabled.add(provider.id());
        }
        for (String assetId : enabled) {
            for (AssetRepository.AssetFileSpec spec : repo.assetFiles(assetId)) {
                String template;
                try {
                    template = repo.readAssetFile(assetId, spec.source());
                } catch (IOException e) {
                    throw new GenerationException("missing asset template: " + assetId + "/" + spec.source(), e);
                }
                Map<String, String> assetVars = new LinkedHashMap<>(vars);
                assetVars.put("asset.version", assetVersion(repo, assetId));
                for (AssetRepository.ConfigSpec config : repo.assetConfiguration(assetId)) {
                    Object value = options.providedConfig().getOrDefault(config.key(), config.defaultValue());
                    if (value != null) {
                        assetVars.put(config.key(), String.valueOf(value));
                    }
                }
                String content = spec.render()
                        ? render(template, assetVars)
                        : template;
                addFile(files, resolveTarget(spec.target(), vars), content, spec.ownership(), assetId, spec.render());
            }
        }
    }

    private static String assetVersion(AssetRepository repo, String assetId) {
        var asset = repo.capability(assetId);
        if (asset == null) {
            asset = repo.provider(assetId);
        }
        return asset == null ? "0.1.0" : asset.version();
    }

    private static String resolveTarget(String target, Map<String, String> vars) {
        String resolved = target;
        if (vars.get("basePackagePath") != null) {
            resolved = resolved.replace("{package}", vars.get("basePackagePath"));
        }
        if (vars.get("Name") != null) {
            resolved = resolved.replace("{Name}", vars.get("Name"));
        }
        return resolved;
    }

    private void addFile(Map<String, ProjectFile> files, String target, String content,
                         Ownership ownership, String sourceAsset, boolean render) {
        ProjectFile existing = files.get(target);
        if (existing != null && !existing.content().equals(content)) {
            throw new GenerationException("generated file conflict for " + target
                    + " between " + existing.sourceAsset() + " and " + sourceAsset);
        }
        files.put(target, new ProjectFile(target, content, ownership, sourceAsset, render));
    }

    private static String render(String template, Map<String, String> vars) {
        try {
            return TemplateRenderer.render(template, vars);
        } catch (IllegalArgumentException e) {
            throw new GenerationException("render failed: " + e.getMessage(), e);
        }
    }

    // ---- required configuration validation ----

    private void validateRequiredConfig(EffectiveProjectModel epm, AssetRepository repo, Options options) {
        List<String> enabled = new ArrayList<>();
        for (ResolvedCapability capability : epm.capabilities()) {
            enabled.add(capability.id());
        }
        for (ResolvedProvider provider : epm.providers()) {
            enabled.add(provider.id());
        }
        for (String assetId : enabled) {
            for (AssetRepository.ConfigSpec config : repo.assetConfiguration(assetId)) {
                boolean referenceType = config.type().equals("secretRef") || config.type().equals("configRef");
                if (config.required() && config.defaultValue() == null
                        && !referenceType && !options.providedConfig().containsKey(config.key())) {
                    throw new GenerationException("missing required configuration: " + config.key()
                            + " (asset " + assetId + ")");
                }
            }
        }
    }

    // ---- pom.xml ----

    private String buildPom(String groupId, String artifactId, String version,
                            List<AssetRepository.MavenDependency> dependencies) {
        StringBuilder deps = new StringBuilder();
        for (AssetRepository.MavenDependency dep : dependencies) {
            deps.append("        <dependency>\n")
                    .append("            <groupId>").append(dep.groupId()).append("</groupId>\n")
                    .append("            <artifactId>").append(dep.artifactId()).append("</artifactId>\n");
            if (dep.version() != null) {
                deps.append("            <version>").append(dep.version()).append("</version>\n");
            }
            if (dep.scope() != null && !dep.scope().equals("compile")) {
                deps.append("            <scope>").append(dep.scope()).append("</scope>\n");
            }
            deps.append("        </dependency>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>%s</version>
                        <relativePath/>
                    </parent>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <properties>
                        <java.version>25</java.version>
                        <maven.compiler.release>25</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                    <dependencies>
                %s        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(SPRING_BOOT_VERSION, groupId, artifactId, version, deps);
    }

    // ---- application.yml ----

    private String buildApplicationYml(Map<String, String> vars, AssetRepository repo,
                                       EffectiveProjectModel epm, Options options) {
        StringBuilder sb = new StringBuilder();
        sb.append("spring.application.name: ").append(vars.get("projectName")).append("\n");
        sb.append("server.port: 8080\n");
        // asset configuration inputs (defaults / provided values / secret & config references)
        List<String> enabled = new ArrayList<>();
        for (ResolvedCapability capability : epm.capabilities()) {
            enabled.add(capability.id());
        }
        for (ResolvedProvider provider : epm.providers()) {
            enabled.add(provider.id());
        }
        for (String assetId : enabled) {
            for (AssetRepository.ConfigSpec config : repo.assetConfiguration(assetId)) {
                String key = config.key();
                if (key.equals("server.port") || key.equals("spring.application.name")) {
                    continue; // base lines already present
                }
                Object provided = options.providedConfig().get(key);
                if (provided != null) {
                    sb.append(key).append(": ").append(provided).append("\n");
                    continue;
                }
                if (config.defaultValue() != null) {
                    sb.append(key).append(": ").append(config.defaultValue()).append("\n");
                    continue;
                }
                if (config.type().equals("secretRef") || config.type().equals("configRef")) {
                    // reference placeholder; never a plaintext value in the contract
                    sb.append(key).append(": ${")
                            .append(key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'))
                            .append("}\n");
                }
                // required non-reference without default and not provided -> already rejected
                // by validateRequiredConfig before any write
            }
        }
        return sb.toString();
    }

    // ---- base templates ----

    private static final String APPLICATION_TEMPLATE = """
            package ${package};

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class ${Name}Application {

                public static void main(String[] args) {
                    SpringApplication.run(${Name}Application.class, args);
                }
            }
            """;

    private static final String API_ERROR_TEST_TEMPLATE = """
            package ${package}.common.error;

            import org.junit.jupiter.api.Test;

            import static org.assertj.core.api.Assertions.assertThat;

            class ApiErrorTest {

                @Test
                void ofBuildsErrorBody() {
                    ApiError error = ApiError.of(400, "VALIDATION_FAILED", "bad request");
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.message()).isEqualTo("bad request");
                    assertThat(error.violations()).isEmpty();
                }
            }
            """;

    private static String camelize(String input) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : input.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
