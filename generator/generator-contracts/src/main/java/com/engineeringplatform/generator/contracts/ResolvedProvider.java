package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * A resolved provider in the final model (004A effective-project resolvedProvider).
 *
 * @param id          provider id (kebab-case)
 * @param activation  activation mechanism
 * @param reason      human readable explanation
 * @param requiredBy  entity ids that require this provider
 * @param version     resolved version (may be null)
 * @param implementsList capability slots this provider implements
 */
public record ResolvedProvider(
        String id,
        Activation activation,
        String reason,
        List<String> requiredBy,
        String version,
        List<String> implementsList) {

    public ResolvedProvider {
        requiredBy = requiredBy == null ? List.of() : List.copyOf(requiredBy);
        implementsList = implementsList == null ? List.of() : List.copyOf(implementsList);
    }
}
