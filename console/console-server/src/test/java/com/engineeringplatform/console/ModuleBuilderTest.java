package com.engineeringplatform.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V06-WORK-005 — Module Builder targeted tests:
 * ModuleStore CRUD, XlsxSupport template parse, MySQL type mapping.
 */
class ModuleBuilderTest {

    @TempDir
    Path tmp;

    // ---- ModuleStore ----

    @Test
    void moduleStoreSavesAndListsModuleManifest() throws Exception {
        ModuleStore store = new ModuleStore(tmp.resolve("modules"));
        Map<String, Object> manifest = sampleManifest("customer-lite");
        Map<String, Object> saved = store.save(manifest);
        assertThat(saved.get("status")).isEqualTo("READY");

        List<Map<String, Object>> list = store.list();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id")).isEqualTo("customer-lite");

        Map<String, Object> got = store.get("customer-lite");
        assertThat((String) got.get("yaml")).contains("customer_lite").contains("phone");

        store.delete("customer-lite");
        assertThat(store.list()).isEmpty();
    }

    @Test
    void moduleStorePersistsToDisk() throws Exception {
        Path dir = tmp.resolve("modules2");
        ModuleStore store = new ModuleStore(dir);
        store.save(sampleManifest("warehouse-lite"));
        ModuleStore reopened = new ModuleStore(dir);
        assertThat(reopened.list()).hasSize(1);
        assertThat(reopened.list().get(0).get("id")).isEqualTo("warehouse-lite");
    }

    // ---- XlsxSupport ----

    @Test
    void xlsxTemplateRoundTrips() throws Exception {
        String[] headers = {"column", "field", "type", "label", "required", "primaryKey",
                "unique", "length", "comment", "searchable", "listVisible",
                "formVisible", "detailVisible", "dictionary"};
        byte[] xlsx = XlsxSupport.writeTemplate(headers);
        List<List<String>> rows = XlsxSupport.parseRows(xlsx);
        assertThat(rows).isNotEmpty();
        // header row
        assertThat(rows.get(0)).contains("column", "field", "type");
        // example row
        assertThat(rows.get(1).get(1)).isEqualTo("code");
        assertThat(rows.get(1).get(2)).isEqualTo("string");
    }

    // ---- MySQL type mapping ----

    @Test
    void mysqlTypeMappingCoversExpectedTypes() throws Exception {
        assertThat(map("varchar")).isEqualTo("string");
        assertThat(map("char")).isEqualTo("string");
        assertThat(map("text")).isEqualTo("text");
        assertThat(map("int")).isEqualTo("integer");
        assertThat(map("bigint")).isEqualTo("long");
        assertThat(map("decimal")).isEqualTo("decimal");
        assertThat(map("boolean")).isEqualTo("boolean");
        assertThat(map("tinyint")).isEqualTo("boolean");
        assertThat(map("date")).isEqualTo("date");
        assertThat(map("datetime")).isEqualTo("datetime");
        assertThat(map("timestamp")).isEqualTo("datetime");
    }

    private static String map(String mysqlType) {
        // reflectively reuse the private mapper
        try {
            var m = MySqlImportService.class.getDeclaredMethod("mapType", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, mysqlType);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sampleManifest(String id) {
        boolean warehouse = "warehouse-lite".equals(id);
        return Map.of(
                "schemaVersion", 1,
                "module", Map.of(
                        "id", id,
                        "name", warehouse ? "WarehouseLite" : "CustomerLite",
                        "version", "1.0.0",
                        "type", "business",
                        "description", "V06-WORK-005 generic proof domain (no dedicated capability)",
                        "compatibility", Map.of("platformVersion", "0.6"),
                        "business", Map.of(
                                "table", warehouse ? "warehouse_lite" : "customer_lite",
                                "entity", Map.of(
                                        "name", warehouse ? "WarehouseLite" : "CustomerLite",
                                        "fields", warehouse
                                                ? List.of(field("code"), field("name"), field("address"))
                                                : List.of(field("code"), field("name"), field("phone"))),
                                "features", List.of("list", "search", "create", "edit", "detail", "disable"),
                                "enterprise", Map.of(
                                        "permissions", true, "dataScope", true, "menu", true,
                                        "dictionary", true, "operationLog", true))));
    }

    private static Map<String, Object> field(String name) {
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("name", name);
        f.put("type", "string");
        f.put("required", true);
        f.put("length", 100);
        return f;
    }
}
