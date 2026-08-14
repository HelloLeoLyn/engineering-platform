package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * A resolved capability in the final model (004A effective-project resolvedCapability).
 *
 * @param id         capability id (dotted)
 * @param activation activation mechanism
 * @param reason     human readable explanation
 * @param requiredBy entity ids that require this capability
 * @param provider   resolved provider id bound to this capability slot (may be null)
 */
public record ResolvedCapability(
        String id,
        Activation activation,
        String reason,
        List<String> requiredBy,
        String provider) {

    public ResolvedCapability {
        requiredBy = requiredBy == null ? List.of() : List.copyOf(requiredBy);
    }
}
