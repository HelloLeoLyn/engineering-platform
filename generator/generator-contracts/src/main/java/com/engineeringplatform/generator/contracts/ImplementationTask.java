package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * ImplementationTask（V0.7 §14 / §22 ImplementationTasks 是核心 Artifact）。
 * 将 EngineeringPlan 拆成可执行 Task；支持 dependency DAG。
 * Task Executor 可报告 DONE/FAILED/BLOCKED + Evidence，但不得直接设置 WorkItem=ACCEPTED。
 */
public record ImplementationTask(
        String taskId,
        String workItemId,
        String planId,
        String objective,
        TaskStatus status,
        List<String> dependencies,
        List<String> expectedArtifacts,
        List<String> acceptanceCriteria,
        List<String> evidence) {

    public ImplementationTask {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        expectedArtifacts = expectedArtifacts == null ? List.of() : List.copyOf(expectedArtifacts);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public enum TaskStatus {
        PLANNED, IN_PROGRESS, DONE, FAILED, BLOCKED
    }

    /**
     * Task DAG 验证结果（unknown dependency + cycle 检测）。
     */
    public record DagResult(boolean valid, List<String> unknownDependencies, List<String> cycles) {
        public DagResult {
            unknownDependencies = unknownDependencies == null ? List.of() : List.copyOf(unknownDependencies);
            cycles = cycles == null ? List.of() : List.copyOf(cycles);
        }
    }
}
