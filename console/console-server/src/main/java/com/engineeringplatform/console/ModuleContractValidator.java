package com.engineeringplatform.console;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V07-WORK-004 — Console-side Business Module Contract validation.
 *
 * Lightweight structural checks for module manifests produced by the
 * Business Module Builder 2.0 (Fields Designer / Reference Designer /
 * Relations Designer). The EXISTING generator pipeline (schema validation +
 * CompleteResolver) remains the authoritative validation; this validator only
 * gives the Builder friendly categorized feedback before save/preview.
 *
 * Rules cover the Contract V2 module shape:
 *   schemaVersion / module / compatibility / business{table, entity, fields,
 *   relations, features, enterprise, frontend}
 * with V0.7 (WORK-001) structured reference / enum / relations support.
 *
 * No Console-private module schema: everything here mirrors the existing
 * Business Module Contract semantics.
 */
public final class ModuleContractValidator {

    private static final Set<String> FIELD_TYPES = Set.of(
            "string", "text", "integer", "long", "decimal", "boolean",
            "date", "datetime", "money", "enum", "status", "reference",
            "image", "file", "richtext");
    private static final Set<String> RELATION_TYPES = Set.of(
            "MANY_TO_ONE", "ONE_TO_MANY", "ONE_TO_ONE");
    private static final Set<String> RELATION_TYPES_UNSUPPORTED = Set.of("MANY_TO_MANY");

    private ModuleContractValidator() {}

    public static List<Map<String, Object>> validate(Map<String, Object> manifest) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (manifest == null) {
            errors.add(err("Invalid Module Contract", "Manifest is empty"));
            return errors;
        }

        // module identity
        Object moduleObj = manifest.get("module");
        if (!(moduleObj instanceof Map<?, ?> module)) {
            errors.add(err("Invalid Module Contract", "module section is required"));
            return errors;
        }
        Object id = module.get("id");
        if (id == null || !String.valueOf(id).matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
            errors.add(err("Invalid Module ID", "Module ID must match ^[a-z0-9]+(-[a-z0-9]+)*$"));
        }
        Object name = module.get("name");
        if (name == null || String.valueOf(name).isBlank()) {
            errors.add(err("Invalid Module Contract", "Module name is required"));
        }

        // business section
        Object bizObj = manifest.get("business");
        if (!(bizObj instanceof Map<?, ?> biz)) {
            errors.add(err("Invalid Module Contract", "business section is required"));
            return errors;
        }
        Object table = biz.get("table");
        if (table == null || String.valueOf(table).isBlank()) {
            errors.add(err("Invalid Module Contract", "business.table is required"));
        }
        Object entityObj = biz.get("entity");
        if (!(entityObj instanceof Map<?, ?> entity)) {
            errors.add(err("Invalid Module Contract", "business.entity is required"));
            return errors;
        }
        Object entityName = entity.get("name");
        if (entityName == null || String.valueOf(entityName).isBlank()) {
            errors.add(err("Invalid Module Contract", "business.entity.name is required"));
        }

        // fields (under business.entity.fields)
        Object fieldsObj = entity.get("fields");
        if (!(fieldsObj instanceof List<?> fields) || fields.isEmpty()) {
            errors.add(err("Invalid Module Contract", "At least one business.entity.fields entry is required"));
        } else {
            Set<String> names = new HashSet<>();
            int idx = 0;
            for (Object o : fields) {
                idx++;
                if (!(o instanceof Map<?, ?> f)) {
                    errors.add(err("Invalid Field", "Field #" + idx + " must be an object"));
                    continue;
                }
                String fname = f.get("name") == null ? "" : String.valueOf(f.get("name"));
                if (fname.isBlank()) {
                    errors.add(err("Invalid Field", "Field #" + idx + " name is required"));
                } else if (!names.add(fname)) {
                    errors.add(err("Invalid Field", "Duplicate field name: " + fname));
                }
                String ftype = f.get("type") == null ? "" : String.valueOf(f.get("type"));
                if (!FIELD_TYPES.contains(ftype)) {
                    errors.add(err("Invalid Field", "Field '" + (fname.isBlank() ? "#" + idx : fname)
                            + "' has unsupported type '" + ftype + "'"));
                }
                // V0.7 structured reference
                if ("reference".equals(f.get("semantic")) && f.get("reference") instanceof Map<?, ?> ref) {
                    Object target = ref.get("target");
                    if (target == null || String.valueOf(target).isBlank()) {
                        errors.add(err("Invalid Reference", "Field '" + fname + "' reference.target is required"));
                    }
                }
                // V0.7 structured enum
                if ("enum".equals(f.get("semantic")) || "enum".equals(ftype)) {
                    Object enumObj = f.get("enum");
                    if (!(enumObj instanceof Map<?, ?> em) || !(em.get("values") instanceof List<?> values)
                            || values.isEmpty()) {
                        errors.add(err("Invalid Enum", "Field '" + fname
                                + "' requires enum.values with at least one entry"));
                    }
                }
                // dictionary semantic needs a dictionary code
                if ("dictionary".equals(f.get("semantic")) && f.get("dictionary") == null) {
                    errors.add(err("Invalid Dictionary", "Field '" + fname
                            + "' semantic=dictionary requires a dictionary code"));
                }
            }
        }

        // relations (V0.7)
        Object relObj = biz.get("relations");
        if (relObj instanceof List<?> relations && !relations.isEmpty()) {
            Set<String> relNames = new HashSet<>();
            int idx = 0;
            for (Object o : relations) {
                idx++;
                if (!(o instanceof Map<?, ?> r)) {
                    errors.add(err("Invalid Relation", "Relation #" + idx + " must be an object"));
                    continue;
                }
                String rname = r.get("name") == null ? "" : String.valueOf(r.get("name"));
                if (rname.isBlank()) {
                    errors.add(err("Invalid Relation", "Relation #" + idx + " name is required"));
                } else if (!relNames.add(rname)) {
                    errors.add(err("Invalid Relation", "Duplicate relation name: " + rname));
                }
                String rtype = r.get("type") == null ? "" : String.valueOf(r.get("type"));
                if (RELATION_TYPES_UNSUPPORTED.contains(rtype)) {
                    errors.add(err("Unsupported Relation", "Relation '" + rname
                            + "' type MANY_TO_MANY is not supported yet"));
                } else if (!RELATION_TYPES.contains(rtype)) {
                    errors.add(err("Invalid Relation", "Relation '" + rname
                            + "' has unsupported type '" + rtype + "'"));
                }
                Object target = r.get("target");
                if (target == null || String.valueOf(target).isBlank()) {
                    errors.add(err("Invalid Relation", "Relation '" + rname + "' target module is required"));
                }
                if ("MANY_TO_ONE".equals(rtype) || "ONE_TO_ONE".equals(rtype)) {
                    if (r.get("localField") == null || String.valueOf(r.get("localField")).isBlank()) {
                        errors.add(err("Invalid Relation", "Relation '" + rname
                                + "' type " + rtype + " requires localField"));
                    }
                    if (r.get("targetField") == null || String.valueOf(r.get("targetField")).isBlank()) {
                        errors.add(err("Invalid Relation", "Relation '" + rname
                                + "' type " + rtype + " requires targetField"));
                    }
                }
                if ("ONE_TO_MANY".equals(rtype)) {
                    if (r.get("mappedBy") == null || String.valueOf(r.get("mappedBy")).isBlank()) {
                        errors.add(err("Invalid Relation", "Relation '" + rname
                                + "' type ONE_TO_MANY requires mappedBy"));
                    }
                }
            }
        }

        return errors;
    }

    private static Map<String, Object> err(String category, String message) {
        return Map.of("category", category, "message", message);
    }
}
