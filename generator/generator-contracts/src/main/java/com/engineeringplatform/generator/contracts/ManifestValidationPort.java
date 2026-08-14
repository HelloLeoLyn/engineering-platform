package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * Schema Validation Boundary (004B).
 *
 * Resolver depends on the RESULT that inputs are structurally valid;
 * it does NOT embed a JSON Schema engine. Implementations may be:
 *   - a Java JSON Schema validator (future, after EP-WORK-002 decision)
 *   - a test stub/fake (current phase)
 *
 * This interface intentionally does NOT re-open EP-WORK-002.
 */
public interface ManifestValidationPort {

    /**
     * Validates one manifest against its schema contract.
     *
     * @param manifestType platform / project / module / provider
     * @param manifest     parsed manifest as Map
     * @return true when the manifest is structurally valid
     */
    boolean isValid(String manifestType, Map<String, Object> manifest);

    /**
     * Human-readable validation errors (empty when valid).
     */
    List<String> validationErrors(String manifestType, Map<String, Object> manifest);
}
