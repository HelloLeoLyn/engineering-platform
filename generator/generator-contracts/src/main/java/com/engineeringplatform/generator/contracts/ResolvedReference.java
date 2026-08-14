package com.engineeringplatform.generator.contracts;

/**
 * Result of a single reference existence check (004B).
 *
 * @param referenceType module / capability / provider / event / ... (registry type)
 * @param referenceId   referenced id
 * @param resolved      true when the target exists in the registry snapshot
 * @param sourcePath    where the reference was declared (manifest path + pointer)
 */
public record ResolvedReference(
        String referenceType,
        String referenceId,
        boolean resolved,
        String sourcePath) {
}
