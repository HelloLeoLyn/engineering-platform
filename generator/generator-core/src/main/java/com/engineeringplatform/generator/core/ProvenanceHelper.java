package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.Provenance;

import java.util.Optional;

/** Small helper for provenance lookups across steps. */
final class ProvenanceHelper {

    private ProvenanceHelper() {
    }

    /** Returns the quality from provenance when its source is profile-default. */
    static Optional<String> profileQuality(IntermediateResolutionState.Builder state) {
        Provenance p = state.provenance("profiles.quality");
        if (p != null && p.source().name().equals("PROFILE_DEFAULT") && p.value() != null) {
            return Optional.of(String.valueOf(p.value()));
        }
        return Optional.empty();
    }
}
