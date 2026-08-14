package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolverInput;
import com.engineeringplatform.generator.contracts.SnapshotMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolution Snapshot Metadata factory (EP-WORK-004C+D, Fix Round 1).
 *
 * Semantics (Fix Round 1):
 *  - inputHash: SHA-256 of canonicalized declarative inputs (manifests + registry).
 *    Same canonical input -> same hash. No timestamps, machine paths, runtime
 *    info, or OpenClaw info.
 *  - resolutionId: Resolution Snapshot identity. Deterministic:
 *    resolutionId = "res-" + sha256(resolverVersion + ":" + inputHash)[0..12]
 *    Same resolverVersion + inputHash -> same resolutionId;
 *    different resolverVersion -> different resolutionId.
 *  - resolverVersion: explicit code constant by default; package-private
 *    constructor allows tests to supply a different version (no DI framework).
 *
 * No database involved. 004A Schema Contract unchanged.
 */
public final class SnapshotFactory {

    /** Default resolver version constant (implementation choice). */
    public static final String RESOLVER_VERSION = "0.1.0";

    private final String resolverVersion;

    public SnapshotFactory() {
        this(RESOLVER_VERSION);
    }

    /** Package-private / test-friendly: allows a different resolverVersion. */
    SnapshotFactory(String resolverVersion) {
        this.resolverVersion = resolverVersion;
    }

    /**
     * Builds snapshot metadata from declarative inputs.
     */
    public SnapshotMetadata create(ResolverInput input, IntermediateResolutionState state) {
        String canonicalInput = canonicalInput(input);
        String inputHash = sha256(canonicalInput);
        // resolutionId is bound to BOTH resolverVersion and inputHash (deterministic).
        String resolutionId = "res-" + sha256(resolverVersion + ":" + inputHash).substring(0, 12);
        return new SnapshotMetadata(resolutionId, resolverVersion, inputHash);
    }

    /** Canonical string of the full declarative input set. */
    private static String canonicalInput(ResolverInput input) {
        Map<String, Object> root = new TreeMap<>();
        root.put("platform", input.platformManifest());
        root.put("project", input.projectManifest());
        root.put("modules", new TreeMap<>(input.moduleManifests()));
        root.put("providers", new TreeMap<>(input.providerManifests()));
        root.put("registry", new TreeMap<>(input.registrySnapshot()));
        return Canonicalizer.canonicalString(root);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
