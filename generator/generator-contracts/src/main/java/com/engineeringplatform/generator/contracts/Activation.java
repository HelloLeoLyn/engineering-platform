package com.engineeringplatform.generator.contracts;

/**
 * Activation mechanism of a resolved item (004A Contract).
 * Only mechanism, NOT source (source lives in Provenance.source).
 * Aligns with effective-project.schema.yaml activation enum.
 */
public enum Activation {
    EXPLICIT("explicit"),
    DEFAULT("default"),
    REQUIRED("required"),
    OPTIONAL_TRIGGERED("optional-triggered");

    private final String code;

    Activation(String code) {
        this.code = code;
    }

    /** Schema-compatible lowercase code. */
    public String code() {
        return code;
    }
}
