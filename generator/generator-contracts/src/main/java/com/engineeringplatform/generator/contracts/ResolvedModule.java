package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * A resolved module in the final model (004A effective-project resolvedModule).
 *
 * @param id         module id (kebab-case)
 * @param activation activation mechanism (explicit/default/required/optional-triggered)
 * @param reason     human readable explanation
 * @param requiredBy entity ids that require this module
 * @param version    resolved version (may be null)
 */
public record ResolvedModule(
        String id,
        Activation activation,
        String reason,
        List<String> requiredBy,
        String version) {

    public ResolvedModule {
        requiredBy = requiredBy == null ? List.of() : List.copyOf(requiredBy);
    }
}
