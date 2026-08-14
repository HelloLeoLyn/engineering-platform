package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Evidence;
import com.engineeringplatform.generator.contracts.TestRun;
import com.engineeringplatform.generator.contracts.VerificationReport;
import com.engineeringplatform.generator.contracts.WorkItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verification Engine（V0.7 §14：Verification Engine 是正式测试结论唯一来源；
 * EP-WORK-007/008 指令 §十三/§十四）。
 *
 * 机械验证：BUILD / TEST / FILE_SCOPE / ARTIFACT。
 * MANUAL：只能输出 PENDING_MANUAL，不得自动 PASS。
 *
 * Agent Self-Acceptance Boundary（硬规则）：
 * 本 Engine 是唯一能产生 ACCEPT 决策的机械来源；
 * 执行 Agent 可报告 DONE/FAILED/BLOCKED + Evidence，但不得直接设置 WorkItem=ACCEPTED。
 *
 * 纯计算，无副作用。相同 facts → 相同 decision（deterministic）。
 */
public final class VerificationEngine {

    private VerificationEngine() {
    }

    /**
     * 计算 VerificationReport。
     *
     * @param workItem     目标 WorkItem（提供 acceptanceCriteria + scope）
     * @param scopeResult  ScopeVerifier 结果
     * @param testRun      可选 TestRun（TEST criterion 使用）
     * @param evidenceByCriterion criterionId → evidence 列表
     * @param artifactResults     ARTIFACT criterion：artifactId → 是否存在（存在性 = evidence）
     * @param buildPassed         BUILD criterion：构建是否通过（null = 无 evidence）
     */
    public static VerificationReport evaluate(
            WorkItem workItem,
            WorkItem.ScopeResult scopeResult,
            TestRun testRun,
            Map<String, List<Evidence>> evidenceByCriterion,
            Map<String, Boolean> artifactResults,
            Boolean buildPassed) {

        List<VerificationReport.CriterionResult> criteriaResults = new ArrayList<>();
        List<String> findings = new ArrayList<>();

        if (workItem.acceptanceCriteria() == null || workItem.acceptanceCriteria().isEmpty()) {
            // WorkItem 必须至少有一个验收标准；无标准不得 ACCEPT（schema 层已 minItems:1，这里双重防护）
            findings.add("WorkItem has no acceptance criteria");
            return new VerificationReport(
                    "vr-" + ContentHasher.sha256(workItem.workItemId() + ":empty-criteria").substring(0, 12),
                    workItem.workItemId(),
                    null, null, null, null, null, null,
                    List.of(),
                    scopeResult,
                    new VerificationReport.TestSummary(0, 0, 0, 0),
                    Map.of(),
                    findings,
                    VerificationReport.Decision.BLOCKED,
                    collectEvidence(evidenceByCriterion));
        }

        for (WorkItem.AcceptanceCriterion criterion : workItem.acceptanceCriteria()) {
            criteriaResults.add(evaluateCriterion(criterion, scopeResult, testRun,
                    evidenceByCriterion == null ? Map.of() : evidenceByCriterion,
                    artifactResults == null ? Map.of() : artifactResults,
                    buildPassed, findings));
        }

        // decision 计算（V0.7 §14 + EP-WORK-007/008 指令 §十三）:
        //  - required criterion FAIL → REJECT
        //  - required criterion BLOCKED / required MANUAL PENDING → BLOCKED（不得 ACCEPT）
        //  - 全部 required 机械 PASS → ACCEPT（非 required MANUAL pending 不阻断机械 ACCEPT）
        boolean requiredFail = false;
        boolean requiredBlocked = false;
        for (VerificationReport.CriterionResult r : criteriaResults) {
            WorkItem.AcceptanceCriterion criterion = findCriterion(workItem, r.criterionId());
            if (criterion == null || !criterion.required()) {
                continue;
            }
            if (r.status() == VerificationReport.CriterionStatus.FAIL) {
                requiredFail = true;
            }
            if (r.status() == VerificationReport.CriterionStatus.BLOCKED
                    || r.status() == VerificationReport.CriterionStatus.PENDING_MANUAL) {
                requiredBlocked = true;
            }
        }

        VerificationReport.Decision decision;
        if (requiredFail || (scopeResult != null && !scopeResult.pass())) {
            decision = VerificationReport.Decision.REJECT;
            if (scopeResult != null) {
                findings.addAll(scopeResult.violations());
            }
        } else if (requiredBlocked) {
            decision = VerificationReport.Decision.BLOCKED;
        } else {
            decision = VerificationReport.Decision.ACCEPT;
        }

        TestRun.Summary summary = testRun == null
                ? new TestRun.Summary(0, 0, 0, 0, 0) : testRun.summary();

        return new VerificationReport(
                "vr-" + ContentHasher.sha256(workItem.workItemId()
                        + ":" + criteriaResults + ":" + scopeResult).substring(0, 12),
                workItem.workItemId(),
                null, null, null, null,
                testRun == null ? null : testRun.testPlanId(),
                testRun == null ? null : testRun.testRunId(),
                criteriaResults,
                scopeResult,
                new VerificationReport.TestSummary(
                        summary.passed(), summary.failed(), summary.skipped(), summary.blocked()),
                Map.of(),
                findings,
                decision,
                collectEvidence(evidenceByCriterion));
    }

    private static VerificationReport.CriterionResult evaluateCriterion(
            WorkItem.AcceptanceCriterion criterion,
            WorkItem.ScopeResult scopeResult,
            TestRun testRun,
            Map<String, List<Evidence>> evidenceByCriterion,
            Map<String, Boolean> artifactResults,
            Boolean buildPassed,
            List<String> findings) {

        List<Evidence> evidence = evidenceByCriterion.getOrDefault(criterion.criterionId(), List.of());
        String cid = criterion.criterionId();

        return switch (criterion.type()) {
            case BUILD -> {
                if (buildPassed == null) {
                    findings.add("BUILD criterion " + cid + ": missing evidence");
                    yield new VerificationReport.CriterionResult(cid,
                            VerificationReport.CriterionStatus.BLOCKED, evidenceRefs(evidence),
                            "missing build evidence");
                }
                yield buildPassed
                        ? new VerificationReport.CriterionResult(cid,
                                VerificationReport.CriterionStatus.PASS, evidenceRefs(evidence), null)
                        : new VerificationReport.CriterionResult(cid,
                                VerificationReport.CriterionStatus.FAIL, evidenceRefs(evidence),
                                "build failed");
            }
            case TEST -> {
                if (testRun == null) {
                    findings.add("TEST criterion " + cid + ": missing test run");
                    yield new VerificationReport.CriterionResult(cid,
                            VerificationReport.CriterionStatus.BLOCKED, evidenceRefs(evidence),
                            "missing test run");
                }
                boolean anyFail = testRun.results().stream()
                        .anyMatch(r -> r.status() == TestRun.TestCaseStatus.FAIL);
                boolean anyBlocked = testRun.results().stream()
                        .anyMatch(r -> r.status() == TestRun.TestCaseStatus.BLOCKED);
                boolean anyPass = testRun.results().stream()
                        .anyMatch(r -> r.status() == TestRun.TestCaseStatus.PASS);
                // SKIPPED 不得自动等于 PASS：只有 SKIPPED（无 PASS）的 run 不算通过
                if (anyFail) {
                    yield new VerificationReport.CriterionResult(cid,
                            VerificationReport.CriterionStatus.FAIL, evidenceRefs(evidence),
                            "test failures present");
                } else if (anyBlocked) {
                    yield new VerificationReport.CriterionResult(cid,
                            VerificationReport.CriterionStatus.BLOCKED, evidenceRefs(evidence),
                            "test cases blocked");
                } else if (!anyPass) {
                    // skipped-only 或空结果：不得自动 PASS
                    yield new VerificationReport.CriterionResult(cid,
                            VerificationReport.CriterionStatus.BLOCKED, evidenceRefs(evidence),
                            "no passing test evidence (skipped/empty run)");
                } else {
                    yield new VerificationReport.CriterionResult(cid,
                            VerificationReport.CriterionStatus.PASS, evidenceRefs(evidence), null);
                }
            }
            case FILE_SCOPE -> {
                if (scopeResult == null) {
                    yield new VerificationReport.CriterionResult(cid,
                            VerificationReport.CriterionStatus.BLOCKED, List.of(),
                            "missing scope result");
                }
                yield scopeResult.pass()
                        ? new VerificationReport.CriterionResult(cid,
                                VerificationReport.CriterionStatus.PASS, List.of(), null)
                        : new VerificationReport.CriterionResult(cid,
                                VerificationReport.CriterionStatus.FAIL, List.of(),
                                String.join("; ", scopeResult.violations()));
            }
            case ARTIFACT -> {
                boolean exists = artifactResults.getOrDefault(criterion.criterionId(), false);
                yield exists
                        ? new VerificationReport.CriterionResult(cid,
                                VerificationReport.CriterionStatus.PASS, evidenceRefs(evidence), null)
                        : new VerificationReport.CriterionResult(cid,
                                VerificationReport.CriterionStatus.BLOCKED, evidenceRefs(evidence),
                                "artifact missing");
            }
            case MANUAL -> new VerificationReport.CriterionResult(cid,
                    VerificationReport.CriterionStatus.PENDING_MANUAL, evidenceRefs(evidence),
                    "awaiting human confirmation");
            case CUSTOM -> new VerificationReport.CriterionResult(cid,
                    VerificationReport.CriterionStatus.PENDING_MANUAL, evidenceRefs(evidence),
                    "custom criterion requires manual judgment");
        };
    }

    private static WorkItem.AcceptanceCriterion findCriterion(WorkItem workItem, String criterionId) {
        for (WorkItem.AcceptanceCriterion c : workItem.acceptanceCriteria()) {
            if (c.criterionId().equals(criterionId)) {
                return c;
            }
        }
        return null;
    }

    private static List<String> evidenceRefs(List<Evidence> evidence) {
        return evidence.stream().map(Evidence::evidenceId).toList();
    }

    private static List<Evidence> collectEvidence(Map<String, List<Evidence>> evidenceByCriterion) {
        if (evidenceByCriterion == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<Evidence> all = new ArrayList<>();
        for (List<Evidence> list : evidenceByCriterion.values()) {
            for (Evidence e : list) {
                if (seen.add(e.evidenceId())) {
                    all.add(e);
                }
            }
        }
        return all;
    }
}
