package com.engineeringplatform.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V07-WORK-005 — Unified Import Candidate model.
 *
 * External Metadata (MySQL schema / Excel template) is NEVER written directly
 * into the formal Business Module Contract. It becomes Candidates which the
 * user must review:
 *
 *   External Metadata → Candidate → Human Confirmation → Contract V2
 *
 * Status semantics:
 *   DETECTED  = data source provides a definite fact (e.g. a real FK)
 *   SUGGESTED = rule/heuristic based suggestion
 *   CONFIRMED = user accepted (only CONFIRMED may enter a formal contract)
 *   IGNORED   = user explicitly ignored
 *
 * Types: FIELD / REFERENCE / RELATION / SEMANTIC
 */
public final class ImportCandidateModel {

    private ImportCandidateModel() {}

    // ---- status ----
    public static final String DETECTED = "DETECTED";
    public static final String SUGGESTED = "SUGGESTED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String IGNORED = "IGNORED";

    // ---- type ----
    public static final String FIELD = "FIELD";
    public static final String REFERENCE = "REFERENCE";
    public static final String RELATION = "RELATION";
    public static final String SEMANTIC = "SEMANTIC";

    // ---- source ----
    public static final String SRC_DATABASE_FK = "DATABASE_FK";
    public static final String SRC_DATABASE_COLUMN = "DATABASE_COLUMN";
    public static final String SRC_DATABASE_INDEX = "DATABASE_INDEX";
    public static final String SRC_COLUMN_NAME_HEURISTIC = "COLUMN_NAME_HEURISTIC";
    public static final String SRC_TYPE_HEURISTIC = "TYPE_HEURISTIC";
    public static final String SRC_EXCEL_METADATA = "EXCEL_METADATA";
    public static final String SRC_EXCEL_EXPLICIT = "DETECTED_FROM_EXPLICIT_INPUT";
    public static final String SRC_USER_CONFIRMED = "USER_CONFIRMED";

    public static Map<String, Object> candidate(
            String id, String type, String status, String source,
            String moduleId, String table, Map<String, Object> payload) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("type", type);
        c.put("status", status);
        c.put("source", source);
        c.put("moduleId", moduleId);
        c.put("table", table);
        c.put("payload", payload == null ? Map.of() : payload);
        return c;
    }

    /** Whether a candidate may be serialized into a formal contract. */
    public static boolean isConfirmed(Map<String, Object> candidate) {
        return CONFIRMED.equals(candidate.get("status"));
    }

    // ---- payload builders ----

    public static Map<String, Object> fieldPayload(Map<String, Object> field) {
        return field;
    }

    public static Map<String, Object> referencePayload(
            String field, String targetModule, String targetTable,
            String valueField, String labelField, List<String> searchFields) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("field", field);
        p.put("targetModule", targetModule);
        p.put("targetTable", targetTable);
        p.put("valueField", valueField == null ? "id" : valueField);
        p.put("labelField", labelField == null ? "" : labelField);
        p.put("searchFields", searchFields == null ? List.of() : searchFields);
        return p;
    }

    public static Map<String, Object> relationPayload(
            String name, String type, String targetModule,
            String localField, String targetField, String mappedBy,
            boolean required, boolean composition) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("type", type);
        p.put("targetModule", targetModule);
        if (localField != null) p.put("localField", localField);
        if (targetField != null) p.put("targetField", targetField);
        if (mappedBy != null) p.put("mappedBy", mappedBy);
        p.put("required", required);
        p.put("composition", composition);
        return p;
    }

    public static Map<String, Object> semanticPayload(String field, String semantic, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("field", field);
        p.put("semantic", semantic);
        p.put("reason", reason);
        return p;
    }
}
