package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * V06-WORK-001 — Business Entity Field.
 *
 * Structured field definition from the Generic Business Module Contract.
 *
 * @param name         field name (camelCase)
 * @param type         field type (string/number/integer/decimal/boolean/date/datetime/text
 *                     + V0.7: money/enum/status/reference/image/file/richtext)
 * @param required     not null constraint
 * @param unique       unique constraint
 * @param length       string length (nullable)
 * @param precision    decimal precision (nullable)
 * @param scale        decimal scale (nullable)
 * @param defaultValue default value (nullable)
 * @param primaryKey   primary key flag
 * @param comment      field comment
 * @param semantic     semantic mapping (none/dictionary/department/ownership/currentUser/system
 *                     + V0.7: reference/enum)
 * @param dictionary   dictionary type key when semantic=dictionary (nullable)
 * @param frontend     field-level frontend metadata (label/listVisible/searchable/formVisible/detailVisible/component/placeholder/sortable/order)
 * @param reference    V0.7: structured reference config when semantic=reference
 *                     (target/valueField/labelField/searchFields; transport decided by Generator,
 *                     never an HTTP endpoint in the Contract)
 * @param enumValues   V0.7: structured enum candidates when semantic=enum or type=enum
 *                     (list of {value, label})
 */
public record BusinessEntityField(
        String name,
        String type,
        boolean required,
        boolean unique,
        Integer length,
        Integer precision,
        Integer scale,
        Object defaultValue,
        boolean primaryKey,
        String comment,
        String semantic,
        String dictionary,
        Map<String, Object> frontend,
        Map<String, Object> reference,
        List<Map<String, Object>> enumValues) {

    public BusinessEntityField {
        frontend = frontend == null ? Map.of() : Map.copyOf(frontend);
        reference = reference == null ? Map.of() : Map.copyOf(reference);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    /**
     * V0.6-compatible constructor (13 args, no V0.7 fields). Kept so existing
     * callers outside the resolver (and any legacy construction path) still
     * compile unchanged; V0.7 fields default to empty.
     */
    public BusinessEntityField(
            String name,
            String type,
            boolean required,
            boolean unique,
            Integer length,
            Integer precision,
            Integer scale,
            Object defaultValue,
            boolean primaryKey,
            String comment,
            String semantic,
            String dictionary,
            Map<String, Object> frontend) {
        this(name, type, required, unique, length, precision, scale, defaultValue,
                primaryKey, comment, semantic, dictionary, frontend, Map.of(), List.of());
    }
}
