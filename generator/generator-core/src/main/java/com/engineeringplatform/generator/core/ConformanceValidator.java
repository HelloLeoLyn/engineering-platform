package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ConformanceFinding;
import com.engineeringplatform.generator.contracts.ConformanceResult;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ResolvedProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engineering Conformance Validator V1 (V02-WORK-005).
 *
 * Verifies that a generated project matches Platform / Asset engineering standards.
 * Independent from V0.1 Task-oriented Verification (ADR-001): this is Engineering
 * Conformance Verification — manifest/architecture/dependency/structure/config facts.
 *
 * Input: EffectiveProjectModel + AssetRepository + generated project workspace.
 * Rules are driven by asset conformance metadata where possible (no hardcoded
 * exception-handling/logging/audit/persistence specifics).
 *
 * V1 pom analysis only (no full Maven dependency graph analyzer).
 */
public final class ConformanceValidator {

    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile(
            "<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile(
            "<java.version>([^<]+)</java.version>");
    private static final Pattern PARENT_VERSION_PATTERN = Pattern.compile(
            "<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern FLAT_KEY_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9._-]+):", Pattern.MULTILINE);

    private final AssetRepository repo;

    public ConformanceValidator(AssetRepository repo) {
        this.repo = repo;
    }

    public ConformanceResult validate(EffectiveProjectModel epm, Path projectRoot) throws IOException {
        List<ConformanceFinding> findings = new ArrayList<>();
        String pomText = readOptional(projectRoot.resolve("pom.xml"));
        String appYml = readOptional(projectRoot.resolve("src/main/resources/application.yml"));
        String mybatisYml = readOptional(projectRoot.resolve("src/main/resources/application-mybatis.yaml"));

        // enabled asset ids in stable EPM order
        List<String> enabled = new ArrayList<>();
        epm.capabilities().forEach(c -> enabled.add(c.id()));
        epm.providers().forEach(p -> enabled.add(p.id()));

        technologyRules(epm, pomText, findings);
        structureRules(epm, enabled, projectRoot, findings);
        dependencyRules(enabled, pomText, findings);
        configurationRules(enabled, appYml, mybatisYml, findings);
        providerRules(epm, pomText, findings);
        sourceLeakRules(epm, enabled, projectRoot, findings);
        assetTestReferenceWarnings(enabled, projectRoot, findings);

        String summary = "Conformance checked " + findings.size() + " finding(s) against "
                + enabled.size() + " enabled asset(s) for project "
                + (epm.identity() == null ? "?" : epm.identity().getOrDefault("id", "?"));
        return ConformanceResult.of(findings, summary);
    }

    // ---- A. Technology ----

    private void technologyRules(EffectiveProjectModel epm, String pomText, List<ConformanceFinding> findings) {
        Object javaRaw = epm.technology() == null ? null : epm.technology().get("java");
        if (javaRaw != null && !pomText.isEmpty()) {
            Matcher matcher = JAVA_VERSION_PATTERN.matcher(pomText);
            if (matcher.find() && !String.valueOf(javaRaw).equals(matcher.group(1))) {
                findings.add(ConformanceFinding.error("technology.java-version",
                        "pom java.version '" + matcher.group(1) + "' does not match EPM baseline '"
                                + javaRaw + "'", null, "pom.xml"));
            }
        }
        // springBoot fact source: EPM.technology.springBoot when present, else asset-declared constraint
        Object sbRaw = epm.technology() == null ? null : epm.technology().get("springBoot");
        String springBootConstraint = sbRaw == null ? firstSpringBootConstraint() : String.valueOf(sbRaw);
        if (springBootConstraint != null && !pomText.isEmpty()) {
            Matcher matcher = PARENT_VERSION_PATTERN.matcher(pomText);
            if (matcher.find() && !AssetResolution.matches(springBootConstraint, matcher.group(1))) {
                findings.add(ConformanceFinding.error("technology.spring-boot-version",
                        "pom spring-boot-starter-parent '" + matcher.group(1)
                                + "' does not match declared constraint '" + springBootConstraint + "'",
                        null, "pom.xml"));
            }
        }
    }

    private String firstSpringBootConstraint() {
        for (var asset : repo.capabilities().values()) {
            if (asset.compatibility().springBoot() != null) {
                return asset.compatibility().springBoot();
            }
        }
        return null;
    }

    // ---- B. Structure ----

    private void structureRules(EffectiveProjectModel epm, List<String> enabled, Path projectRoot,
                                List<ConformanceFinding> findings) {
        String[] base = {"pom.xml", "src/main/java", "src/main/resources", "src/test/java"};
        for (String path : base) {
            if (!Files.exists(projectRoot.resolve(path))) {
                findings.add(ConformanceFinding.error("structure.required-file",
                        "required project structure missing: " + path, null, path));
            }
        }
        String basePackage = epm.identity() == null ? null
                : (String) epm.identity().get("basePackage");
        String packagePath = basePackage == null ? null : basePackage.replace('.', '/');
        for (String assetId : enabled) {
            for (String requiredFile : requiredFiles(assetId)) {
                String resolved = packagePath == null ? requiredFile
                        : requiredFile.replace("{package}", packagePath);
                if (!Files.exists(projectRoot.resolve(resolved))) {
                    findings.add(ConformanceFinding.error("asset.required-file",
                            "asset " + assetId + " requires generated file: " + resolved,
                            assetId, resolved));
                }
            }
        }
    }

    // ---- C. Dependency ----

    private void dependencyRules(List<String> enabled, String pomText, List<ConformanceFinding> findings) {
        if (pomText.isEmpty()) {
            return; // structure rule already flags missing pom
        }
        Set<String> present = parsePomDependencies(pomText);
        for (String assetId : enabled) {
            for (String required : requiredDependencies(assetId)) {
                if (!present.contains(required)) {
                    findings.add(ConformanceFinding.error("dependency.required",
                            "asset " + assetId + " requires dependency: " + required,
                            assetId, "pom.xml"));
                }
            }
            for (String forbidden : forbiddenDependencies(assetId)) {
                if (present.contains(forbidden)) {
                    findings.add(ConformanceFinding.error("dependency.forbidden",
                            "asset " + assetId + " forbids dependency: " + forbidden,
                            assetId, "pom.xml"));
                }
            }
        }
    }

    // ---- D. Configuration ----

    private void configurationRules(List<String> enabled, String appYml, String mybatisYml,
                                    List<ConformanceFinding> findings) {
        String combined = appYml + "\n" + mybatisYml;
        Set<String> presentKeys = new LinkedHashSet<>();
        Matcher matcher = FLAT_KEY_PATTERN.matcher(combined);
        while (matcher.find()) {
            presentKeys.add(matcher.group(1));
        }
        for (String assetId : enabled) {
            for (String requiredKey : requiredConfig(assetId)) {
                if (!presentKeys.contains(requiredKey)) {
                    findings.add(ConformanceFinding.error("config.required",
                            "asset " + assetId + " requires configuration key: " + requiredKey,
                            assetId, "src/main/resources/application.yml"));
                }
            }
        }
    }

    // ---- E. Provider compatibility ----

    private void providerRules(EffectiveProjectModel epm, String pomText, List<ConformanceFinding> findings)
            throws IOException {
        Set<String> present = parsePomDependencies(pomText);
        for (ResolvedProvider provider : epm.providers()) {
            List<AssetRepository.MavenDependency> gavs = repo.gavFixtures(provider.id());
            if (gavs.isEmpty()) {
                continue;
            }
            for (AssetRepository.MavenDependency gav : gavs) {
                if (!present.contains(gav.ga())) {
                    findings.add(ConformanceFinding.error("provider.mismatch",
                            "resolved provider " + provider.id() + " dependency missing in pom: " + gav.ga(),
                            provider.id(), "pom.xml"));
                }
            }
        }
    }

    // ---- F. Source-level framework leak guard (V04-WORK-001) ----

    private void sourceLeakRules(EffectiveProjectModel epm, List<String> enabled, Path projectRoot,
                                 List<ConformanceFinding> findings) {
        String basePackage = epm.identity() == null ? null
                : (String) epm.identity().get("basePackage");
        String packagePath = basePackage == null ? null : basePackage.replace('.', '/');
        for (String assetId : enabled) {
            List<String> forbiddenImports = forbiddenImports(assetId);
            if (forbiddenImports.isEmpty()) {
                continue;
            }
            List<String> excludedPaths = forbiddenImportsExcludedPaths(assetId);
            for (String requiredFile : requiredFiles(assetId)) {
                String resolved = packagePath == null ? requiredFile
                        : requiredFile.replace("{package}", packagePath);
                if (isExcluded(resolved, excludedPaths)) {
                    continue; // infrastructure layer: MyBatis/technical types allowed
                }
                Path file = projectRoot.resolve(resolved);
                if (!Files.exists(file)) {
                    continue; // structure rule already flags missing file
                }
                String content = readOptional(file);
                for (String forbidden : forbiddenImports) {
                    if (containsImport(content, forbidden)) {
                        findings.add(ConformanceFinding.error("source.forbidden-import",
                                "asset " + assetId + " forbids framework import: " + forbidden
                                        + " (in " + resolved + ")", assetId, resolved));
                    }
                }
            }
        }
    }

    private static boolean isExcluded(String resolved, List<String> excludedPaths) {
        if (excludedPaths.isEmpty()) {
            return false;
        }
        for (String prefix : excludedPaths) {
            if (resolved.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsImport(String source, String prefix) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import " + prefix + ".")
                    || trimmed.startsWith("import " + prefix + ";")) {
                return true;
            }
        }
        return false;
    }

    // ---- G. Asset test references (warning only) ----

    private void assetTestReferenceWarnings(List<String> enabled, Path projectRoot,
                                            List<ConformanceFinding> findings) {
        for (String assetId : enabled) {
            for (String reference : testReferences(assetId)) {
                if (!Files.exists(projectRoot.resolve(reference))) {
                    findings.add(ConformanceFinding.warning("asset.tests-reference",
                            "asset " + assetId + " test reference not present in project: " + reference,
                            assetId, reference));
                }
            }
        }
    }

    // ---- asset conformance metadata accessors (from raw asset.yaml) ----

    private List<String> requiredFiles(String assetId) {
        return conformanceList(assetId, "requiredFiles");
    }

    private List<String> requiredDependencies(String assetId) {
        return conformanceList(assetId, "requiredDependencies");
    }

    private List<String> forbiddenDependencies(String assetId) {
        return conformanceList(assetId, "forbiddenDependencies");
    }

    private List<String> forbiddenImports(String assetId) {
        return conformanceList(assetId, "forbiddenImports");
    }

    private List<String> forbiddenImportsExcludedPaths(String assetId) {
        return conformanceList(assetId, "forbiddenImportsExcludedPaths");
    }

    private List<String> requiredConfig(String assetId) {
        return conformanceList(assetId, "requiredConfig");
    }

    private List<String> testReferences(String assetId) {
        List<String> refs = new ArrayList<>();
        Map<String, Object> raw = repo.rawAsset(assetId);
        if (raw == null || !(raw.get("tests") instanceof Map<?, ?> tests)) {
            return refs;
        }
        for (String key : new String[]{"files", "fixtures"}) {
            Object list = tests.get(key);
            if (list instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof String s) {
                        refs.add(s);
                    }
                }
            }
        }
        return refs;
    }

    @SuppressWarnings("unchecked")
    private List<String> conformanceList(String assetId, String field) {
        List<String> values = new ArrayList<>();
        Map<String, Object> raw = repo.rawAsset(assetId);
        if (raw == null || !(raw.get("conformance") instanceof Map<?, ?> conformance)) {
            return values;
        }
        Object list = conformance.get(field);
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String s) {
                    values.add(s);
                }
            }
        }
        return values;
    }

    // ---- pom parsing ----

    private static Set<String> parsePomDependencies(String pomText) {
        Set<String> ga = new LinkedHashSet<>();
        Matcher matcher = DEPENDENCY_PATTERN.matcher(pomText);
        while (matcher.find()) {
            ga.add(matcher.group(1) + ":" + matcher.group(2));
        }
        return ga;
    }

    private static String readOptional(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
