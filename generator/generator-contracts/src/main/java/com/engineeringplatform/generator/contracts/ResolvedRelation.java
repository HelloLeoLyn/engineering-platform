package com.engineeringplatform.generator.contracts;

/**
 * V07-WORK-001 — Resolved Business Relation.
 *
 * Structured relation between business modules (V0.7 Business Modeling).
 * Produced by BusinessModuleResolver from the module manifest's optional
 * {@code business.relations} section. Relations are ALWAYS explicit in the
 * Contract — generators must never infer relations from field names.
 *
 * @param name        relation name (unique within module)
 * @param type        MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE (MANY_TO_MANY is
 *                    schema-reserved but explicitly unsupported in V0.7)
 * @param target      target business module id
 * @param localField  FK field of this module (MANY_TO_ONE / ONE_TO_ONE)
 * @param mappedBy    FK field of the target module (ONE_TO_MANY)
 * @param targetField target field (defaults to id when not specified)
 * @param required    whether the relation is required
 * @param composition whether the relation carries composition lifecycle
 *                    semantics (sub-records follow master lifecycle); this is
 *                    a business semantic, not UI metadata
 */
public record ResolvedRelation(
        String name,
        String type,
        String target,
        String localField,
        String mappedBy,
        String targetField,
        boolean required,
        boolean composition) {

    public ResolvedRelation {
        targetField = targetField == null || targetField.isBlank() ? "id" : targetField;
    }
}
