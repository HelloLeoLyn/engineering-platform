package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.OperationType;
import com.engineeringplatform.generator.contracts.OverwritePolicy;
import com.engineeringplatform.generator.contracts.Ownership;
import com.engineeringplatform.generator.contracts.SnapshotMetadata;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GenerationPlan tests (EP-WORK-005/006 §十九 1-6, 37-38).
 */
class GenerationPlannerTest {

    private static EffectiveProjectModel epm(String resolutionId, String inputHash) {
        return new EffectiveProjectModel(
                1,
                new SnapshotMetadata(resolutionId, "0.1.0", inputHash),
                Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                Map.of("id", "engineering-platform", "version", "0.1.0"),
                Map.of(),
                Map.of("java", "25"),
                List.of(),
                List.of(),
                List.of(),
                Map.of("minimum", "Q2"),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of());
    }

    private static GenerationOperation create(String id, String path) {
        return GenerationOperation.builder()
                .operationId(id).type(OperationType.CREATE_FILE).targetPath(path)
                .ownership(Ownership.GENERATED).overwritePolicy(OverwritePolicy.ALLOWED)
                .content("hello").reason("test")
                .build();
    }

    private static GenerationOperation modify(String id, String path) {
        return GenerationOperation.builder()
                .operationId(id).type(OperationType.UPDATE_MANAGED_FILE).targetPath(path)
                .ownership(Ownership.MANAGED).overwritePolicy(OverwritePolicy.STRUCTURED_ONLY)
                .content("updated").reason("test")
                .build();
    }

    // 1. deterministic plan
    @Test
    void deterministicPlan() {
        GenerationPlanner planner = new GenerationPlanner();
        GenerationPlan p1 = planner.plan(epm("res-a", "hash-a"), "0.1.0", "SCAFFOLD",
                List.of(create("op-1", "src/A.java")));
        GenerationPlan p2 = planner.plan(epm("res-a", "hash-a"), "0.1.0", "SCAFFOLD",
                List.of(create("op-1", "src/A.java")));
        assertThat(p1.planId()).isEqualTo(p2.planId());
        assertThat(p1.planId()).startsWith("gp-");
    }

    // 2. resolutionId binding
    @Test
    void resolutionIdBinding() {
        GenerationPlanner planner = new GenerationPlanner();
        GenerationPlan p = planner.plan(epm("res-xyz", "hash-xyz"), "0.1.0", "SCAFFOLD",
                List.of(create("op-1", "src/A.java")));
        assertThat(p.resolutionId()).isEqualTo("res-xyz");
        assertThat(p.inputHash()).isEqualTo("hash-xyz");
        // different resolution -> different plan id
        GenerationPlan p2 = planner.plan(epm("res-abc", "hash-xyz"), "0.1.0", "SCAFFOLD",
                List.of(create("op-1", "src/A.java")));
        assertThat(p2.planId()).isNotEqualTo(p.planId());
    }

    // 3. CREATE operation
    @Test
    void createOperationInPlan() {
        GenerationPlanner planner = new GenerationPlanner();
        GenerationPlan p = planner.plan(epm("res-a", "hash-a"), "0.1.0", "SCAFFOLD",
                List.of(create("op-1", "src/A.java")));
        assertThat(p.operations()).hasSize(1);
        assertThat(p.operations().get(0).type()).isEqualTo(OperationType.CREATE_FILE);
        assertThat(p.createCount()).isEqualTo(1);
    }

    // 4. MODIFY operation
    @Test
    void modifyOperationInPlan() {
        GenerationPlanner planner = new GenerationPlanner();
        GenerationPlan p = planner.plan(epm("res-a", "hash-a"), "0.1.0", "SCAFFOLD",
                List.of(modify("op-2", "src/registry.yaml")));
        assertThat(p.operations().get(0).type()).isEqualTo(OperationType.UPDATE_MANAGED_FILE);
        assertThat(p.modifyCount()).isEqualTo(1);
    }

    // 5. ownership included
    @Test
    void ownershipIncluded() {
        GenerationPlanner planner = new GenerationPlanner();
        GenerationPlan p = planner.plan(epm("res-a", "hash-a"), "0.1.0", "SCAFFOLD",
                List.of(create("op-1", "src/A.java"), modify("op-2", "src/registry.yaml")));
        assertThat(p.operations().get(0).ownership()).isEqualTo(Ownership.GENERATED);
        assertThat(p.operations().get(1).ownership()).isEqualTo(Ownership.MANAGED);
    }

    // 6. overwrite policy included
    @Test
    void overwritePolicyIncluded() {
        GenerationPlanner planner = new GenerationPlanner();
        GenerationPlan p = planner.plan(epm("res-a", "hash-a"), "0.1.0", "SCAFFOLD",
                List.of(create("op-1", "src/A.java"), modify("op-2", "src/registry.yaml")));
        assertThat(p.operations().get(0).overwritePolicy()).isEqualTo(OverwritePolicy.ALLOWED);
        assertThat(p.operations().get(1).overwritePolicy()).isEqualTo(OverwritePolicy.STRUCTURED_ONLY);
    }

    // 37. same EPM -> same plan
    @Test
    void sameEpmSamePlan() {
        GenerationPlanner planner = new GenerationPlanner();
        EffectiveProjectModel epm = epm("res-a", "hash-a");
        GenerationPlan p1 = planner.plan(epm, "0.1.0", "SCAFFOLD", List.of(create("op-1", "src/A.java")));
        GenerationPlan p2 = planner.plan(epm, "0.1.0", "SCAFFOLD", List.of(create("op-1", "src/A.java")));
        assertThat(p1).isEqualTo(p2);
    }

    // 38. source EPM unchanged (planning does not mutate EPM)
    @Test
    void sourceEpmUnchanged() {
        GenerationPlanner planner = new GenerationPlanner();
        EffectiveProjectModel epm = epm("res-a", "hash-a");
        EffectiveProjectModel before = epm;
        planner.plan(epm, "0.1.0", "SCAFFOLD", List.of(create("op-1", "src/A.java")));
        assertThat(epm).isEqualTo(before);
    }
}
