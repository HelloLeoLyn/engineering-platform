package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * V06-WORK-001 — Resolved Generic Business Module.
 *
 * Structured input for the future Module Generator (WORK-002): the resolved
 * business module contract, not a raw Map. Produced by BusinessModuleResolver
 * from the module manifest's optional {@code business} section.
 *
 * @param id         module id (kebab-case)
 * @param name       module display name
 * @param version    resolved version (may be null)
 * @param table      database table name (single-table model in V0.6)
 * @param entity     entity definition (name + fields)
 * @param features   supported CRUD features (list/search/create/edit/detail/enable/disable)
 * @param enterprise enterprise integration flags (permissions/dataScope/menu/dictionary/operationLog)
 * @param frontend   module-level frontend metadata (optional)
 * @param relations  V0.7: structured business relations (may be empty; V0.6
 *                   modules without relations resolve exactly as before)
 */
public record ResolvedBusinessModule(
        String id,
        String name,
        String version,
        String table,
        BusinessEntity entity,
        List<String> features,
        Map<String, Object> enterprise,
        Map<String, Object> frontend,
        List<ResolvedRelation> relations) {

    public ResolvedBusinessModule {
        features = features == null ? List.of() : List.copyOf(features);
        enterprise = enterprise == null ? Map.of() : Map.copyOf(enterprise);
        frontend = frontend == null ? Map.of() : Map.copyOf(frontend);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    /**
     * V0.6-compatible constructor (no relations). Kept so existing callers
     * outside the resolver still compile unchanged; relations default to empty.
     */
    public ResolvedBusinessModule(
            String id,
            String name,
            String version,
            String table,
            BusinessEntity entity,
            List<String> features,
            Map<String, Object> enterprise,
            Map<String, Object> frontend) {
        this(id, name, version, table, entity, features, enterprise, frontend, List.of());
    }

    /** Entity definition (V06-WORK-001 Generic Business Module Contract). */
    public record BusinessEntity(
            String name,
            List<BusinessEntityField> fields) {

        public BusinessEntity {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }
}
