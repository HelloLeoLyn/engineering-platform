package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.Provenance;
import com.engineeringplatform.generator.contracts.ProvenanceSource;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolution Foundation Pipeline tests (EP-WORK-004B).
 * Uses in-memory fixtures; never touches the real registry.
 */
class ResolutionFoundationPipelineTest {

    // ---- fixtures ----

    private static final Map<String, Object> PLATFORM = Map.of(
            "platform", Map.of("id", "engineering-platform", "version", "0.1.0"),
            "technology", Map.of("java", "25", "node", "24"),
            "profiles", Map.of(
                    "presets", Map.of(
                            "lite", Map.of(
                                    "application", "lite", "infrastructure", "lite",
                                    "security", "standard", "quality", "Q1"),
                            "standard", Map.of(
                                    "application", "standard", "infrastructure", "standard",
                                    "security", "standard", "quality", "Q2")),
                    "default", Map.of("application", "lite", "infrastructure", "lite",
                            "security", "standard", "quality", "Q1")));

    private static final Map<String, Object> PROJECT_MINIMAL = Map.of(
            "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
            "platform", Map.of("id", "engineering-platform"),
            "modules", List.of());

    private static final Map<String, Object> PROJECT_WITH_REFS = Map.of(
            "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
            "platform", Map.of("id", "engineering-platform"),
            "modules", List.of(Map.of("id", "sample-customer")),
            "capabilities", List.of(Map.of("id", "persistence")),
            "providers", List.of(Map.of("id", "mybatis-plus")));

    private static final Map<String, Set<String>> REGISTRY = Map.of(
            "modules", Set.of("sample-customer"),
            "capabilities", Set.of("persistence"),
            "providers", Set.of("mybatis-plus"));

    private static final ManifestValidationPort ALWAYS_VALID = new ManifestValidationPort() {
        @Override public boolean isValid(String manifestType, Map<String, Object> manifest) { return true; }
        @Override public List<String> validationErrors(String manifestType, Map<String, Object> manifest) { return List.of(); }
    };

    private static ResolutionPipeline pipeline() {
        return ResolutionPipeline.withDefaults(ALWAYS_VALID);
    }

    // ---- 1. minimal valid input ----

    @Test
    void minimalValidInputProducesState() {
        IntermediateResolutionState state = pipeline().resolve(
                ResolverInput.minimal(PLATFORM, PROJECT_MINIMAL));
        assertThat(state).isNotNull();
        assertThat(state.hasErrors()).isFalse();
        assertThat(state.resolvedValues()).containsKey("technology.java");
    }

    // ---- 2. known reference ----

    @Test
    void knownReferenceIsResolved() {
        IntermediateResolutionState state = pipeline().resolve(
                new ResolverInput(PLATFORM, PROJECT_WITH_REFS, Map.of(), Map.of(), REGISTRY));
        assertThat(state.resolvedReferences())
                .anyMatch(r -> r.referenceType().equals("modules")
                        && r.referenceId().equals("sample-customer") && r.resolved());
        assertThat(state.hasErrors()).isFalse();
    }

    // ---- 3. unknown reference ----

    @Test
    void unknownReferenceRaisesError() {
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "modules", List.of(Map.of("id", "ghost-module")));
        IntermediateResolutionState state = pipeline().resolve(
                new ResolverInput(PLATFORM, project, Map.of(), Map.of(), REGISTRY));
        assertThat(state.hasErrors()).isTrue();
        assertThat(state.errors()).anyMatch(e -> e.code().equals("UNKNOWN_REFERENCE"));
        assertThat(state.resolvedReferences())
                .anyMatch(r -> r.referenceId().equals("ghost-module") && !r.resolved());
    }

    // ---- 4. profile expansion ----

    @Test
    void profileExpansionAppliesDefaults() {
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "standard"),
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(PLATFORM, project));
        assertThat(state.activeProfiles()).contains("standard");
        assertThat(state.resolvedValues()).containsEntry("profiles.quality", "Q2");
        Provenance p = state.provenance().get("profiles.quality");
        assertThat(p.source()).isEqualTo(ProvenanceSource.PROFILE_DEFAULT);
        assertThat(p.profile()).isEqualTo("standard");
    }

    // ---- 5. unknown profile ----

    @Test
    void unknownProfileRaisesError() {
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "ultra"),
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(PLATFORM, project));
        assertThat(state.errors()).anyMatch(e -> e.code().equals("UNKNOWN_PROFILE"));
    }

    // ---- 6. platform default ----

    @Test
    void platformDefaultAppliedWhenNoProfileValue() {
        IntermediateResolutionState state = pipeline().resolve(
                ResolverInput.minimal(PLATFORM, PROJECT_MINIMAL));
        assertThat(state.resolvedValues()).containsEntry("technology.java", "25");
        Provenance p = state.provenance().get("technology.java");
        assertThat(p.source()).isEqualTo(ProvenanceSource.PLATFORM_DEFAULT);
        assertThat(p.sourcePath()).contains("platform.yaml:/technology/java");
    }

    // ---- 7. profile > platform default ----

    @Test
    void profileOverridesPlatformDefault() {
        // platform default profiles.default.quality = Q1; standard preset = Q2
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "standard"),
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(PLATFORM, project));
        assertThat(state.resolvedValues()).containsEntry("profiles.quality", "Q2");
        assertThat(state.provenance().get("profiles.quality").source())
                .isEqualTo(ProvenanceSource.PROFILE_DEFAULT);
    }

    // ---- 8. project > profile ----

    @Test
    void projectOverridesProfile() {
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "standard", "quality", "Q3"),
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(PLATFORM, project));
        assertThat(state.resolvedValues()).containsEntry("profiles.quality", "Q3");
        assertThat(state.provenance().get("profiles.quality").source())
                .isEqualTo(ProvenanceSource.PROJECT);
    }

    // ---- 9. customer constraint > project ----

    @Test
    void customerConstraintOverridesProject() {
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "standard", "quality", "Q3"),
                "customerConstraints", List.of(Map.of(
                        "name", "profiles.quality", "value", "Q1", "reason", "customer cap")),
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(PLATFORM, project));
        assertThat(state.resolvedValues()).containsEntry("profiles.quality", "Q1");
        assertThat(state.provenance().get("profiles.quality").source())
                .isEqualTo(ProvenanceSource.CUSTOMER_CONSTRAINT);
    }

    // ---- 10. platform guardrail > all ----

    @Test
    void platformGuardrailWinsOverAll() {
        Map<String, Object> platform = withGuardrail("profiles.quality", "Q1");
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "standard", "quality", "Q3"),
                "customerConstraints", List.of(Map.of(
                        "name", "profiles.quality", "value", "Q2", "reason", "customer cap")),
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(platform, project));
        // guardrail forces Q1 despite project Q3 and customer Q2
        assertThat(state.resolvedValues()).containsEntry("profiles.quality", "Q1");
        assertThat(state.provenance().get("profiles.quality").source())
                .isEqualTo(ProvenanceSource.PLATFORM_GUARDRAIL);
    }

    // ---- 11. constraint violation ----

    @Test
    void conflictingLowerLayerRaisesConstraintViolation() {
        Map<String, Object> platform = withGuardrail("profiles.quality", "Q1");
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "standard"), // Q2 conflicts with guardrail Q1
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(platform, project));
        assertThat(state.errors()).anyMatch(e -> e.code().equals("CONSTRAINT_VIOLATION"));
        // guardrail still wins in the final value
        assertThat(state.resolvedValues()).containsEntry("profiles.quality", "Q1");
    }

    // ---- 12. deterministic result ----

    @Test
    void sameInputProducesSameOutput() {
        IntermediateResolutionState a = pipeline().resolve(ResolverInput.minimal(PLATFORM, PROJECT_MINIMAL));
        IntermediateResolutionState b = pipeline().resolve(ResolverInput.minimal(PLATFORM, PROJECT_MINIMAL));
        assertThat(a.resolvedValues()).isEqualTo(b.resolvedValues());
        assertThat(a.provenance()).isEqualTo(b.provenance());
        assertThat(a.warnings()).isEqualTo(b.warnings());
        assertThat(a.errors()).isEqualTo(b.errors());
    }

    // ---- 13. source input unchanged ----

    @Test
    void sourceInputsAreNeverMutated() {
        Map<String, Object> platform = new java.util.HashMap<>(PLATFORM);
        Map<String, Object> project = new java.util.HashMap<>(PROJECT_WITH_REFS);
        Map<String, Object> platformBefore = new java.util.HashMap<>(platform);
        Map<String, Object> projectBefore = new java.util.HashMap<>(project);
        pipeline().resolve(new ResolverInput(platform, project, Map.of(), Map.of(), REGISTRY));
        assertThat(platform).isEqualTo(platformBefore);
        assertThat(project).isEqualTo(projectBefore);
    }

    // ---- 14. provenance correctness ----

    @Test
    void provenanceSourcesAreDistinct() {
        Map<String, Object> project = Map.of(
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "profiles", Map.of("default", "standard"),
                "technology", Map.of("java", "21"),
                "modules", List.of());
        IntermediateResolutionState state = pipeline().resolve(ResolverInput.minimal(PLATFORM, project));
        // technology.java overridden by project
        assertThat(state.provenance().get("technology.java").source())
                .isEqualTo(ProvenanceSource.PROJECT);
        // profiles.quality from profile default
        assertThat(state.provenance().get("profiles.quality").source())
                .isEqualTo(ProvenanceSource.PROFILE_DEFAULT);
        // technology.node untouched platform default
        assertThat(state.provenance().get("technology.node").source())
                .isEqualTo(ProvenanceSource.PLATFORM_DEFAULT);
    }

    private static Map<String, Object> withGuardrail(String key, String value) {
        Map<String, Object> platform = new java.util.HashMap<>(PLATFORM);
        platform.put("governance", Map.of(
                "guardrails", List.of(Map.of("key", key, "value", value))));
        return platform;
    }
}
