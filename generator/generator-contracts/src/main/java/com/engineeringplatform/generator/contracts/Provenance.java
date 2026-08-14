package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Provenance of a resolved effective value (004A Contract).
 * Aligns with effective-project.schema.yaml effectiveValue.
 *
 * @param value      resolved value
 * @param source     provenance source (7 enum values)
 * @param sourcePath manifest path + JSON pointer, e.g. "project.yaml:/profiles/quality"
 * @param profile    effective profile name (may be null)
 * @param requiredBy referencing entity ids (may be empty)
 */
public record Provenance(
        Object value,
        ProvenanceSource source,
        String sourcePath,
        String profile,
        List<String> requiredBy) {

    public Provenance {
        requiredBy = requiredBy == null ? List.of() : List.copyOf(requiredBy);
    }

    public static Provenance of(Object value, ProvenanceSource source, String sourcePath) {
        return new Provenance(value, source, sourcePath, null, List.of());
    }

    public static Provenance of(Object value, ProvenanceSource source, String sourcePath, String profile) {
        return new Provenance(value, source, sourcePath, profile, List.of());
    }
}
