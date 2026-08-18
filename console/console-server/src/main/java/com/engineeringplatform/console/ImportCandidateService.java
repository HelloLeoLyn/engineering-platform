package com.engineeringplatform.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V07-WORK-005 — Import Candidate generation + Review resolution.
 *
 * Turns external metadata (MySQL schema, Excel template) into Candidates:
 *
 *   FIELD      — every column (DETECTED / DATABASE_COLUMN)
 *   REFERENCE  — real FK column (DETECTED / DATABASE_FK); valueField prefilled,
 *                labelField/searchFields left for human confirmation
 *   RELATION   — real FK → MANY_TO_ONE (DETECTED / DATABASE_FK);
 *                reverse ONE_TO_MANY (SUGGESTED — derived, not in the DB)
 *   SEMANTIC   — heuristic suggestions (SUGGESTED / *_HEURISTIC)
 *
 * Only CONFIRMED candidates may be serialized into a Business Module Contract.
 * Nothing here writes to the formal contract directly.
 */
public final class ImportCandidateService {

    private final MySqlImportService mysql = new MySqlImportService();

    // ------------------------------------------------------------------
    // MySQL discovery → module drafts with candidates
    // ------------------------------------------------------------------

    /**
     * Discover candidates for multiple tables.
     *
     * @param conn     JDBC connection info (password used only for the connection)
     * @param tables   selected table names
     * @param mapping  optional table → {moduleId, entity} overrides; null → defaults
     */
    public List<Map<String, Object>> discoverMysql(MySqlImportService.ConnectionInfo conn,
                                                   List<String> tables,
                                                   Map<String, Map<String, String>> mapping) throws Exception {
        return discoverMysqlAll(conn, tables, mapping);
    }

    private Map<String, Object> buildDraft(MySqlImportService.TableMeta meta,
                                           Map<String, String> moduleIdOf,
                                           Map<String, String> entityOf,
                                           List<String> importedTables) {
        String table = meta.table;
        String moduleId = moduleIdOf.get(table);
        String entity = entityOf.get(table);
        Set<String> imported = new LinkedHashSet<>(importedTables);

        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> fkColumns = new LinkedHashSet<>();
        for (Map<String, Object> fk : meta.foreignKeys) {
            fkColumns.add(String.valueOf(fk.get("column")));
        }

        // 1. FIELD candidates — every column, definite fact from DB.
        // Column names are mapped to camelCase field names (contract pattern
        // ^[a-z][a-zA-Z0-9]*$ forbids underscores); the raw column name stays
        // in the payload as "column" for reference but never enters the contract.
        int fieldNo = 0;
        for (Map<String, Object> col : meta.columns) {
            String rawName = String.valueOf(col.get("name"));
            Map<String, Object> payload = new LinkedHashMap<>(col);
            payload.put("name", snakeToCamel(rawName));
            payload.put("column", rawName);
            payload.put("order", fieldNo++);
            candidates.add(ImportCandidateModel.candidate(
                    table + "." + rawName + ".field",
                    ImportCandidateModel.FIELD, ImportCandidateModel.DETECTED,
                    ImportCandidateModel.SRC_DATABASE_COLUMN, moduleId, table,
                    payload));
        }

        // 2. REFERENCE + RELATION candidates — real FK
        for (Map<String, Object> fk : meta.foreignKeys) {
            String colName = String.valueOf(fk.get("column"));
            String refTable = String.valueOf(fk.get("referencedTable"));
            String refColumn = String.valueOf(fk.get("referencedColumn"));
            boolean targetImported = imported.contains(refTable);
            String targetModule = targetImported ? moduleIdOf.getOrDefault(refTable, tableToModuleId(refTable))
                                                 : tableToModuleId(refTable);

            // REFERENCE candidate (DETECTED) — valueField prefilled from FK,
            // labelField/searchFields intentionally NOT auto-filled.
            Map<String, Object> refPayload = ImportCandidateModel.referencePayload(
                    snakeToCamel(colName), targetModule, refTable, refColumn, "", List.of());
            Map<String, Object> ref = ImportCandidateModel.candidate(
                    table + "." + colName + ".reference",
                    ImportCandidateModel.REFERENCE, ImportCandidateModel.DETECTED,
                    ImportCandidateModel.SRC_DATABASE_FK, moduleId, table, refPayload);
            ref.put("note", "real FK → " + refTable + "." + refColumn
                    + (targetImported ? "" : " (target table not imported — unresolved)"));
            if (!targetImported) ref.put("unresolved", true);
            candidates.add(ref);

            // RELATION MANY_TO_ONE (DETECTED)
            Map<String, Object> relPayload = ImportCandidateModel.relationPayload(
                    targetModule, "MANY_TO_ONE", targetModule,
                    snakeToCamel(colName), refColumn, null,
                    isRequiredColumn(meta, colName), false);
            Map<String, Object> rel = ImportCandidateModel.candidate(
                    table + "." + colName + ".relation",
                    ImportCandidateModel.RELATION, ImportCandidateModel.DETECTED,
                    ImportCandidateModel.SRC_DATABASE_FK, moduleId, table, relPayload);
            rel.put("note", "real FK relation (MANY_TO_ONE)");
            if (!targetImported) rel.put("unresolved", true);
            candidates.add(rel);
        }

        // 3. Reverse ONE_TO_MANY — derived from FKs pointing AT this table.
        // Not part of the FK itself → SUGGESTED. composition stays false until
        // a human explicitly confirms it.
        for (MySqlImportService.TableMeta other : allMetas) {
            if (!other.table.equals(table)) {
                for (Map<String, Object> fk : other.foreignKeys) {
                    if (table.equals(String.valueOf(fk.get("referencedTable")))) {
                        String childModule = moduleIdOf.getOrDefault(other.table, tableToModuleId(other.table));
                        String childColumn = String.valueOf(fk.get("column"));
                        Map<String, Object> revPayload = ImportCandidateModel.relationPayload(
                                childModule, "ONE_TO_MANY", childModule,
                                null, String.valueOf(fk.get("referencedColumn")), snakeToCamel(childColumn),
                                false, false);
                        Map<String, Object> rev = ImportCandidateModel.candidate(
                                table + ".reverse." + other.table + ".relation",
                                ImportCandidateModel.RELATION, ImportCandidateModel.SUGGESTED,
                                ImportCandidateModel.SRC_DATABASE_FK, moduleId, table, revPayload);
                        rev.put("note", "reverse of real FK "
                                + other.table + "." + childColumn + " → " + table
                                + " (suggested — composition requires human confirmation)");
                        candidates.add(rev);
                    }
                }
            }
        }

        // 4. SEMANTIC suggestions — heuristics only, never auto-confirmed
        for (Map<String, Object> col : meta.columns) {
            String colName = String.valueOf(col.get("name"));
            String fieldName = snakeToCamel(colName);
            String type = String.valueOf(col.get("type"));
            boolean isFk = fkColumns.contains(colName);
            // DECIMAL(x,2) → possible money
            if ("decimal".equals(type)) {
                Object scale = col.get("scale");
                if (scale != null && "2".equals(String.valueOf(scale))) {
                    candidates.add(semanticCandidate(table, moduleId, fieldName, colName,
                            "money", "DECIMAL(...,2) → possible money",
                            ImportCandidateModel.SRC_TYPE_HEURISTIC));
                }
            }
            // *_id column (not a real FK) → possible reference
            if (colName.endsWith("_id") && !isFk) {
                candidates.add(semanticCandidate(table, moduleId, fieldName, colName,
                        "reference", "column name *_id → possible reference (no FK found)",
                        ImportCandidateModel.SRC_COLUMN_NAME_HEURISTIC));
            }
            // status / type / state / level → possible enum/dictionary
            String lower = colName.toLowerCase();
            if (lower.equals("status") || lower.equals("type") || lower.equals("state")
                    || lower.equals("level") || lower.endsWith("_status") || lower.endsWith("_type")) {
                candidates.add(semanticCandidate(table, moduleId, fieldName, colName,
                        "enum", "column name status/type → possible enum/dictionary",
                        ImportCandidateModel.SRC_COLUMN_NAME_HEURISTIC));
            }
        }

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("table", table);
        draft.put("comment", meta.comment);
        draft.put("moduleId", moduleId);
        draft.put("entity", entity);
        draft.put("fields", meta.columns);
        draft.put("candidates", candidates);
        return draft;
    }

    // all discovered metas (used for reverse candidates) — set before building
    private List<MySqlImportService.TableMeta> allMetas = new ArrayList<>();

    /** Bind the full table set before building drafts (for reverse FK scan). */
    public List<Map<String, Object>> discoverMysqlAll(MySqlImportService.ConnectionInfo conn,
                                                      List<String> tables,
                                                      Map<String, Map<String, String>> mapping) throws Exception {
        List<MySqlImportService.TableMeta> metas = mysql.discover(conn, tables);
        this.allMetas = metas;
        Map<String, String> moduleIdOf = new LinkedHashMap<>();
        Map<String, String> entityOf = new LinkedHashMap<>();
        for (String t : tables) {
            String mid = tableToModuleId(t);
            String ent = tableToEntity(t);
            if (mapping != null && mapping.containsKey(t)) {
                Map<String, String> m = mapping.get(t);
                if (m.get("moduleId") != null && !m.get("moduleId").isBlank()) mid = m.get("moduleId");
                if (m.get("entity") != null && !m.get("entity").isBlank()) ent = m.get("entity");
            }
            moduleIdOf.put(t, mid);
            entityOf.put(t, ent);
        }
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (MySqlImportService.TableMeta meta : metas) {
            drafts.add(buildDraft(meta, moduleIdOf, entityOf, tables));
        }
        return drafts;
    }

    // ------------------------------------------------------------------
    // Review resolution — CONFIRMED candidates → module manifest
    // ------------------------------------------------------------------

    /**
     * Resolve a reviewed draft into a Business Module Contract manifest.
     * Only CONFIRMED candidates are serialized. Everything else (DETECTED /
     * SUGGESTED / IGNORED) is dropped.
     *
     * @param draft      discovered draft {table, moduleId, entity, fields, candidates}
     * @param decisions  candidate id → "accept" | "ignore" (from the Review UI);
     *                   absent id = keep original status (confirmed only if already CONFIRMED)
     * @param edits      candidate id → edited payload (optional)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveToManifest(Map<String, Object> draft,
                                                 Map<String, String> decisions,
                                                 Map<String, Map<String, Object>> edits) {
        String moduleId = String.valueOf(draft.get("moduleId"));
        String entity = String.valueOf(draft.get("entity"));
        String table = String.valueOf(draft.get("table"));

        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        Map<String, Map<String, Object>> fieldPayloads = new LinkedHashMap<>();

        Object candidatesObj = draft.get("candidates");
        if (!(candidatesObj instanceof List<?>)) {
            // empty / malformed draft → still produce a valid (empty) manifest
            return manifestOf(moduleId, entity, table, fields, relations);
        }
        List<?> candidateList = (List<?>) candidatesObj;

        // map field candidate id → payload for semantic/reference edits
        for (Object o : candidateList) {
            Map<String, Object> c = (Map<String, Object>) o;
            String type = String.valueOf(c.get("type"));
            String id = String.valueOf(c.get("id"));
            String decision = decisions == null ? null : decisions.get(id);
            boolean confirmed = "accept".equals(decision)
                    || (decision == null && ImportCandidateModel.isConfirmed(c));
            if (!confirmed) continue;

            Map<String, Object> payload = new LinkedHashMap<>();
            if (edits != null && edits.containsKey(id)) {
                payload.putAll(edits.get(id));
            } else {
                payload.putAll((Map<String, Object>) c.get("payload"));
            }

            if (ImportCandidateModel.FIELD.equals(type)) {
                // Contract-safe field: only schema-allowed keys, camelCase name.
                Map<String, Object> clean = cleanField(payload);
                fields.add(clean);
                fieldPayloads.put(String.valueOf(clean.get("name")), clean);
            } else if (ImportCandidateModel.REFERENCE.equals(type)) {
                // attach reference config to the matching field
                String fieldName = String.valueOf(payload.get("field"));
                Map<String, Object> f = fieldPayloads.get(fieldName);
                if (f != null) {
                    f.put("semantic", "reference");
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("target", payload.get("targetModule"));
                    ref.put("valueField", payload.get("valueField"));
                    // DB cannot decide labelField — only write it when confirmed
                    Object label = payload.get("labelField");
                    if (label != null && !String.valueOf(label).isBlank()) {
                        ref.put("labelField", label);
                    }
                    Object search = payload.get("searchFields");
                    if (search instanceof List<?> sl && !sl.isEmpty()) {
                        ref.put("searchFields", sl);
                    }
                    f.put("reference", ref);
                }
            } else if (ImportCandidateModel.RELATION.equals(type)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", snakeToCamel(String.valueOf(payload.get("name"))));
                r.put("type", payload.get("type"));
                r.put("target", payload.get("targetModule"));
                if (payload.get("localField") != null && !String.valueOf(payload.get("localField")).isBlank()) {
                    r.put("localField", snakeToCamel(String.valueOf(payload.get("localField"))));
                }
                if (payload.get("targetField") != null && !String.valueOf(payload.get("targetField")).isBlank()) {
                    r.put("targetField", snakeToCamel(String.valueOf(payload.get("targetField"))));
                }
                if (payload.get("mappedBy") != null && !String.valueOf(payload.get("mappedBy")).isBlank()) {
                    r.put("mappedBy", snakeToCamel(String.valueOf(payload.get("mappedBy"))));
                }
                if (Boolean.TRUE.equals(payload.get("required"))) r.put("required", true);
                if (Boolean.TRUE.equals(payload.get("composition"))) r.put("composition", true);
                relations.add(r);
            } else if (ImportCandidateModel.SEMANTIC.equals(type)) {
                // semantic suggestion accepted → apply to field, but only when
                // the suggestion can be expressed as a VALID contract.
                String fieldName = String.valueOf(payload.get("field"));
                Map<String, Object> f = fieldPayloads.get(fieldName);
                String semantic = String.valueOf(payload.get("semantic"));
                if (f == null) continue;
                switch (semantic) {
                    case "money" -> {
                        // Contract V2 convention: money is a TYPE (schema type
                        // enum includes money; semantic enum does NOT).
                        f.put("type", "money");
                        f.remove("semantic");
                    }
                    case "enum" -> {
                        // enum requires explicit values — the DB heuristic alone
                        // can never provide them, so without user-supplied values
                        // the suggestion is NOT written (contract stays valid).
                        Object values = payload.get("enumValues");
                        if (values == null) values = payload.get("values");
                        List<Object> enumValues = values instanceof List<?> l
                                ? new ArrayList<>(l) : List.of();
                        if (!enumValues.isEmpty()) {
                            f.put("type", "enum");
                            f.put("semantic", "enum");
                            f.put("enum", Map.of("values", enumValues));
                        }
                    }
                    case "reference" -> {
                        // *_id heuristic without an explicit target cannot form
                        // a valid reference contract → require targetModule.
                        Object target = payload.get("targetModule");
                        if (target != null && !String.valueOf(target).isBlank()) {
                            f.put("semantic", "reference");
                            Map<String, Object> ref = new LinkedHashMap<>();
                            ref.put("target", target);
                            ref.put("valueField", payload.getOrDefault("valueField", "id"));
                            Object label = payload.get("labelField");
                            if (label != null && !String.valueOf(label).isBlank()) {
                                ref.put("labelField", label);
                            }
                            Object search = payload.get("searchFields");
                            if (search instanceof List<?> sl && !sl.isEmpty()) {
                                ref.put("searchFields", sl);
                            }
                            f.put("reference", ref);
                        }
                    }
                    default -> {
                        // status/dictionary/etc. kept as-is (no automatic semantic)
                    }
                }
            }
        }

        Map<String, Object> manifest = manifestOf(moduleId, entity, table, fields, relations);
        return manifest;
    }

    private static Map<String, Object> manifestOf(String moduleId, String entity, String table,
                                                  List<Map<String, Object>> fields,
                                                  List<Map<String, Object>> relations) {
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("table", table);
        business.put("entity", Map.of("name", entity, "fields", fields));
        if (!relations.isEmpty()) business.put("relations", relations);
        business.put("features", List.of("list", "search", "create", "edit", "detail", "disable"));
        business.put("enterprise", Map.of(
                "permissions", true, "dataScope", true, "menu", true, "dictionary", true, "operationLog", true));
        business.put("frontend", Map.of(
                "route", "/" + moduleId.toLowerCase().replace("_", "").replace("-", ""),
                "label", entity));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("module", Map.of(
                "id", moduleId,
                "name", entity,
                "version", "1.0.0",
                "type", "business",
                "description", "Imported via Console Import Review (V07-WORK-005)"));
        manifest.put("compatibility", Map.of("platformVersion", "0.6"));
        manifest.put("business", business);
        return manifest;
    }

    // ------------------------------------------------------------------
    // Excel discovery → module draft with candidates (V07-WORK-005 §11/§12)
    // ------------------------------------------------------------------

    /**
     * Build a single-module draft from Excel template rows.
     *
     * Supported optional columns (V0.7):
     *   referenceTarget / referenceValueField / referenceLabelField
     *   relationType / relationTarget / mappedBy / composition
     * Explicit relation/reference input → DETECTED_FROM_EXPLICIT_INPUT
     * (still requires Review — never bypasses the Builder).
     * Heuristics (supplier_id → Possible Reference, amount decimal → Possible
     * Money, status/type → Possible Enum) → SUGGESTED only.
     *
     * @param headers   header row (column names)
     * @param rows      data rows (one per field)
     * @param moduleId  module id (may be edited by user)
     * @param entity    entity name (may be edited by user)
     */
    public Map<String, Object> discoverExcel(List<String> headers, List<List<String>> rows,
                                             String moduleId, String entity) {
        String table = moduleId.replace('-', '_');
        int idxField = headers.indexOf("field");
        int idxColumn = headers.indexOf("column");
        int idxType = headers.indexOf("type");
        int idxRequired = headers.indexOf("required");
        int idxPk = headers.indexOf("primaryKey");
        int idxUnique = headers.indexOf("unique");
        int idxLength = headers.indexOf("length");
        int idxComment = headers.indexOf("comment");
        int idxRefTarget = headers.indexOf("referenceTarget");
        int idxRefValue = headers.indexOf("referenceValueField");
        int idxRefLabel = headers.indexOf("referenceLabelField");
        int idxRelType = headers.indexOf("relationType");
        int idxRelTarget = headers.indexOf("relationTarget");
        int idxMappedBy = headers.indexOf("mappedBy");
        int idxComposition = headers.indexOf("composition");

        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> candidates = new ArrayList<>();

        int order = 0;
        for (List<String> row : rows) {
            String rawName = cell(row, idxField);
            if (rawName == null || rawName.isBlank()) rawName = cell(row, idxColumn);
            if (rawName == null || rawName.isBlank()) continue;
            // contract-safe camelCase field name (pattern forbids underscores)
            String name = snakeToCamel(rawName);

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", name);
            f.put("column", rawName);
            String type = cell(row, idxType);
            f.put("type", type == null || type.isBlank() ? "string" : type);
            f.put("required", "true".equalsIgnoreCase(cell(row, idxRequired)));
            f.put("primaryKey", "true".equalsIgnoreCase(cell(row, idxPk)));
            f.put("unique", "true".equalsIgnoreCase(cell(row, idxUnique)));
            String len = cell(row, idxLength);
            if (len != null && !len.isBlank()) f.put("length", Integer.parseInt(len));
            String comment = cell(row, idxComment);
            if (comment != null && !comment.isBlank()) f.put("comment", comment);
            f.put("order", order);
            fields.add(f);

            // FIELD candidate (DETECTED from explicit Excel metadata)
            candidates.add(ImportCandidateModel.candidate(
                    moduleId + "." + rawName + ".field",
                    ImportCandidateModel.FIELD, ImportCandidateModel.DETECTED,
                    ImportCandidateModel.SRC_EXCEL_METADATA, moduleId, table,
                    new LinkedHashMap<>(f)));

            // Explicit reference columns → DETECTED_FROM_EXPLICIT_INPUT
            String refTarget = cell(row, idxRefTarget);
            if (refTarget != null && !refTarget.isBlank()) {
                String refValue = cell(row, idxRefValue);
                String refLabel = cell(row, idxRefLabel);
                Map<String, Object> refPayload = ImportCandidateModel.referencePayload(
                        name, refTarget, refTarget,
                        refValue == null || refValue.isBlank() ? "id" : refValue,
                        refLabel == null ? "" : refLabel, List.of());
                Map<String, Object> ref = ImportCandidateModel.candidate(
                        moduleId + "." + rawName + ".reference",
                        ImportCandidateModel.REFERENCE, ImportCandidateModel.DETECTED,
                        ImportCandidateModel.SRC_EXCEL_EXPLICIT, moduleId, table, refPayload);
                ref.put("note", "explicit reference input in template — review & confirm");
                candidates.add(ref);
            }

            // Explicit relation columns → DETECTED_FROM_EXPLICIT_INPUT
            String relType = cell(row, idxRelType);
            String relTarget = cell(row, idxRelTarget);
            if (relType != null && !relType.isBlank() && relTarget != null && !relTarget.isBlank()) {
                String mappedBy = cell(row, idxMappedBy);
                boolean composition = "true".equalsIgnoreCase(cell(row, idxComposition));
                String localField = "MANY_TO_ONE".equalsIgnoreCase(relType) ? name : null;
                Map<String, Object> relPayload = ImportCandidateModel.relationPayload(
                        relTarget, relType.toUpperCase(), relTarget,
                        localField, "id", mappedBy == null ? null : snakeToCamel(mappedBy),
                        false, composition);
                Map<String, Object> rel = ImportCandidateModel.candidate(
                        moduleId + "." + rawName + ".relation." + relType.toUpperCase(),
                        ImportCandidateModel.RELATION, ImportCandidateModel.DETECTED,
                        ImportCandidateModel.SRC_EXCEL_EXPLICIT, moduleId, table, relPayload);
                rel.put("note", "explicit relation input in template — review & confirm");
                candidates.add(rel);
            }

            // Heuristics — SUGGESTED only
            if (rawName.endsWith("_id") && (refTarget == null || refTarget.isBlank())) {
                candidates.add(excelSemanticCandidate(moduleId, table, name, rawName,
                        "reference", "column name *_id → possible reference",
                        ImportCandidateModel.SRC_COLUMN_NAME_HEURISTIC));
            }
            String t = type == null ? "" : type.toLowerCase();
            if ((t.equals("decimal") || t.equals("double") || t.equals("float"))
                    && (rawName.contains("amount") || rawName.contains("price") || rawName.contains("money")
                        || rawName.contains("total") || rawName.contains("cost"))) {
                candidates.add(excelSemanticCandidate(moduleId, table, name, rawName,
                        "money", "decimal column named amount/price/money → possible money",
                        ImportCandidateModel.SRC_TYPE_HEURISTIC));
            }
            String lower = rawName.toLowerCase();
            if (lower.equals("status") || lower.equals("type") || lower.endsWith("_status")
                    || lower.endsWith("_type")) {
                candidates.add(excelSemanticCandidate(moduleId, table, name, rawName,
                        "enum", "column name status/type → possible enum/dictionary",
                        ImportCandidateModel.SRC_COLUMN_NAME_HEURISTIC));
            }
            order++;
        }

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("table", table);
        draft.put("comment", "Excel import: " + moduleId);
        draft.put("moduleId", moduleId);
        draft.put("entity", entity == null || entity.isBlank() ? tableToEntity(moduleId) : entity);
        draft.put("fields", fields);
        draft.put("candidates", candidates);
        return draft;
    }

    private static Map<String, Object> excelSemanticCandidate(String moduleId, String table,
                                                              String fieldName, String column,
                                                              String semantic, String reason, String source) {
        Map<String, Object> c = ImportCandidateModel.candidate(
                moduleId + "." + column + ".semantic." + semantic,
                ImportCandidateModel.SEMANTIC, ImportCandidateModel.SUGGESTED,
                source, moduleId, table,
                ImportCandidateModel.semanticPayload(fieldName, semantic, reason));
        c.put("note", "heuristic — suggested, not confirmed");
        return c;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) return null;
        String v = row.get(idx);
        return v == null ? null : v.trim();
    }

    // ------------------------------------------------------------------
    // naming helpers
    // ------------------------------------------------------------------

    public static String tableToModuleId(String table) {
        return table.replace('_', '-').toLowerCase();
    }

    public static String tableToEntity(String table) {
        StringBuilder sb = new StringBuilder();
        for (String part : table.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.length() == 0 ? "Entity" : sb.toString();
    }

    private static boolean isRequiredColumn(MySqlImportService.TableMeta meta, String column) {
        for (Map<String, Object> col : meta.columns) {
            if (column.equals(String.valueOf(col.get("name")))) {
                return Boolean.TRUE.equals(col.get("required"));
            }
        }
        return false;
    }

    private static Map<String, Object> semanticCandidate(String table, String moduleId,
                                                         String fieldName, String column,
                                                         String semantic, String reason, String source) {
        Map<String, Object> c = ImportCandidateModel.candidate(
                table + "." + column + ".semantic." + semantic,
                ImportCandidateModel.SEMANTIC, ImportCandidateModel.SUGGESTED,
                source, moduleId, table,
                ImportCandidateModel.semanticPayload(fieldName, semantic, reason));
        c.put("note", "heuristic — suggested, not confirmed");
        return c;
    }

    /** snake_case / kebab-case → camelCase (supplier_id → supplierId). */
    static String snakeToCamel(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char ch : s.toCharArray()) {
            if (ch == '_' || ch == '-') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Contract-safe field: only schema-allowed keys, camelCase name. The raw
     * DB column stays OUT of the contract (field name pattern
     * ^[a-z][a-zA-Z0-9]*$ forbids underscores).
     */
    private static Map<String, Object> cleanField(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", snakeToCamel(String.valueOf(payload.get("name"))));
        out.put("type", payload.getOrDefault("type", "string"));
        if (Boolean.TRUE.equals(payload.get("required"))) out.put("required", true);
        if (Boolean.TRUE.equals(payload.get("primaryKey"))) out.put("primaryKey", true);
        if (Boolean.TRUE.equals(payload.get("unique"))) out.put("unique", true);
        if (payload.get("length") != null) out.put("length", payload.get("length"));
        if (payload.get("precision") != null) out.put("precision", payload.get("precision"));
        if (payload.get("scale") != null) out.put("scale", payload.get("scale"));
        if (payload.get("defaultValue") != null) out.put("default", payload.get("defaultValue"));
        if (payload.get("comment") != null && !String.valueOf(payload.get("comment")).isBlank()) {
            out.put("comment", payload.get("comment"));
        }
        return out;
    }
}
