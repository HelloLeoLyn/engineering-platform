package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * WorkItem（V0.7 §14 AI Engineering Contract / §23 WorkItem Schema + State Machine V1 [DECIDED]）。
 * 一次工程工作的身份与边界；Artifact 记录进入状态的依据。
 *
 * 状态机（V0.7 §23）：NEW → ANALYZING → PLANNED → APPROVED → IMPLEMENTING →
 * IMPLEMENTED → VERIFYING → READY → DONE；异常 BLOCKED/FAILED/REJECTED/CANCELLED/REOPENED；
 * 验收终态 ACCEPTED（只能来自 Verification + Human Approval，Agent 不得直接设置）。
 */
public record WorkItem(
        String workItemId,
        String title,
        String objective,
        WorkItemType type,
        WorkItemStatus status,
        ScopeContract scope,
        List<AcceptanceCriterion> acceptanceCriteria,
        List<String> allowedChanges,
        List<String> forbiddenChanges,
        List<String> inputs,
        List<String> outputs,
        List<String> risks,
        List<String> dependencies,
        List<String> evidenceRequirements) {

    public WorkItem {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        allowedChanges = allowedChanges == null ? List.of() : List.copyOf(allowedChanges);
        forbiddenChanges = forbiddenChanges == null ? List.of() : List.copyOf(forbiddenChanges);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        risks = risks == null ? List.of() : List.copyOf(risks);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        evidenceRequirements = evidenceRequirements == null ? List.of() : List.copyOf(evidenceRequirements);
    }

    public enum WorkItemType {
        REQUIREMENT, BUG, REFACTOR, MIGRATION, UPGRADE, OPERATIONS, SECURITY, TECH_DEBT
    }

    public enum WorkItemStatus {
        NEW, ANALYZING, PLANNED, APPROVED, IMPLEMENTING, IMPLEMENTED,
        VERIFYING, READY, DONE, ACCEPTED, REJECTED, BLOCKED, FAILED,
        CANCELLED, REOPENED
    }

    /**
     * Acceptance Criterion（稳定 identity + 最小类型；不建立复杂规则语言）。
     */
    public record AcceptanceCriterion(
            String criterionId,
            String description,
            CriterionType type,
            boolean required,
            List<String> evidenceRequirements) {

        public AcceptanceCriterion {
            evidenceRequirements = evidenceRequirements == null ? List.of() : List.copyOf(evidenceRequirements);
        }

        public enum CriterionType {
            BUILD, TEST, FILE_SCOPE, ARTIFACT, MANUAL, CUSTOM
        }
    }

    /**
     * 机器可读 Scope Contract（allowed/forbidden paths + operation types）。
     * 自然语言 description 可保留，但 Guard 消费结构化字段。
     */
    public record ScopeContract(
            String description,
            List<String> allowedPaths,
            List<String> forbiddenPaths,
            List<String> allowedModules,
            List<String> forbiddenModules,
            List<String> allowedOperationTypes,
            List<String> forbiddenOperationTypes) {

        public ScopeContract {
            allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
            forbiddenPaths = forbiddenPaths == null ? List.of() : List.copyOf(forbiddenPaths);
            allowedModules = allowedModules == null ? List.of() : List.copyOf(allowedModules);
            forbiddenModules = forbiddenModules == null ? List.of() : List.copyOf(forbiddenModules);
            allowedOperationTypes = allowedOperationTypes == null ? List.of() : List.copyOf(allowedOperationTypes);
            forbiddenOperationTypes = forbiddenOperationTypes == null ? List.of() : List.copyOf(forbiddenOperationTypes);
        }

        public static ScopeContract any() {
            return new ScopeContract(null, List.of("**"), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * Scope 验证结果（ScopeVerifier 输出）。
     */
    public record ScopeResult(boolean pass, List<String> violations) {
        public ScopeResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }
}
