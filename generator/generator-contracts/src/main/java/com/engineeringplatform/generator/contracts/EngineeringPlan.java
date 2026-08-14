package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * EngineeringPlan（V0.7 §14 / §22 EngineeringPlan 是核心 Artifact）。
 * WorkItem → Implementation strategy（怎么完成工程任务）。
 * 与 GenerationPlan（具体文件变更计划）区分。
 */
public record EngineeringPlan(
        String planId,
        String workItemId,
        List<Step> steps,
        List<String> dependencies,
        List<String> expectedChanges,
        String testStrategy,
        List<String> risks,
        String rollbackConsideration) {

    public EngineeringPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        expectedChanges = expectedChanges == null ? List.of() : List.copyOf(expectedChanges);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }

    public record Step(String stepId, String objective, List<String> dependencies, List<String> expectedArtifacts) {
        public Step {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            expectedArtifacts = expectedArtifacts == null ? List.of() : List.copyOf(expectedArtifacts);
        }
    }
}
