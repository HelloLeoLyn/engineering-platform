package com.engineeringplatform.generator.contracts;

/**
 * Resolution snapshot metadata (004A Contract §resolution).
 * Semantics only; algorithms are implementation choices in generator-core.
 *
 * @param resolutionId   identifies one resolution snapshot (stable, testable)
 * @param resolverVersion resolver version constant
 * @param inputHash      deterministic fingerprint of declarative inputs
 */
public record SnapshotMetadata(
        String resolutionId,
        String resolverVersion,
        String inputHash) {
}
