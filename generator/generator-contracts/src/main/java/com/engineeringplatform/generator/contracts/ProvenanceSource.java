package com.engineeringplatform.generator.contracts;

/**
 * Provenance source of a resolved value (V0.7 §19, 004A Contract).
 * Aligns with effective-project.schema.yaml effectiveValue.source enum.
 */
public enum ProvenanceSource {
    PLATFORM_DEFAULT,
    PROFILE_DEFAULT,
    PROJECT,
    CUSTOMER_CONSTRAINT,
    PLATFORM_GUARDRAIL,
    DEPENDENCY,
    REQUIREMENT
}
