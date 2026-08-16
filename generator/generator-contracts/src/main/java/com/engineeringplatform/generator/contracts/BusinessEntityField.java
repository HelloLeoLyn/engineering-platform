package com.engineeringplatform.generator.contracts;

import java.util.Map;

/**
 * V06-WORK-001 — Business Entity Field.
 *
 * Structured field definition from the Generic Business Module Contract.
 *
 * @param name         field name (camelCase)
 * @param type         field type (string/number/integer/decimal/boolean/date/datetime/text)
 * @param required     not null constraint
 * @param unique       unique constraint
 * @param length       string length (nullable)
 * @param precision    decimal precision (nullable)
 * @param scale        decimal scale (nullable)
 * @param defaultValue default value (nullable)
 * @param primaryKey   primary key flag
 * @param comment      field comment
 * @param semantic     semantic mapping (none/dictionary/department/ownership/currentUser/system)
 * @param dictionary   dictionary type key when semantic=dictionary (nullable)
 * @param frontend     field-level frontend metadata (label/listVisible/searchVisible/formVisible/detailVisible/component/order)
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
        Map<String, Object> frontend) {

    public BusinessEntityField {
        frontend = frontend == null ? Map.of() : Map.copyOf(frontend);
    }
}
