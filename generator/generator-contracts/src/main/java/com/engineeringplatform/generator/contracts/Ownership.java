package com.engineeringplatform.generator.contracts;

/**
 * Generator Ownership 四级（V0.7 §13 Generator Ownership Contract [DECIDED] / AGENTS.md）。
 *
 * GENERATED  — generator fully owns the file; safe to regenerate (manifest-proven).
 * MANAGED    — controlled structured updates only.
 * USER_OWNED — initial skeleton only; never overwrite user implementation.
 * IMMUTABLE  — released assets / applied migrations must not be modified or deleted.
 */
public enum Ownership {
    GENERATED,
    MANAGED,
    USER_OWNED,
    IMMUTABLE
}
