package com.engineeringplatform.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V06-WORK-004 — Console backend targeted tests.
 * Covers catalog reading, contract validation categories, project store,
 * and ONE real Product + Supplier generation through the existing pipeline.
 */
class ConsoleBackendTest {

    private static final Path ROOT = Path.of("/home/administrator/workspace/engineering-platform");

    @TempDir
    Path tmp;

    private GenerationService service(Path dataDir) {
        return new GenerationService(ROOT, dataDir);
    }

    // ---- catalog ----

    @Test
    void catalogExposesCertifiedProfilesStacksTemplates() throws Exception {
        GenerationService svc = service(tmp.resolve("d1"));
        List<Map<String, Object>> profiles = svc.applicationProfiles();
        assertThat(profiles).isNotEmpty();
        Map<String, Object> enterprise = profiles.stream()
                .filter(p -> "enterprise".equals(p.get("id"))).findFirst().orElseThrow();
        assertThat(enterprise.get("status")).isEqualTo("certified");
        assertThat(profiles.stream().map(p -> p.get("id")))
                .contains("corporate-portal", "ecommerce", "custom");

        List<Map<String, Object>> stacks = svc.stackProfiles();
        assertThat(stacks.stream().filter(s -> "enterprise-java25".equals(s.get("id"))).findFirst())
                .isPresent();
        assertThat(stacks.get(0).get("details")).isNotNull();

        List<Map<String, Object>> templates = svc.frontendTemplates();
        assertThat(templates.stream().filter(t -> "enterprise-admin".equals(t.get("id"))).findFirst())
                .isPresent();
        assertThat(templates.stream().map(t -> t.get("id")))
                .contains("modern-console", "simple-admin", "corporate-website", "commerce-storefront", "custom");
    }

    @Test
    void moduleCatalogComesFromRegistryNotHardcoded() throws Exception {
        GenerationService svc = service(tmp.resolve("d2"));
        List<Map<String, Object>> modules = svc.moduleCatalog();
        assertThat(modules).isNotEmpty();
        assertThat(modules.stream().map(m -> m.get("id"))).contains("supplier");
        assertThat(modules.stream().map(m -> m.get("id"))).contains("product-reference");
        for (Map<String, Object> m : modules) {
            assertThat(m).containsKey("id").containsKey("description").containsKey("kind");
        }
    }

    // ---- validation categories ----

    @Test
    void validationRejectsUnsupportedProfile() {
        Map<String, Object> contract = baseContract();
        contract.put("application", Map.of("profile", "corporate-portal"));
        var errors = ConsoleContractValidator.validate(contract);
        assertThat(errors.stream().map(e -> e.get("category")))
                .contains("Unsupported Application Profile");
    }

    @Test
    void validationRejectsUnknownModule() {
        Map<String, Object> contract = baseContract();
        contract.put("modules", List.of("not-a-module"));
        var errors = ConsoleContractValidator.validate(contract);
        assertThat(errors.stream().map(e -> e.get("category"))).contains("Unknown Module");
    }

    @Test
    void validationRejectsUnsupportedStackAndTemplate() {
        Map<String, Object> contract = baseContract();
        contract.put("stack", Map.of("profile", "unknown-stack"));
        contract.put("frontends", List.of(Map.of("id", "admin", "template", "commerce-storefront")));
        var errors = ConsoleContractValidator.validate(contract);
        assertThat(errors.stream().map(e -> e.get("category")))
                .contains("Unsupported Stack Profile", "Unsupported Frontend Template");
    }

    @Test
    void validationAcceptsCertifiedEnterpriseContract() {
        assertThat(ConsoleContractValidator.validate(baseContract())).isEmpty();
    }

    // ---- project store ----

    @Test
    void projectStorePersistsAcrossInstances() throws Exception {
        Path dataFile = tmp.resolve("projects.json");
        ProjectStore s1 = new ProjectStore(dataFile);
        s1.add(Map.of("name", "demo", "profile", "enterprise", "modules", List.of("supplier")));
        ProjectStore s2 = new ProjectStore(dataFile);
        assertThat(s2.list()).hasSize(1);
        assertThat(s2.list().get(0).get("name")).isEqualTo("demo");
    }

    // ---- real generation (Product + Supplier) ----

    @Test
    void generateRealProjectWithProductAndSupplier() throws Exception {
        GenerationService svc = service(tmp.resolve("gen-data"));
        Path out = tmp.resolve("out");
        Map<String, Object> contract = baseContract();
        Map<String, Object> result = svc.generate(contract, out);

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.get("generatedFiles")).isInstanceOf(Number.class);
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/supplier/SupplierService.java")))
                .as("supplier backend generated").isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/product/ProductService.java")))
                .as("product backend generated").isTrue();
        assertThat(Files.exists(out.resolve("frontend/src/views/supplier/SupplierListView.vue")))
                .as("supplier frontend generated").isTrue();
        assertThat(Files.exists(out.resolve("project.yaml")))
                .as("contract artifact written").isTrue();
    }

    @Test
    void generateRejectsUnsupportedProfileBeforePipeline() throws Exception {
        GenerationService svc = service(tmp.resolve("gen-data2"));
        Map<String, Object> contract = baseContract();
        contract.put("application", Map.of("profile", "ecommerce"));
        Map<String, Object> result = svc.generate(contract, tmp.resolve("out2"));
        assertThat(result.get("status")).isEqualTo("FAILED");
        assertThat(result.get("errors")).isNotNull();
    }

    // ---- contract preview (YAML) ----

    @Test
    void yamlPreviewRendersProjectContract() {
        String yaml = YamlDumper.dump(baseContract());
        assertThat(yaml).contains("schemaVersion: 1")
                .contains("profile: enterprise")
                .contains("template: enterprise-admin")
                .contains("- supplier");
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseContract() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schemaVersion", 1);
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", "console-demo");
        project.put("name", "Console Demo");
        project.put("version", "1.0.0");
        project.put("basePackage", "com.acme.core");
        project.put("groupId", "com.acme");
        project.put("artifactId", "console-demo");
        contract.put("project", project);
        contract.put("platform", Map.of("id", "engineering-platform"));
        contract.put("application", Map.of("profile", "enterprise"));
        contract.put("stack", Map.of("profile", "enterprise-java25"));
        contract.put("frontends", List.of(Map.of("id", "admin", "template", "enterprise-admin")));
        contract.put("modules", List.of("supplier"));
        contract.put("capabilities", List.of(
                Map.of("id", "web"), Map.of("id", "validation"), Map.of("id", "exception-handling"),
                Map.of("id", "platform-core"), Map.of("id", "authentication"), Map.of("id", "rbac"),
                Map.of("id", "organization"), Map.of("id", "data-permission"), Map.of("id", "menu"),
                Map.of("id", "dictionary"), Map.of("id", "operation-log"),
                Map.of("id", "product-reference"), Map.of("id", "frontend-product-reference"),
                Map.of("id", "frontend-shell"), Map.of("id", "frontend-auth"),
                Map.of("id", "frontend-permission"), Map.of("id", "frontend-enterprise-management")));
        contract.put("quality", Map.of("minimum", "Q2"));
        return contract;
    }
}
