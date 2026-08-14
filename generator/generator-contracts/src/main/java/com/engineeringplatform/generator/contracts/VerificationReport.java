package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * VerificationReport（V0.7 §14 Verification Engine 是正式测试结论唯一来源 /
 * §22 VerificationReport 是核心 Artifact）。
 *
 * 汇总 WorkItem + Plan/Tasks + GenerationPlan/ChangeManifest + TestPlan/TestRun + Evidence
 * 为验收判断。decision: ACCEPT / REJECT / BLOCKED。
 *
 * Agent Self-Acceptance Boundary（硬规则）：
 * 执行 Agent 可报告 DONE/FAILED/BLOCKED + Evidence，但不得直接设置 WorkItem=ACCEPTED；
 * ACCEPTED 只能来自 Verification result + 必要时 Human Approval。
 */
public record VerificationReport(
        String verificationId,
        String workItemId,
        String engineeringPlanId,
        String implementationTasksId,
        String generationPlanId,
        String changeManifestId,
        String testPlanId,
        String testRunId,
        List<CriterionResult> criteriaResults,
        WorkItem.ScopeResult scopeResult,
        TestSummary testResult,
        Map<String, Object> artifactResult,
        List<String> findings,
        Decision decision,
        List<Evidence> evidence) {

    public VerificationReport {
        criteriaResults = criteriaResults == null ? List.of() : List.copyOf(criteriaResults);
        findings = findings == null ? List.of() : List.copyOf(findings);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        artifactResult = artifactResult == null ? Map.of() : Map.copyOf(artifactResult);
    }

    public enum Decision {
        ACCEPT, REJECT, BLOCKED
    }

    public enum CriterionStatus {
        PASS, FAIL, BLOCKED, PENDING_MANUAL
    }

    public record CriterionResult(
            String criterionId,
            CriterionStatus status,
            List<String> evidence,
            String message) {

        public CriterionResult {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record TestSummary(int passed, int failed, int skipped, int blocked) {
    }
}
