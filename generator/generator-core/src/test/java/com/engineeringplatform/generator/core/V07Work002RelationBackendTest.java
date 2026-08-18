package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V07-WORK-002 — Relationship-aware Generic Backend Generation (A-O acceptance).
 *
 * Proves the Generic Backend Generator understands relations & master/detail:
 *   A. V200__relations.sql: FK constraint + index for reference fields and
 *      MANY_TO_ONE / ONE_TO_MANY relations; columns reused, not duplicated
 *   B. money -> DECIMAL(precision, scale); enum -> VARCHAR + values
 *   C. master DTOs carry items[] (CreateRequest/UpdateRequest/Response)
 *   D. reference target-exists validation rendered into Service (via Port)
 *   E. master create renders @Transactional parent+children insert
 *   F. detail returns parent + items (child findByParent)
 *   G. update reconciliation rendered (existing+present->update, missing->delete, new->insert)
 *   H. composition child Port gains findByParent + deleteById
 *   I. ONE_TO_ONE ownership FK + reference validation (targeted)
 *   J. genericity: a second relation domain (warehouse -> warehouse-bin)
 *   K. generated backend compiles + master/detail HTTP E2E passes
 *   L. V0.6 no-relation modules unchanged (no V200, no items)
 */
class V07Work002RelationBackendTest {

    private static final ManifestValidationPort ALWAYS_VALID = new ManifestValidationPort() {
        @Override public boolean isValid(String manifestType, Map<String, Object> manifest) { return true; }
        @Override public List<String> validationErrors(String manifestType, Map<String, Object> manifest) { return List.of(); }
    };

    @TempDir
    Path tempDir;

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6; i++) {
            if (Files.exists(p.resolve("capabilities")) && Files.exists(p.resolve("platform.yaml"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                throw new IllegalStateException("cannot locate repository root");
            }
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> realPlatform() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(repoRoot().resolve("platform.yaml"), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path p) throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    private static Map<String, Map<String, Object>> readModuleManifests(Path modulesDir) throws Exception {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (modulesDir == null || !Files.isDirectory(modulesDir)) {
            return result;
        }
        try (var stream = Files.list(modulesDir)) {
            for (Path f : stream.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> manifest = (Map<String, Object>) AssetYamlReader.parse(
                        Files.readString(f, StandardCharsets.UTF_8));
                Map<String, Object> module = asMap(manifest.get("module"));
                String id = String.valueOf(module.get("id"));
                result.put(id, manifest);
            }
        }
        return result;
    }

    private static ResolutionResult resolve(Map<String, Object> project,
                                            Map<String, Map<String, Object>> moduleManifests,
                                            Set<String> extraModules) throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<String> explicitCapabilities = ReferenceResolver.extractIds(project.get("capabilities"));
        AssetContext assetContext = AssetResolution.resolve(repo, explicitCapabilities, realPlatform());
        Map<String, Map<String, Object>> providerManifests = repo.toProviderManifests();
        Set<String> known = new java.util.LinkedHashSet<>(Set.of("sample-customer", "supplier", "customer-lite",
                "warehouse-lite", "product", "purchase-order", "purchase-order-item"));
        known.addAll(extraModules);
        Map<String, Set<String>> registry = new LinkedHashMap<>();
        registry.put("modules", known);
        registry.put("capabilities", Set.copyOf(repo.capabilities().keySet()));
        registry.put("providers", Set.copyOf(repo.providers().keySet()));
        ResolverInput input = new ResolverInput(realPlatform(), project,
                moduleManifests == null ? Map.of() : moduleManifests, providerManifests, registry);
        ResolutionResult result = new CompleteResolver(new ManifestRuntimeValidator(), assetContext).resolve(input);
        if (result.status() == ResolutionResult.Status.FAILED) {
            System.out.println("DIAG errors: " + result.errors());
        }
        return result;
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.acme.core", "v07-rel-demo", "com.acme", "v07-rel-demo", "1.0.0", Map.of());
    }

    private static AssetProjectGenerator.GenerationResult generate(EffectiveProjectModel epm, Path out)
            throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Files.createDirectories(out);
        return new AssetProjectGenerator().generate(epm, repo, options(), out);
    }

    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static EffectiveProjectModel v07Epm() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v07-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v07-reference/generic/modules"));
        ResolutionResult result = resolve(project, manifests, Set.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        return result.effectiveProject();
    }

    private static String read(Path out, String rel) throws Exception {
        Path f = out.resolve(rel);
        assertThat(Files.exists(f)).as(rel).isTrue();
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    // ---- A: relations migration (FK + index, columns reused) ----

    @Test
    void relationsMigrationHasFksAndIndexes() throws Exception {
        Path out = tempDir.resolve("gen-a");
        generate(v07Epm(), out);

        String v200 = read(out, "src/main/resources/db/migration/V200__relations.sql");
        // PurchaseOrder.supplierId -> supplier.id (reference field)
        assertThat(v200).contains("fk_purchase_order_supplier_id FOREIGN KEY (supplier_id) REFERENCES supplier (id)");
        // PurchaseOrderItem.purchaseOrderId -> purchase_order.id (MANY_TO_ONE)
        assertThat(v200).contains("fk_purchase_order_item_purchase_order_id FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id)");
        // PurchaseOrderItem.productId -> product.id (reference field)
        assertThat(v200).contains("fk_purchase_order_item_product_id FOREIGN KEY (product_id) REFERENCES product_catalog (id)");
        // index on FK columns (H2 needs explicit index for child lookups)
        assertThat(v200).contains("CREATE INDEX idx_purchase_order_item_purchase_order_id");
        assertThat(v200).contains("CREATE INDEX idx_purchase_order_supplier_id");
        // baselines do NOT duplicate columns: supplierId declared once in purchase_order baseline
        String baseline = read(out, "src/main/resources/db/migration/V101__purchase_order.sql");
        assertThat(baseline).contains("supplier_id");
        assertThat(v200).doesNotContain("CREATE TABLE");
    }

    @Test
    void relationsMigrationAfterAllBaselines() throws Exception {
        Path out = tempDir.resolve("gen-a2");
        generate(v07Epm(), out);
        // sorted module ids: product(V100) purchase-order(V101) purchase-order-item(V102) supplier(V103)
        for (String f : List.of("V100__product_catalog.sql", "V101__purchase_order.sql",
                "V102__purchase_order_item.sql", "V103__supplier.sql", "V200__relations.sql")) {
            assertThat(Files.exists(out.resolve("src/main/resources/db/migration/" + f))).as(f).isTrue();
        }
        String v200 = read(out, "src/main/resources/db/migration/V200__relations.sql");
        assertThat(v200.indexOf("ALTER TABLE")).isGreaterThan(0); // runs after all baselines
    }

    // ---- B: money / enum column semantics ----

    @Test
    void moneyAndEnumColumns() throws Exception {
        Path out = tempDir.resolve("gen-b");
        generate(v07Epm(), out);

        String poBaseline = read(out, "src/main/resources/db/migration/V101__purchase_order.sql");
        assertThat(poBaseline).contains("total_amount DECIMAL(14, 2)"); // money precision/scale from Contract
        assertThat(poBaseline).contains("status VARCHAR(255)");         // enum persists as String
        String itemBaseline = read(out, "src/main/resources/db/migration/V102__purchase_order_item.sql");
        assertThat(itemBaseline).contains("unit_price DECIMAL(12, 2)");
        assertThat(itemBaseline).contains("amount DECIMAL(14, 2)");

        // entity: money -> BigDecimal
        String entity = read(out, "src/main/java/com/acme/core/domain/entity/PurchaseOrder.java");
        assertThat(entity).contains("private BigDecimal totalAmount;");
        // response: money -> BigDecimal (record component)
        String response = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderResponse.java");
        assertThat(response).contains("BigDecimal totalAmount,");
    }

    // ---- C: master DTO items[] ----

    @Test
    void masterDtosCarryItems() throws Exception {
        Path out = tempDir.resolve("gen-c");
        generate(v07Epm(), out);

        String create = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderCreateRequest.java");
        assertThat(create).contains("List<PurchaseOrderItemItemInput> items");
        String update = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderUpdateRequest.java");
        assertThat(update).contains("List<PurchaseOrderItemItemInput> items");
        String response = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderResponse.java");
        assertThat(response).contains("List<PurchaseOrderItemResponse> items");

        // item input: no parent FK (owned by master Service)
        String itemInput = read(out, "src/main/java/com/acme/core/application/purchaseorderitem/PurchaseOrderItemItemInput.java");
        assertThat(itemInput).contains("Long id");
        assertThat(itemInput).contains("Long productId");
        assertThat(itemInput).doesNotContain("purchaseOrderId");
    }

    // ---- D: reference target-exists validation in Service ----

    @Test
    void referenceValidationRendered() throws Exception {
        Path out = tempDir.resolve("gen-d");
        generate(v07Epm(), out);

        String service = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderService.java");
        // supplier reference validated through SupplierPort
        assertThat(service).contains("supplierPort.findById(request.supplierId())");
        assertThat(service).contains("PURCHASE_ORDER_SUPPLIER_ID_REFERENCE_NOT_FOUND");
        // master Service injects SupplierPort + child Port + child reference target (ProductPort)
        assertThat(service).contains("private final SupplierPort supplierPort;");
        assertThat(service).contains("private final PurchaseOrderItemPort purchaseOrderItemPort;");
        // child reference validation inside items loop
        assertThat(service).contains("productPort.findById(item.productId())");
    }

    // ---- E: master create transaction ----

    @Test
    void masterCreateTransactional() throws Exception {
        Path out = tempDir.resolve("gen-e");
        generate(v07Epm(), out);

        String service = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderService.java");
        assertThat(service).contains("@Transactional");
        assertThat(service).contains("public PurchaseOrderResponse create(");
        // insert parent then children; child FK set from parent id
        assertThat(service).contains("purchaseOrderPort.insert(purchaseOrder)");
        assertThat(service).contains("child.setPurchaseOrderId(purchaseOrder.getId())");
        assertThat(service).contains("purchaseOrderItemPort.insert(child)");
    }

    // ---- F: detail returns parent + items ----

    @Test
    void detailReturnsItems() throws Exception {
        Path out = tempDir.resolve("gen-f");
        generate(v07Epm(), out);

        String service = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderService.java");
        assertThat(service).contains("toDetailResponse(purchaseOrder, context)");
        assertThat(service).contains("purchaseOrderItemPort.findByPurchaseOrderId(e.getId())");
        assertThat(service).contains("PurchaseOrderItemResponse.of(c.getId()");
    }

    // ---- G: update reconciliation ----

    @Test
    void updateReconciliationRendered() throws Exception {
        Path out = tempDir.resolve("gen-g");
        generate(v07Epm(), out);

        String service = read(out, "src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderService.java");
        assertThat(service).contains("reconcileItems(purchaseOrder, request, context)");
        assertThat(service).contains("PURCHASE_ORDER_ITEM_ID_DUPLICATE");   // duplicate child id rejected
        assertThat(service).contains("PURCHASE_ORDER_ITEM_NOT_IN_PARENT");  // child of another parent rejected
        assertThat(service).contains("existingIds.contains(item.id())");
        assertThat(service).contains("purchaseOrderItemPort.deleteById(c.getId())"); // missing -> delete (composition only)
        // never DELETE ALL -> INSERT ALL: reconciliation is diff-based
        assertThat(service).contains("existing + present -> update; new -> insert; existing + missing -> delete");
    }

    // ---- H: composition child Port gains findByParent + deleteById ----

    @Test
    void childPortHasFindByParentAndDelete() throws Exception {
        Path out = tempDir.resolve("gen-h");
        generate(v07Epm(), out);

        String port = read(out, "src/main/java/com/acme/core/application/purchaseorderitem/PurchaseOrderItemPort.java");
        assertThat(port).contains("List<PurchaseOrderItem> findByPurchaseOrderId(Long purchaseOrderId)");
        assertThat(port).contains("void deleteById(Long id)");

        String repo = read(out, "src/main/java/com/acme/core/infrastructure/persistence/MybatisPurchaseOrderItemRepository.java");
        assertThat(repo).contains("findByPurchaseOrderId(Long purchaseOrderId)");
        assertThat(repo).contains("wrapper.eq(\"purchase_order_id\", purchaseOrderId)");
        assertThat(repo).contains("purchaseOrderItemMapper.deleteById(id)");
    }

    // ---- I: ONE_TO_ONE targeted ----

    @Test
    void oneToOneOwnershipFkAndValidation() throws Exception {
        // warehouse(owner) ONE_TO_ONE -> warehouse-bin? simpler: profile ONE_TO_ONE -> customer
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "o2o-demo", "name", "O2O Demo", "version", "1.0.0",
                        "basePackage", "com.acme.core"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "modules", List.of("customer-lite", "customer-profile"));
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
        manifests.putAll(readModuleManifests(repoRoot().resolve("tests/fixtures/v06-reference/generic/modules")));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("schemaVersion", 1);
        profile.put("module", Map.of("id", "customer-profile", "name", "CustomerProfile",
                "version", "1.0.0", "type", "business"));
        // platform.yaml version is 0.1.0; declare a matching compatibility so the
        // CompatibilityValidator accepts the fixture (YAML fixtures bypass this
        // via Double-typed platformVersion, Java Maps use String and are checked)
        profile.put("compatibility", Map.of("platformVersion", "0.1.x"));
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("table", "customer_profile");
        business.put("entity", Map.of("name", "CustomerProfile", "fields", List.of(
                Map.of("name", "customerId", "type", "integer", "required", true,
                        "semantic", "reference",
                        "reference", Map.of("target", "customer-lite", "targetField", "id")),
                Map.of("name", "note", "type", "string", "length", 200))));
        business.put("relations", List.of(Map.of(
                "name", "customer", "type", "ONE_TO_ONE", "target", "customer-lite",
                "localField", "customerId", "targetField", "id", "required", true)));
        business.put("features", List.of("list", "detail", "create", "edit", "detail"));
        business.put("enterprise", Map.of());
        profile.put("business", business);
        manifests.put("customer-profile", profile);

        EffectiveProjectModel epm = resolve(project, manifests, Set.of("customer-profile")).effectiveProject();
        Path out = tempDir.resolve("gen-i");
        generate(epm, out);

        // ownership FK in relations migration
        String v200 = read(out, "src/main/resources/db/migration/V200__relations.sql");
        assertThat(v200).contains("fk_customer_profile_customer_id FOREIGN KEY (customer_id) REFERENCES customer_lite (id)");
        // reference validation on write (owner -> target via Port)
        String service = read(out, "src/main/java/com/acme/core/application/customerprofile/CustomerProfileService.java");
        assertThat(service).contains("customerLitePort.findById(request.customerId())");
        assertThat(service).contains("CUSTOMER_PROFILE_CUSTOMER_ID_REFERENCE_NOT_FOUND");
    }

    // ---- J: genericity — second relation domain (warehouse -> warehouse-bin) ----

    @Test
    void secondRelationDomainGenerates() throws Exception {
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "wh-demo", "name", "Warehouse Demo", "version", "1.0.0",
                        "basePackage", "com.acme.core"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "modules", List.of("warehouse", "warehouse-bin"));
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
        manifests.put("warehouse", warehouseManifest());
        manifests.put("warehouse-bin", warehouseBinManifest());

        EffectiveProjectModel epm = resolve(project, manifests, Set.of("warehouse", "warehouse-bin")).effectiveProject();
        Path out = tempDir.resolve("gen-j");
        generate(epm, out);

        // FK + index in V200
        String v200 = read(out, "src/main/resources/db/migration/V200__relations.sql");
        assertThat(v200).contains("fk_warehouse_bin_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)");
        // master DTO items[]
        String create = read(out, "src/main/java/com/acme/core/application/warehouse/WarehouseCreateRequest.java");
        assertThat(create).contains("List<WarehouseBinItemInput> items");
        // child Port findByParent
        String port = read(out, "src/main/java/com/acme/core/application/warehousebin/WarehouseBinPort.java");
        assertThat(port).contains("List<WarehouseBin> findByWarehouseId(Long warehouseId)");
        assertThat(port).contains("void deleteById(Long id)");
        // master Service transactional + reconciliation
        String service = read(out, "src/main/java/com/acme/core/application/warehouse/WarehouseService.java");
        assertThat(service).contains("@Transactional");
        assertThat(service).contains("reconcileItems");
    }

    // ---- K: generated backend compiles + master/detail HTTP E2E ----

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void generatedBackendCompilesAndHttpE2ePasses() throws Exception {
        Path out = tempDir.resolve("gen-k");
        AssetProjectGenerator.GenerationResult generated = generate(v07Epm(), out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        // generated master/detail HTTP E2E test exists
        String e2e = read(out, "src/test/java/com/acme/core/PurchaseOrderMasterDetailHttpE2ETest.java");
        assertThat(e2e).contains("createWithItemsThenDetail");
        assertThat(e2e).contains("updateReconcilesItems");
        assertThat(e2e).contains("duplicateChildIdRejected");
        assertThat(e2e).contains("childOfAnotherParentRejected");

        // compile + run the targeted generated tests (backend only)
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "test",
                "-Dtest=PurchaseOrderMasterDetailHttpE2ETest,PurchaseOrderModelUnitTest,PurchaseOrderItemModelUnitTest",
                "-Dsurefire.failIfNoSpecifiedTests=false"));
        if (Files.exists(settings)) {
            cmd.add(2, "-s");
            cmd.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(900, TimeUnit.SECONDS);
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue())
                .as("generated backend master/detail tests must pass:\n%s",
                        output.substring(Math.max(0, output.length() - 4000)))
                .isEqualTo(0);
        for (String suite : List.of("com.acme.core.PurchaseOrderMasterDetailHttpE2ETest")) {
            Path report = out.resolve("target/surefire-reports/" + suite + ".txt");
            assertThat(Files.exists(report)).as(suite + " report exists").isTrue();
            String text = Files.readString(report, StandardCharsets.UTF_8);
            assertThat(text).as(suite + " ran").contains("Tests run:");
            assertThat(text).as(suite + " no failures").contains("Failures: 0");
        }
    }

    // ---- L: V0.6 no-relation modules unchanged ----

    @Test
    void v06ModulesUnchanged() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests, Set.of()).effectiveProject();
        Path out = tempDir.resolve("gen-l");
        generate(epm, out);

        // no relations migration (no FK anywhere)
        assertThat(Files.exists(out.resolve("src/main/resources/db/migration/V200__relations.sql"))).isFalse();
        // no items[] anywhere
        String create = read(out, "src/main/java/com/acme/core/application/customerlite/CustomerLiteCreateRequest.java");
        assertThat(create).doesNotContain("items");
        String service = read(out, "src/main/java/com/acme/core/application/customerlite/CustomerLiteService.java");
        assertThat(service).doesNotContain("@Transactional").doesNotContain("reconcileItems");
        String port = read(out, "src/main/java/com/acme/core/application/customerlite/CustomerLitePort.java");
        assertThat(port).doesNotContain("deleteById");
    }

    // ---- helpers ----

    private static Map<String, Object> warehouseManifest() {
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("table", "warehouse");
        business.put("entity", Map.of("name", "Warehouse", "fields", List.of(
                Map.of("name", "code", "type", "string", "required", true, "unique", true, "length", 50),
                Map.of("name", "name", "type", "string", "required", true, "length", 100))));
        business.put("relations", List.of(Map.of(
                "name", "bins", "type", "ONE_TO_MANY", "target", "warehouse-bin",
                "mappedBy", "warehouseId", "composition", true)));
        business.put("features", List.of("list", "search", "create", "edit", "detail", "disable"));
        business.put("enterprise", Map.of("permissions", true, "dataScope", true, "menu", true,
                "dictionary", false, "operationLog", true));
        business.put("frontend", Map.of("route", "/warehouses", "label", "Warehouses"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("module", Map.of("id", "warehouse", "name", "Warehouse", "version", "1.0.0", "type", "business"));
        manifest.put("compatibility", Map.of("platformVersion", "0.1.x"));
        manifest.put("business", business);
        return manifest;
    }

    private static Map<String, Object> warehouseBinManifest() {
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("table", "warehouse_bin");
        business.put("entity", Map.of("name", "WarehouseBin", "fields", List.of(
                Map.of("name", "warehouseId", "type", "integer", "required", true),
                Map.of("name", "code", "type", "string", "required", true, "length", 50),
                Map.of("name", "capacity", "type", "integer"))));
        business.put("relations", List.of(Map.of(
                "name", "warehouse", "type", "MANY_TO_ONE", "target", "warehouse",
                "localField", "warehouseId", "targetField", "id", "required", true, "composition", true)));
        business.put("features", List.of("list", "detail"));
        business.put("enterprise", Map.of());
        business.put("frontend", Map.of("route", "/warehouse-bins", "label", "Warehouse Bins"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("module", Map.of("id", "warehouse-bin", "name", "WarehouseBin", "version", "1.0.0", "type", "business"));
        manifest.put("compatibility", Map.of("platformVersion", "0.1.x"));
        manifest.put("business", business);
        return manifest;
    }
}
