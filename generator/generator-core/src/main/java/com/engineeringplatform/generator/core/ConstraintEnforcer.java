package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.Provenance;
import com.engineeringplatform.generator.contracts.ProvenanceSource;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;

/**
 * Step 6 — Constraint Enforcement (EP-WORK-004B).
 *
 * Applies Customer Constraints, then Platform Guardrails on top.
 * Full precedence:
 *   Platform Default &lt; Profile Default &lt; Project Preference
 *   &lt; Customer Constraint &lt; Platform Guardrail
 *
 * - Customer Constraint wins over project preference.
 * - Platform Guardrail is the highest boundary; it can never be overridden
 *   by any lower layer. Conflict -> CONSTRAINT_VIOLATION.
 * - No Guardrail DSL: only structures the existing manifests can express.
 */
public final class ConstraintEnforcer {

    /**
     * Enforces constraints and guardrails over the current resolved values.
     */
    public void enforce(ResolverInput input, IntermediateResolutionState.Builder state) {
        Map<String, Object> project = input.projectManifest();

        // ---- Customer Constraints ----
        if (project.get("customerConstraints") instanceof List<?> constraints) {
            for (Object item : constraints) {
                if (!(item instanceof Map<?, ?> constraint)) {
                    continue;
                }
                Object name = constraint.get("name");
                Object value = constraint.get("value");
                if (name == null || value == null) {
                    continue;
                }
                // constraint targets a resolved key: e.g. name "profiles.quality"
                String key = String.valueOf(name);
                if (state.containsValue(key)) {
                    state.value(key, value);
                    state.provenance(key, Provenance.of(
                            value, ProvenanceSource.CUSTOMER_CONSTRAINT,
                            "project.yaml:/customerConstraints/" + name, null));
                }
            }
        }

        // ---- Platform Guardrails ----
        Map<String, Object> platform = input.platformManifest();
        if (platform.get("governance") instanceof Map<?, ?> governance
                && governance.get("guardrails") instanceof List<?> guardrails) {
            for (Object item : guardrails) {
                if (!(item instanceof Map<?, ?> guardrail)) {
                    continue;
                }
                Object keyObj = guardrail.get("key");
                Object expected = guardrail.get("value");
                if (keyObj == null || expected == null) {
                    continue;
                }
                String key = String.valueOf(keyObj);
                Object current = state.containsValue(key) ? state.value(key) : null;
                // If a lower layer set a conflicting value, it is a violation.
                if (current != null && !java.util.Objects.equals(current, expected)) {
                    state.error(ResolutionError.constraintViolation(
                            "Platform guardrail violated for key '" + key + "': "
                                    + "lower-layer value " + current + " conflicts with guardrail " + expected,
                            "platform.yaml:/governance/guardrails/" + key));
                }
                // Guardrail enforces an exact expected value (highest boundary).
                state.value(key, expected);
                state.provenance(key, Provenance.of(
                        expected, ProvenanceSource.PLATFORM_GUARDRAIL,
                        "platform.yaml:/governance/guardrails/" + key, null));
            }
        }
    }
}
