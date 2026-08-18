package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.BusinessEntityField;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;
import com.engineeringplatform.generator.contracts.ResolvedRelation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V07-WORK-002 — Relationship-aware database migration rendering.
 *
 * Produces a SINGLE deterministic relations migration (V200__relations.sql)
 * that runs AFTER every per-module baseline migration (V100+V module index).
 * Module baselines are generated in sorted module-id order, so a baseline can
 * reference a table that does not exist yet (purchase_order -> supplier when
 * supplier sorts after purchase-order). All foreign keys are therefore applied
 * in one pass after every table exists — never inlined into CREATE TABLE.
 *
 * FK sources (all explicit Contract, never inferred):
 *   - semantic=reference fields   -> field -> reference.target (valueField, default id)
 *   - MANY_TO_ONE / ONE_TO_ONE    -> localField -> target table (targetField, default id)
 *   - ONE_TO_MANY                 -> target module's mappedBy -> this module's id
 *
 * Columns are REUSED when already declared by the Contract (no duplicate
 * column generation). MANY_TO_MANY is rejected by the resolver in V0.7, so it
 * never reaches this renderer.
 */
public final class MigrationRelationRenderer {

    /** One foreign key (table-level, deduplicated by table+column). */
    public record ForeignKey(String table, String column, String targetTable, String targetColumn) {
    }

    public static final int RELATIONS_MIGRATION = 200; // after V100+moduleIndex baselines

    /**
     * Collect every FK implied by the modules' structured relations and
     * reference fields. Deterministic order: sorted by (table, column).
     */
    public List<ForeignKey> collect(List<ResolvedBusinessModule> modules) {
        Map<String, ResolvedBusinessModule> byId = new LinkedHashMap<>();
        for (ResolvedBusinessModule m : modules) {
            byId.put(m.id(), m);
        }
        Map<String, ForeignKey> unique = new LinkedHashMap<>();
        for (ResolvedBusinessModule m : modules) {
            String table = tableOf(m);
            // reference fields (semantic=reference) -> target table
            for (BusinessEntityField f : m.entity().fields()) {
                if (GenericModuleGenerator.isSystemField(f)) continue;
                if (!"reference".equals(f.semantic())) continue;
                String target = str(f.reference().get("target"));
                ResolvedBusinessModule targetModule = byId.get(target);
                if (targetModule == null) continue; // resolver already rejects unknown targets
                String targetColumn = str(f.reference().get("valueField"));
                if (targetColumn == null || targetColumn.isBlank()) targetColumn = "id";
                unique.putIfAbsent(table + "." + snake(f.name()),
                        new ForeignKey(table, snake(f.name()), tableOf(targetModule), targetColumn));
            }
            // relations
            for (ResolvedRelation r : m.relations()) {
                ResolvedBusinessModule targetModule = byId.get(r.target());
                if (targetModule == null) continue; // resolver already rejects unknown targets
                String targetColumn = r.targetField() == null || r.targetField().isBlank() ? "id" : r.targetField();
                switch (r.type()) {
                    case "MANY_TO_ONE", "ONE_TO_ONE" -> {
                        if (r.localField() == null || r.localField().isBlank()) continue;
                        unique.putIfAbsent(table + "." + snake(r.localField()),
                                new ForeignKey(table, snake(r.localField()), tableOf(targetModule), targetColumn));
                    }
                    case "ONE_TO_MANY" -> {
                        if (r.mappedBy() == null || r.mappedBy().isBlank()) continue;
                        // child -> parent FK lives on the TARGET module's table
                        unique.putIfAbsent(tableOf(targetModule) + "." + snake(r.mappedBy()),
                                new ForeignKey(tableOf(targetModule), snake(r.mappedBy()), table, "id"));
                    }
                    default -> { /* MANY_TO_MANY rejected by resolver; nothing to render */ }
                }
            }
        }
        List<ForeignKey> fks = new ArrayList<>(unique.values());
        fks.sort(Comparator.comparing(ForeignKey::table).thenComparing(ForeignKey::column));
        return fks;
    }

    /**
     * Render the full V200__relations.sql content for the module set.
     * Returns null when no FK exists (no relations migration needed).
     */
    public String render(List<ResolvedBusinessModule> modules) {
        List<ForeignKey> fks = collect(modules);
        if (fks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("-- relations schema (generated by engineering-platform Generic Module Generator)\n");
        sb.append("-- V07-WORK-002: structured relation foreign keys, applied after all module baselines.\n");
        sb.append("-- Columns are reused from module baselines — no duplicate column generation.\n");
        sb.append("-- MySQL-compatible baseline (H2 MODE=MySQL for tests).\n\n");
        for (ForeignKey fk : fks) {
            // index on the FK column (MySQL auto-indexes FK columns; H2 needs an explicit
            // index for efficient child lookups). Named deterministically.
            sb.append("CREATE INDEX idx_").append(fk.table()).append("_").append(fk.column())
                    .append(" ON ").append(fk.table()).append(" (").append(fk.column()).append(");\n");
            sb.append("ALTER TABLE ").append(fk.table())
                    .append(" ADD CONSTRAINT fk_").append(fk.table()).append("_").append(fk.column())
                    .append(" FOREIGN KEY (").append(fk.column()).append(")")
                    .append(" REFERENCES ").append(fk.targetTable()).append(" (").append(fk.targetColumn()).append(");\n");
        }
        return sb.toString();
    }

    private static String tableOf(ResolvedBusinessModule m) {
        return m.table() == null ? m.id().replace("-", "_") : m.table();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
    /** camelCase → snake_case (MyBatis-Plus default column-underline mapping). */
    private static String snake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

}
