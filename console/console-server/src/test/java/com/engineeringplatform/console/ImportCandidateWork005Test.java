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
 * V07-WORK-005 — MySQL / Excel Relation Discovery targeted tests.
 *
 * Covers the Candidate pipeline:
 *   External Metadata → Candidate → Human Confirmation → Contract V2
 *
 *  - candidate model semantics (DETECTED/SUGGESTED/CONFIRMED/IGNORED)
 *  - MySQL metadata discovery (information_schema 5-table read, real FK)
 *  - relation candidates (FK → DETECTED MANY_TO_ONE, reverse → SUGGESTED)
 *  - reference candidates (valueField prefilled, labelField NOT auto-filled)
 *  - heuristic semantic suggestions (SUGGESTED only)
 *  - Excel discovery (explicit input → DETECTED_FROM_EXPLICIT_INPUT,
 *    heuristics → SUGGESTED)
 *  - review resolution (only CONFIRMED enters the contract)
 *  - multi-table mapping (table → module id → entity)
 *
 * MySQL tests connect to the local proof schema (ep_import_proof) when
 * available; they are skipped when no local MySQL is reachable.
 */
class ImportCandidateWork005Test {

    private static final Path ROOT = Path.of("/home/administrator/workspace/engineering-platform");

    // ------------------------------------------------------------------
    // Candidate model semantics
    // ------------------------------------------------------------------

    @Test
    void candidateModelDistinguishesStatusAndType() {
        Map<String, Object> c = ImportCandidateModel.candidate(
                "t1.id.field", ImportCandidateModel.FIELD, ImportCandidateModel.DETECTED,
                ImportCandidateModel.SRC_DATABASE_COLUMN, "t1", "t1", Map.of());
        assertThat(c.get("type")).isEqualTo("FIELD");
        assertThat(c.get("status")).isEqualTo("DETECTED");
        assertThat(c.get("source")).isEqualTo("DATABASE_COLUMN");
        assertThat(ImportCandidateModel.isConfirmed(c)).isFalse();

        Map<String, Object> confirmed = ImportCandidateModel.candidate(
                "t1.id.field", ImportCandidateModel.FIELD, ImportCandidateModel.CONFIRMED,
                ImportCandidateModel.SRC_USER_CONFIRMED, "t1", "t1", Map.of());
        assertThat(ImportCandidateModel.isConfirmed(confirmed)).isTrue();
    }

    @Test
    void onlyConfirmedMayEnterContract() {
        Map<String, Object> draft = excelDraft();
        Map<String, Object> manifest = new ImportCandidateService()
                .resolveToManifest(draft, Map.of(), Map.of());
        // nothing confirmed → no fields in the contract
        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) manifest.get("business");
        List<?> fields = (List<?>) ((Map<?, ?>) biz.get("entity")).get("fields");
        assertThat(fields).isEmpty();
        assertThat(biz.get("relations")).isNull();
    }

    // ------------------------------------------------------------------
    // MySQL discovery — real FK detection
    // ------------------------------------------------------------------

    private static boolean mysqlAvailable() {
        try {
            MySqlImportService.ConnectionInfo info = new MySqlImportService.ConnectionInfo(
                    "127.0.0.1", 3306, "ep_import_proof", "root", "123456");
            return new MySqlImportService().testConnection(info);
        } catch (Exception e) {
            return false;
        }
    }

    private static MySqlImportService.ConnectionInfo proofConn() {
        return new MySqlImportService.ConnectionInfo(
                "127.0.0.1", 3306, "ep_import_proof", "root", "123456");
    }

    @Test
    void mysqlDiscoveryReadsAllFiveInformationSchemaSources() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        MySqlImportService mysql = new MySqlImportService();
        List<MySqlImportService.TableMeta> metas = mysql.discover(proofConn(), List.of("purchase_order"));
        assertThat(metas).hasSize(1);
        MySqlImportService.TableMeta meta = metas.get(0);
        assertThat(meta.table).isEqualTo("purchase_order");
        // columns read
        List<Map<String, Object>> cols = meta.columns;
        assertThat(cols).isNotEmpty();
        Map<String, Object> idCol = cols.stream()
                .filter(c -> "id".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(idCol.get("primaryKey")).isEqualTo(true);
        assertThat(idCol.get("type")).isEqualTo("long");
        Map<String, Object> supplierId = cols.stream()
                .filter(c -> "supplier_id".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(supplierId.get("required")).isEqualTo(true);
        // unique index read
        assertThat(meta.uniqueIndexes).extracting("name").contains("uk_po_order_no");
        // real FK read
        assertThat(meta.foreignKeys).extracting("column").contains("supplier_id");
        Map<String, Object> fk = meta.foreignKeys.stream()
                .filter(f -> "supplier_id".equals(f.get("column"))).findFirst().orElseThrow();
        assertThat(fk.get("referencedTable")).isEqualTo("supplier");
        assertThat(fk.get("referencedColumn")).isEqualTo("id");
        assertThat(fk.get("constraintName")).isEqualTo("fk_po_supplier");
    }

    @Test
    void mysqlFkBecomesDetectedRelationAndReference() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(), List.of("purchase_order", "supplier"), null);
        assertThat(drafts).hasSize(2);
        Map<String, Object> po = drafts.stream().filter(d -> "purchase_order".equals(d.get("table"))).findFirst().orElseThrow();

        // relation candidate MANY_TO_ONE from real FK — DETECTED, not SUGGESTED
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) po.get("candidates");
        Map<String, Object> rel = cands.stream()
                .filter(c -> "RELATION".equals(c.get("type"))
                        && "purchase_order.supplier_id.relation".equals(c.get("id")))
                .findFirst().orElseThrow();
        assertThat(rel.get("status")).isEqualTo("DETECTED");
        assertThat(rel.get("source")).isEqualTo("DATABASE_FK");
        @SuppressWarnings("unchecked")
        Map<String, Object> relPayload = (Map<String, Object>) rel.get("payload");
        assertThat(relPayload.get("type")).isEqualTo("MANY_TO_ONE");
        assertThat(relPayload.get("targetModule")).isEqualTo("supplier");
        // column name → camelCase field name (contract pattern forbids underscores)
        assertThat(relPayload.get("localField")).isEqualTo("supplierId");
        assertThat(relPayload.get("targetField")).isEqualTo("id");

        // reference candidate — valueField prefilled, labelField NOT auto-filled
        Map<String, Object> ref = cands.stream()
                .filter(c -> "REFERENCE".equals(c.get("type"))
                        && "purchase_order.supplier_id.reference".equals(c.get("id")))
                .findFirst().orElseThrow();
        assertThat(ref.get("status")).isEqualTo("DETECTED");
        @SuppressWarnings("unchecked")
        Map<String, Object> refPayload = (Map<String, Object>) ref.get("payload");
        assertThat(refPayload.get("valueField")).isEqualTo("id");
        assertThat(refPayload.get("labelField")).isEqualTo("");
        assertThat(refPayload.get("searchFields")).isEqualTo(List.of());
        assertThat(refPayload.get("targetModule")).isEqualTo("supplier");
    }

    @Test
    void reverseOneToManyIsSuggestedNotConfirmed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        // purchase_order_item.purchase_order_id → purchase_order.id
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(),
                List.of("purchase_order", "purchase_order_item"), null);
        Map<String, Object> po = drafts.stream().filter(d -> "purchase_order".equals(d.get("table"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) po.get("candidates");
        Map<String, Object> rev = cands.stream()
                .filter(c -> "RELATION".equals(c.get("type"))
                        && c.get("id").toString().contains("reverse"))
                .findFirst().orElseThrow();
        assertThat(rev.get("status")).isEqualTo("SUGGESTED"); // NOT auto-confirmed
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) rev.get("payload");
        assertThat(payload.get("type")).isEqualTo("ONE_TO_MANY");
        assertThat(payload.get("targetModule")).isEqualTo("purchase-order-item");
        assertThat(payload.get("mappedBy")).isEqualTo("purchaseOrderId");
        assertThat(payload.get("composition")).isEqualTo(false); // requires human confirmation
    }

    @Test
    void unresolvedTargetWhenTableNotImported() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        // import ONLY purchase_order — supplier not imported
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(), List.of("purchase_order"), null);
        Map<String, Object> po = drafts.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) po.get("candidates");
        Map<String, Object> rel = cands.stream()
                .filter(c -> "purchase_order.supplier_id.relation".equals(c.get("id")))
                .findFirst().orElseThrow();
        assertThat(rel.get("unresolved")).isEqualTo(true);
    }

    @Test
    void heuristicSemanticsAreSuggestedOnly() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(), List.of("purchase_order", "supplier"), null);
        Map<String, Object> po = drafts.stream().filter(d -> "purchase_order".equals(d.get("table"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) po.get("candidates");
        // total_amount DECIMAL(14,2) → possible money
        Map<String, Object> money = cands.stream()
                .filter(c -> "SEMANTIC".equals(c.get("type"))
                        && c.get("id").toString().contains("total_amount") && c.get("id").toString().contains("money"))
                .findFirst().orElseThrow();
        assertThat(money.get("status")).isEqualTo("SUGGESTED");
        // status column → possible enum
        Map<String, Object> en = cands.stream()
                .filter(c -> "SEMANTIC".equals(c.get("type"))
                        && c.get("id").toString().contains("status") && c.get("id").toString().contains("enum"))
                .findFirst().orElseThrow();
        assertThat(en.get("status")).isEqualTo("SUGGESTED");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) en.get("payload");
        assertThat(payload.get("semantic")).isEqualTo("enum");
    }

    @Test
    void tableModuleMappingIsExplicit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        Map<String, Map<String, String>> mapping = Map.of(
                "purchase_order", Map.of("moduleId", "purchase-order", "entity", "PurchaseOrder"),
                "purchase_order_item", Map.of("moduleId", "purchase-order-item", "entity", "PurchaseOrderItem"),
                "supplier", Map.of("moduleId", "supplier", "entity", "Supplier"));
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(),
                List.of("purchase_order", "purchase_order_item", "supplier"), mapping);
        Map<String, Object> po = drafts.stream().filter(d -> "purchase_order".equals(d.get("table"))).findFirst().orElseThrow();
        assertThat(po.get("moduleId")).isEqualTo("purchase-order");
        assertThat(po.get("entity")).isEqualTo("PurchaseOrder");
        // reverse candidate must use the MAPPED child module id, not a string guess
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) po.get("candidates");
        Map<String, Object> rev = cands.stream()
                .filter(c -> "RELATION".equals(c.get("type")) && c.get("id").toString().contains("reverse"))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) rev.get("payload");
        assertThat(payload.get("targetModule")).isEqualTo("purchase-order-item");
    }

    @Test
    void namingHelpersDeriveModuleIdAndEntity() {
        assertThat(ImportCandidateService.tableToModuleId("purchase_order")).isEqualTo("purchase-order");
        assertThat(ImportCandidateService.tableToEntity("purchase_order")).isEqualTo("PurchaseOrder");
        assertThat(ImportCandidateService.tableToEntity("supplier")).isEqualTo("Supplier");
    }

    // ------------------------------------------------------------------
    // Review resolution — CONFIRMED only → Contract V2
    // ------------------------------------------------------------------

    @Test
    void confirmFieldsReferencesAndRelationsProducesContract() {
        Map<String, Object> draft = excelDraft();
        Map<String, String> decisions = Map.of(
                "excel-module.code.field", "accept",
                "excel-module.name.field", "accept",
                "excel-module.warehouse_id.field", "accept",
                "excel-module.warehouse_id.reference", "accept",
                "excel-module.warehouse_id.relation.MANY_TO_ONE", "accept",
                "excel-module.amount.field", "accept",
                "excel-module.amount.semantic.money", "accept");
        Map<String, Object> manifest = new ImportCandidateService()
                .resolveToManifest(draft, decisions, Map.of());
        assertThat(manifest.get("schemaVersion")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) manifest.get("business");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) ((Map<?, ?>) biz.get("entity")).get("fields");
        assertThat(fields).extracting("name")
                .containsExactlyInAnyOrder("code", "name", "warehouseId", "amount");
        // reference config attached to warehouse_id (valueField from candidate)
        Map<String, Object> wh = fields.stream().filter(f -> "warehouseId".equals(f.get("name"))).findFirst().orElseThrow();
        assertThat(wh.get("semantic")).isEqualTo("reference");
        assertThat(((Map<?, ?>) wh.get("reference")).get("target")).isEqualTo("warehouse");
        // money semantic applied
        Map<String, Object> amount = fields.stream().filter(f -> "amount".equals(f.get("name"))).findFirst().orElseThrow();
        assertThat(amount.get("type")).isEqualTo("money");
        // relation serialized
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations = (List<Map<String, Object>>) biz.get("relations");
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).get("type")).isEqualTo("MANY_TO_ONE");
        assertThat(relations.get(0).get("target")).isEqualTo("warehouse");
    }

    @Test
    void compositionRequiresExplicitHumanEdit() {
        Map<String, Object> draft = excelDraft();
        Map<String, String> decisions = Map.of(
                "excel-module.code.field", "accept",
                "excel-module.name.field", "accept",
                "excel-module.warehouse_id.field", "accept",
                "excel-module.warehouse_id.relation.MANY_TO_ONE", "accept");
        // human edits: flip composition to true on the relation
        Map<String, Map<String, Object>> edits = Map.of(
                "excel-module.warehouse_id.relation.MANY_TO_ONE",
                Map.of("name", "warehouse", "type", "MANY_TO_ONE", "targetModule", "warehouse",
                        "localField", "warehouse_id", "targetField", "id", "composition", true));
        Map<String, Object> manifest = new ImportCandidateService()
                .resolveToManifest(draft, decisions, edits);
        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) manifest.get("business");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations = (List<Map<String, Object>>) biz.get("relations");
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).get("composition")).isEqualTo(true);
    }

    @Test
    void ignoredCandidatesDoNotEnterContract() {
        Map<String, Object> draft = excelDraft();
        Map<String, String> decisions = Map.of(
                "excel-module.code.field", "accept",
                "excel-module.name.field", "accept",
                "excel-module.warehouse_id.field", "ignore",
                "excel-module.warehouse_id.reference", "ignore",
                "excel-module.warehouse_id.relation.MANY_TO_ONE", "ignore",
                "excel-module.amount.field", "ignore");
        Map<String, Object> manifest = new ImportCandidateService()
                .resolveToManifest(draft, decisions, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) manifest.get("business");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) ((Map<?, ?>) biz.get("entity")).get("fields");
        assertThat(fields).extracting("name").containsExactly("code", "name");
        assertThat(biz.get("relations")).isNull();
    }

    @Test
    void resolvedManifestPassesContractValidatorAndRoundTrips(@TempDir Path tmp) throws Exception {
        Map<String, Object> draft = excelDraft();
        Map<String, String> decisions = Map.of(
                "excel-module.code.field", "accept",
                "excel-module.name.field", "accept",
                "excel-module.warehouse_id.field", "accept",
                "excel-module.warehouse_id.reference", "accept");
        Map<String, Object> manifest = new ImportCandidateService()
                .resolveToManifest(draft, decisions, Map.of());
        assertThat(ModuleContractValidator.validate(manifest)).isEmpty();

        // YAML round-trip via ModuleStore
        ModuleStore store = new ModuleStore(tmp.resolve("modules"));
        store.save(manifest);
        Map<String, Object> got = store.get("excel-module");
        Map<String, Object> parsed = (Map<String, Object>) AssetYamlReader.parse(String.valueOf(got.get("yaml")));
        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) parsed.get("business");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) ((Map<?, ?>) biz.get("entity")).get("fields");
        assertThat(fields).extracting("name").contains("code", "warehouseId");
    }

    // ------------------------------------------------------------------
    // Excel discovery
    // ------------------------------------------------------------------

    private static Map<String, Object> excelDraft() {
        List<String> headers = List.of("field", "type", "required", "primaryKey", "unique",
                "referenceTarget", "referenceValueField", "referenceLabelField",
                "relationType", "relationTarget", "mappedBy", "composition");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("code", "string", "true", "true", "true", "", "", "", "", "", "", ""));
        rows.add(List.of("name", "string", "true", "", "", "", "", "", "", "", "", ""));
        rows.add(List.of("warehouse_id", "long", "true", "", "",
                "warehouse", "id", "", "MANY_TO_ONE", "warehouse", "", ""));
        rows.add(List.of("amount", "decimal", "", "", "", "", "", "", "", "", "", ""));
        return new ImportCandidateService().discoverExcel(headers, rows, "excel-module", "ExcelModule");
    }

    @Test
    void excelExplicitInputIsDetectedFromExplicitInput() {
        Map<String, Object> draft = excelDraft();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) draft.get("candidates");
        // explicit referenceTarget → DETECTED_FROM_EXPLICIT_INPUT
        Map<String, Object> ref = cands.stream()
                .filter(c -> "REFERENCE".equals(c.get("type")))
                .findFirst().orElseThrow();
        assertThat(ref.get("status")).isEqualTo("DETECTED");
        assertThat(ref.get("source")).isEqualTo("DETECTED_FROM_EXPLICIT_INPUT");
        // explicit relationType/relationTarget → DETECTED_FROM_EXPLICIT_INPUT
        Map<String, Object> rel = cands.stream()
                .filter(c -> "RELATION".equals(c.get("type")))
                .findFirst().orElseThrow();
        assertThat(rel.get("status")).isEqualTo("DETECTED");
        assertThat(rel.get("source")).isEqualTo("DETECTED_FROM_EXPLICIT_INPUT");
    }

    @Test
    void excelHeuristicsAreSuggestedOnly() {
        Map<String, Object> draft = excelDraft();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) draft.get("candidates");
        // amount decimal → possible money (SUGGESTED)
        Map<String, Object> money = cands.stream()
                .filter(c -> "SEMANTIC".equals(c.get("type"))
                        && c.get("id").toString().contains("amount") && c.get("id").toString().contains("money"))
                .findFirst().orElseThrow();
        assertThat(money.get("status")).isEqualTo("SUGGESTED");
        // warehouse_id (no reference columns) → possible reference is NOT added
        // because explicit referenceTarget exists; a bare *_id case is covered
        // by the MySQL tests. Assert heuristics never auto-confirm:
        for (Map<String, Object> c : cands) {
            if ("SEMANTIC".equals(c.get("type"))) {
                assertThat(c.get("status")).isEqualTo("SUGGESTED");
            }
        }
    }

    @Test
    void excelBareIdColumnGetsPossibleReferenceSuggestion() {
        List<String> headers = List.of("field", "type");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("supplier_id", "long"));
        rows.add(List.of("name", "string"));
        Map<String, Object> draft = new ImportCandidateService()
                .discoverExcel(headers, rows, "supplier-excel", "SupplierExcel");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) draft.get("candidates");
        Map<String, Object> ref = cands.stream()
                .filter(c -> "SEMANTIC".equals(c.get("type"))
                        && c.get("id").toString().contains("supplier_id"))
                .findFirst().orElseThrow();
        assertThat(ref.get("status")).isEqualTo("SUGGESTED");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ref.get("payload");
        assertThat(payload.get("semantic")).isEqualTo("reference");
    }

    // ------------------------------------------------------------------
    // PurchaseOrder 4-table import proof (backend level)
    // ------------------------------------------------------------------

    @Test
    void purchaseOrderFourTableImportEndToEnd() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        Map<String, Map<String, String>> mapping = Map.of(
                "supplier", Map.of("moduleId", "supplier", "entity", "Supplier"),
                "product", Map.of("moduleId", "product", "entity", "Product"),
                "purchase_order", Map.of("moduleId", "purchase-order", "entity", "PurchaseOrder"),
                "purchase_order_item", Map.of("moduleId", "purchase-order-item", "entity", "PurchaseOrderItem"));
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(),
                List.of("supplier", "product", "purchase_order", "purchase_order_item"), mapping);
        assertThat(drafts).hasSize(4);

        // decide: accept all FIELD + REFERENCE + RELATION (FK) candidates,
        // accept reverse O2M on purchase_order with composition=true (human)
        Map<String, String> decisions = new LinkedHashMap<>();
        Map<String, Map<String, Object>> edits = new LinkedHashMap<>();
        for (Map<String, Object> draft : drafts) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cands = (List<Map<String, Object>>) draft.get("candidates");
            for (Map<String, Object> c : cands) {
                String id = String.valueOf(c.get("id"));
                String type = String.valueOf(c.get("type"));
                if ("FIELD".equals(type) || "REFERENCE".equals(type)) {
                    decisions.put(id, "accept");
                } else if ("RELATION".equals(type)) {
                    decisions.put(id, "accept");
                    if (id.contains("reverse") && "purchase_order".equals(draft.get("table"))) {
                        // human confirmation: composition=true for Master/Detail
                        @SuppressWarnings("unchecked")
                        Map<String, Object> p = (Map<String, Object>) c.get("payload");
                        Map<String, Object> edit = new LinkedHashMap<>(p);
                        edit.put("composition", true);
                        edits.put(id, edit);
                    }
                }
                // SEMANTIC suggestions: accept money for total_amount/amount
                if ("SEMANTIC".equals(type) && c.get("id").toString().contains("money")) {
                    decisions.put(id, "accept");
                }
            }
        }

        Map<String, Object> poManifest = svc.resolveToManifest(
                drafts.stream().filter(d -> "purchase_order".equals(d.get("table"))).findFirst().orElseThrow(),
                decisions, edits);
        assertThat(ModuleContractValidator.validate(poManifest)).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> biz = (Map<String, Object>) poManifest.get("business");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations = (List<Map<String, Object>>) biz.get("relations");
        // MANY_TO_ONE supplier + reverse ONE_TO_MANY items
        assertThat(relations).extracting("type").contains("MANY_TO_ONE", "ONE_TO_MANY");
        Map<String, Object> items = relations.stream()
                .filter(r -> "ONE_TO_MANY".equals(r.get("type"))).findFirst().orElseThrow();
        assertThat(items.get("target")).isEqualTo("purchase-order-item");
        assertThat(items.get("composition")).isEqualTo(true);
        assertThat(items.get("mappedBy")).isEqualTo("purchaseOrderId");
        // supplier reference field present
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) ((Map<?, ?>) biz.get("entity")).get("fields");
        Map<String, Object> supplierId = fields.stream()
                .filter(f -> "supplierId".equals(f.get("name"))).findFirst().orElseThrow();
        assertThat(supplierId.get("semantic")).isEqualTo("reference");
        assertThat(((Map<?, ?>) supplierId.get("reference")).get("target")).isEqualTo("supplier");

        // item manifest: product reference + MANY_TO_ONE purchase_order
        Map<String, Object> itemManifest = svc.resolveToManifest(
                drafts.stream().filter(d -> "purchase_order_item".equals(d.get("table"))).findFirst().orElseThrow(),
                decisions, edits);
        assertThat(ModuleContractValidator.validate(itemManifest)).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> itemBiz = (Map<String, Object>) itemManifest.get("business");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemFields = (List<Map<String, Object>>) ((Map<?, ?>) itemBiz.get("entity")).get("fields");
        Map<String, Object> productId = itemFields.stream()
                .filter(f -> "productId".equals(f.get("name"))).findFirst().orElseThrow();
        assertThat(productId.get("semantic")).isEqualTo("reference");
        assertThat(((Map<?, ?>) productId.get("reference")).get("target")).isEqualTo("product");

        // supplier/product modules import cleanly (no FK)
        Map<String, Object> supplierManifest = svc.resolveToManifest(
                drafts.stream().filter(d -> "supplier".equals(d.get("table"))).findFirst().orElseThrow(),
                decisions, edits);
        assertThat(ModuleContractValidator.validate(supplierManifest)).isEmpty();
    }

    @Test
    void noFkTableStillImports() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(), List.of("supplier"), null);
        assertThat(drafts).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cands = (List<Map<String, Object>>) drafts.get(0).get("candidates");
        assertThat(cands.stream().anyMatch(c -> "RELATION".equals(c.get("type")))).isFalse();
        assertThat(cands.stream().anyMatch(c -> "REFERENCE".equals(c.get("type")))).isFalse();
    }

    // ------------------------------------------------------------------
    // Generator integration — confirmed Contract → existing pipeline
    // (targeted proof only; full relation generation is WORK-002/003)
    // ------------------------------------------------------------------

    @Test
    void confirmedContractReachesExistingGenerator() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        GenerationService svc = new GenerationService(ROOT, tmp2.resolve("gen-data"));
        ModuleStore store = new ModuleStore(tmp2.resolve("modules"));
        ImportCandidateService candidateSvc = new ImportCandidateService();

        Map<String, Map<String, String>> mapping = Map.of(
                "supplier", Map.of("moduleId", "supplier", "entity", "Supplier"),
                "purchase_order", Map.of("moduleId", "purchase-order", "entity", "PurchaseOrder"));
        List<Map<String, Object>> drafts = candidateSvc.discoverMysql(proofConn(),
                List.of("supplier", "purchase_order"), mapping);

        // confirm everything confirmable (fields + FK reference/relation)
        Map<String, String> decisions = new LinkedHashMap<>();
        for (Map<String, Object> draft : drafts) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cands = (List<Map<String, Object>>) draft.get("candidates");
            for (Map<String, Object> c : cands) {
                String type = String.valueOf(c.get("type"));
                if ("FIELD".equals(type) || "REFERENCE".equals(type) || "RELATION".equals(type)) {
                    decisions.put(String.valueOf(c.get("id")), "accept");
                }
            }
        }
        Map<String, Object> manifest = candidateSvc.resolveToManifest(
                drafts.stream().filter(d -> "purchase_order".equals(d.get("table"))).findFirst().orElseThrow(),
                decisions, Map.of());
        assertThat(ModuleContractValidator.validate(manifest)).isEmpty();
        store.save(manifest);

        // full project contract referencing the confirmed module (same shape as
        // Console handleModuleGenerate)
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schemaVersion", 1);
        contract.put("project", Map.of(
                "id", "po-import-proof",
                "name", "PO Import Proof",
                "version", "1.0.0",
                "basePackage", "com.acme.core",
                "groupId", "com.acme",
                "artifactId", "po-import-proof"));
        contract.put("platform", Map.of("id", "engineering-platform"));
        contract.put("application", Map.of("profile", "enterprise"));
        contract.put("stack", Map.of("profile", "enterprise-java25"));
        contract.put("frontends", List.of(Map.of("id", "admin", "template", "enterprise-admin")));
        contract.put("modules", List.of("purchase-order", "supplier"));
        contract.put("capabilities", List.of(
                Map.of("id", "web"), Map.of("id", "validation"), Map.of("id", "exception-handling"),
                Map.of("id", "platform-core"), Map.of("id", "authentication"), Map.of("id", "rbac"),
                Map.of("id", "organization"), Map.of("id", "data-permission"), Map.of("id", "menu"),
                Map.of("id", "dictionary"), Map.of("id", "operation-log"),
                Map.of("id", "frontend-shell"), Map.of("id", "frontend-auth"),
                Map.of("id", "frontend-permission"), Map.of("id", "frontend-enterprise-management"),
                Map.of("id", "runtime-recipe")));
        contract.put("quality", Map.of("minimum", "Q2"));

        List<Map<String, Object>> extra = new ArrayList<>();
        extra.add((Map<String, Object>) AssetYamlReader.parse(
                String.valueOf(store.get("purchase-order").get("yaml"))));
        for (String id : List.of("supplier", "product")) {
            Path f = ROOT.resolve("tests/fixtures/v07-reference/generic/modules").resolve(id + ".yaml");
            extra.add((Map<String, Object>) AssetYamlReader.parse(Files.readString(f)));
        }

        Path out = tmp2.resolve("out");
        Map<String, Object> result = svc.generateWithModules(contract, extra, out);
        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/application/purchaseorder/PurchaseOrderService.java")))
                .as("confirmed import contract reaches the generator").isTrue();
        assertThat(Files.exists(out.resolve("project.yaml"))).isTrue();
    }

    @TempDir
    Path tmp2;

    // ------------------------------------------------------------------
    // Security — password never leaks into candidates/contracts
    // ------------------------------------------------------------------

    @Test
    void passwordNeverAppearsInDiscoveryOutput() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mysqlAvailable(), "local MySQL not available");
        ImportCandidateService svc = new ImportCandidateService();
        List<Map<String, Object>> drafts = svc.discoverMysql(proofConn(), List.of("purchase_order", "supplier"), null);
        String json = Json.write(drafts);
        assertThat(json).doesNotContain("123456").doesNotContain("password");
        // resolved contract also clean
        Map<String, Object> manifest = svc.resolveToManifest(drafts.get(0), Map.of(), Map.of());
        assertThat(Json.write(manifest)).doesNotContain("123456").doesNotContain("password");
    }
}
