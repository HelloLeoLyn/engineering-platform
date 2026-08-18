package com.engineeringplatform.console;

import com.engineeringplatform.generator.core.AssetYamlReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V07-WORK-004 — Business Module Builder 2.0 backend targeted tests:
 * ModuleContractValidator rules + Contract V2 round-trip through the store
 * (save → YAML → parse → semantic equality).
 */
class ModuleBuilder2Test {

    private static final Path ROOT = Path.of("/home/administrator/workspace/engineering-platform");

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // ModuleContractValidator
    // ------------------------------------------------------------------

    @Test
    void acceptsValidPurchaseOrderManifest() {
        Map<String, Object> manifest = purchaseOrderManifest();
        assertThat(ModuleContractValidator.validate(manifest)).isEmpty();
    }

    @Test
    void rejectsMissingModuleAndBusinessSections() {
        assertThat(ModuleContractValidator.validate(null)).isNotEmpty();
        assertThat(ModuleContractValidator.validate(Map.of())).isNotEmpty();
        assertThat(ModuleContractValidator.validate(Map.of("module", Map.of("id", "x")))).isNotEmpty();
    }

    @Test
    void rejectsBadModuleId() {
        Map<String, Object> manifest = purchaseOrderManifest();
        ((Map<String, Object>) manifest.get("module")).put("id", "Bad ID!");
        List<Map<String, Object>> errors = ModuleContractValidator.validate(manifest);
        assertThat(errors.stream().map(e -> e.get("category"))).contains("Invalid Module ID");
    }

    @Test
    void rejectsReferenceWithoutTarget() {
        Map<String, Object> manifest = purchaseOrderManifest();
        Map<String, Object> field = field("supplierId");
        field.put("semantic", "reference");
        field.put("reference", Map.of("valueField", "id", "labelField", "name"));
        entityFields(manifest).set(1, field);
        List<Map<String, Object>> errors = ModuleContractValidator.validate(manifest);
        assertThat(errors.stream().map(e -> e.get("message")).anyMatch(m -> m.toString().contains("reference.target"))).isTrue();
    }

    @Test
    void rejectsEnumWithoutValues() {
        Map<String, Object> manifest = purchaseOrderManifest();
        Map<String, Object> field = field("status");
        field.put("semantic", "enum");
        field.put("enum", Map.of("values", List.of()));
        entityFields(manifest).set(2, field);
        List<Map<String, Object>> errors = ModuleContractValidator.validate(manifest);
        assertThat(errors.stream().map(e -> e.get("message")).anyMatch(m -> m.toString().contains("enum.values"))).isTrue();
    }

    @Test
    void rejectsManyToManyAndMissingRelationFields() {
        Map<String, Object> manifest = purchaseOrderManifest();
        List<Map<String, Object>> relations = new ArrayList<>();
        relations.add(relation("r1", "MANY_TO_MANY", "x"));
        // ONE_TO_MANY without mappedBy → invalid
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("name", "r2");
        r2.put("type", "ONE_TO_MANY");
        r2.put("target", "y");
        relations.add(r2);
        ((Map<String, Object>) manifest.get("business")).put("relations", relations);
        List<Map<String, Object>> errors = ModuleContractValidator.validate(manifest);
        assertThat(errors.stream().map(e -> e.get("message"))
                .anyMatch(m -> m.toString().contains("MANY_TO_MANY"))).isTrue();
        assertThat(errors.stream().map(e -> e.get("message"))
                .anyMatch(m -> m.toString().contains("mappedBy"))).isTrue();
    }

    @Test
    void rejectsDuplicateRelationNameAndDuplicateFieldName() {
        Map<String, Object> manifest = purchaseOrderManifest();
        List<Map<String, Object>> relations = new ArrayList<>();
        relations.add(relation("items", "ONE_TO_MANY", "purchase-order-item"));
        relations.add(relation("items", "MANY_TO_ONE", "supplier"));
        ((Map<String, Object>) manifest.get("business")).put("relations", relations);
        // duplicate field name
        entityFields(manifest).add(field("orderNo"));
        List<Map<String, Object>> errors = ModuleContractValidator.validate(manifest);
        assertThat(errors.stream().map(e -> e.get("message"))
                .anyMatch(m -> m.toString().contains("Duplicate relation name"))).isTrue();
        assertThat(errors.stream().map(e -> e.get("message"))
                .anyMatch(m -> m.toString().contains("Duplicate field name"))).isTrue();
    }

    // ------------------------------------------------------------------
    // Contract V2 round-trip through ModuleStore (YAML persistence)
    // ------------------------------------------------------------------

    @Test
    void saveParseRoundTripPreservesSemantics() throws Exception {
        ModuleStore store = new ModuleStore(tmp.resolve("modules"));
        Map<String, Object> manifest = purchaseOrderManifest();
        Map<String, Object> saved = store.save(manifest);
        assertThat(saved.get("status")).isEqualTo("READY");

        Map<String, Object> got = store.get("purchase-order");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) AssetYamlReader.parse(String.valueOf(got.get("yaml")));
        assertThat(parsed.get("schemaVersion")).isEqualTo(1);
        Map<?, ?> module = (Map<?, ?>) parsed.get("module");
        assertThat(module.get("id")).isEqualTo("purchase-order");
        Map<?, ?> biz = (Map<?, ?>) parsed.get("business");
        assertThat(biz.get("table")).isEqualTo("purchase_order");
        List<?> fields = (List<?>) ((Map<?, ?>) biz.get("entity")).get("fields");
        assertThat(fields).hasSize(7);
        // reference semantics survive YAML round-trip
        Map<?, ?> supplier = (Map<?, ?>) fields.get(1);
        assertThat(supplier.get("semantic")).isEqualTo("reference");
        Map<?, ?> ref = (Map<?, ?>) supplier.get("reference");
        assertThat(ref.get("target")).isEqualTo("supplier");
        assertThat(ref.get("searchFields")).isEqualTo(List.of("code", "name"));
        // enum semantics survive (orderDate inserted at index 2 → status at 3)
        Map<?, ?> orderDate = (Map<?, ?>) fields.get(2);
        assertThat(orderDate.get("type")).isEqualTo("date");
        Map<?, ?> status = (Map<?, ?>) fields.get(3);
        assertThat(status.get("semantic")).isEqualTo("enum");
        Map<?, ?> enumObj = (Map<?, ?>) status.get("enum");
        List<?> values = (List<?>) enumObj.get("values");
        assertThat(values).hasSize(3);
        // money precision survives
        Map<?, ?> amount = (Map<?, ?>) fields.get(4);
        assertThat(amount.get("type")).isEqualTo("money");
        assertThat(amount.get("precision")).isEqualTo(14);
        // relations survive
        List<?> relations = (List<?>) biz.get("relations");
        assertThat(relations).hasSize(1);
        Map<?, ?> items = (Map<?, ?>) relations.get(0);
        assertThat(items.get("type")).isEqualTo("ONE_TO_MANY");
        assertThat(items.get("composition")).isEqualTo(true);
        // features / enterprise / frontend survive
        assertThat(biz.get("features")).isEqualTo(List.of("list", "search", "create", "edit", "detail", "disable"));
        Map<?, ?> ent = (Map<?, ?>) biz.get("enterprise");
        assertThat(ent.get("dataScope")).isEqualTo(true);
        Map<?, ?> fe = (Map<?, ?>) biz.get("frontend");
        assertThat(fe.get("route")).isEqualTo("/purchase-orders");
    }

    @Test
    void v06ManifestWithoutRelationsStillRoundTrips() throws Exception {
        ModuleStore store = new ModuleStore(tmp.resolve("modules"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("module", Map.of(
                "id", "customer-lite", "name", "CustomerLite", "version", "1.0.0", "type", "business"));
        manifest.put("compatibility", Map.of("platformVersion", "0.6"));
        manifest.put("business", Map.of(
                "table", "customer_lite",
                "entity", Map.of("name", "CustomerLite", "fields", List.of(field("code"))),
                "features", List.of("list", "search", "create", "edit", "detail", "disable"),
                "enterprise", Map.of("permissions", true, "dataScope", true, "menu", true, "dictionary", true, "operationLog", true),
                "frontend", Map.of("route", "/customer-lite", "label", "CustomerLite")));
        assertThat(ModuleContractValidator.validate(manifest)).isEmpty();
        store.save(manifest);
        Map<String, Object> got = store.get("customer-lite");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) AssetYamlReader.parse(String.valueOf(got.get("yaml")));
        Map<?, ?> biz = (Map<?, ?>) parsed.get("business");
        assertThat(biz.get("relations")).isNull();
        List<?> fields = (List<?>) ((Map<?, ?>) biz.get("entity")).get("fields");
        assertThat(fields).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Generate integration: Builder-saved Contract → existing pipeline
    // (targeted proof only — WORK-002/003 already cover full E2E)
    // ------------------------------------------------------------------

    @Test
    void builderContractReachesExistingGenerator() throws Exception {
        GenerationService svc = new GenerationService(ROOT, tmp.resolve("gen-data"));
        ModuleStore store = new ModuleStore(tmp.resolve("modules"));
        // Builder 2.0 saves the module manifest (same store the Console uses)
        store.save(purchaseOrderManifest());

        // Build a full project contract referencing the builder module + its
        // detail module; capability list mirrors the Console's handleModuleGenerate.
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schemaVersion", 1);
        contract.put("project", Map.of(
                "id", "po-builder-proof",
                "name", "PO Builder Proof",
                "version", "1.0.0",
                "basePackage", "com.acme.core",
                "groupId", "com.acme",
                "artifactId", "po-builder-proof"));
        contract.put("platform", Map.of("id", "engineering-platform"));
        contract.put("application", Map.of("profile", "enterprise"));
        contract.put("stack", Map.of("profile", "enterprise-java25"));
        contract.put("frontends", List.of(Map.of("id", "admin", "template", "enterprise-admin")));
        contract.put("modules", List.of("purchase-order", "purchase-order-item"));
        contract.put("capabilities", List.of(
                Map.of("id", "web"), Map.of("id", "validation"), Map.of("id", "exception-handling"),
                Map.of("id", "platform-core"), Map.of("id", "authentication"), Map.of("id", "rbac"),
                Map.of("id", "organization"), Map.of("id", "data-permission"), Map.of("id", "menu"),
                Map.of("id", "dictionary"), Map.of("id", "operation-log"),
                Map.of("id", "frontend-shell"), Map.of("id", "frontend-auth"),
                Map.of("id", "frontend-permission"), Map.of("id", "frontend-enterprise-management"),
                Map.of("id", "runtime-recipe")));
        contract.put("quality", Map.of("minimum", "Q2"));

        // extra manifests: the builder module itself + v07 fixtures it depends on
        List<Map<String, Object>> extra = new ArrayList<>();
        extra.add((Map<String, Object>) AssetYamlReader.parse(
                String.valueOf(store.get("purchase-order").get("yaml"))));
        for (String id : List.of("purchase-order-item", "supplier", "product")) {
            Path f = ROOT.resolve("tests/fixtures/v07-reference/generic/modules").resolve(id + ".yaml");
            extra.add((Map<String, Object>) AssetYamlReader.parse(Files.readString(f)));
        }

        Path out = tmp.resolve("out");
        Map<String, Object> result = svc.generateWithModules(contract, extra, out);
        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderService.java")))
                .as("master backend generated").isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/purchaseorderitem/PurchaseOrderItemService.java")))
                .as("detail backend generated").isTrue();
        assertThat(Files.exists(out.resolve("frontend/src/views/purchaseorder/PurchaseOrderEditView.vue")))
                .as("master edit view generated").isTrue();
        assertThat(Files.exists(out.resolve("project.yaml")))
                .as("contract artifact written").isTrue();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> purchaseOrderManifest() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("orderNo"));
        Map<String, Object> supplier = field("supplierId");
        supplier.put("required", true);
        supplier.put("semantic", "reference");
        supplier.put("reference", Map.of(
                "target", "supplier", "valueField", "id", "labelField", "name",
                "searchFields", List.of("code", "name")));
        fields.add(supplier);
        Map<String, Object> orderDate = field("orderDate");
        orderDate.put("type", "date");
        orderDate.put("required", true);
        fields.add(orderDate);
        Map<String, Object> status = field("status");
        status.put("semantic", "enum");
        status.put("enum", Map.of("values", List.of(
                Map.of("value", "DRAFT", "label", "Draft"),
                Map.of("value", "CONFIRMED", "label", "Confirmed"),
                Map.of("value", "CLOSED", "label", "Closed"))));
        fields.add(status);
        Map<String, Object> amount = field("totalAmount");
        amount.put("type", "money");
        amount.put("precision", 14);
        amount.put("scale", 2);
        fields.add(amount);
        fields.add(field("departmentId"));
        fields.add(field("remark"));

        Map<String, Object> business = new LinkedHashMap<>();
        business.put("table", "purchase_order");
        business.put("entity", Map.of("name", "PurchaseOrder", "fields", fields));
        business.put("relations", List.of(relation("items", "ONE_TO_MANY", "purchase-order-item")));
        business.put("features", List.of("list", "search", "create", "edit", "detail", "disable"));
        business.put("enterprise", Map.of(
                "permissions", true, "dataScope", true, "menu", true, "dictionary", false, "operationLog", true));
        business.put("frontend", Map.of("route", "/purchase-orders", "label", "Purchase Orders"));

        Map<String, Object> manifest = new LinkedHashMap<>();
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("id", "purchase-order");
        module.put("name", "PurchaseOrder");
        module.put("version", "1.0.0");
        module.put("type", "business");
        module.put("description", "V07-WORK-004 builder proof");
        manifest.put("schemaVersion", 1);
        manifest.put("module", module);
        manifest.put("compatibility", Map.of("platformVersion", "0.6"));
        manifest.put("business", business);
        return manifest;
    }

    private static Map<String, Object> field(String name) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("type", "string");
        return f;
    }

    private static Map<String, Object> relation(String name, String type, String target) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("type", type);
        r.put("target", target);
        if ("ONE_TO_MANY".equals(type)) {
            r.put("mappedBy", "purchaseOrderId");
            r.put("composition", true);
        } else {
            r.put("localField", "supplierId");
            r.put("targetField", "id");
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entityFields(Map<String, Object> manifest) {
        Map<String, Object> biz = (Map<String, Object>) manifest.get("business");
        Map<String, Object> entity = (Map<String, Object>) biz.get("entity");
        return (List<Map<String, Object>>) entity.get("fields");
    }
}
