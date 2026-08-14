package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * TestRun（V0.7 §14 Execution Planner 决定 HOW / §22 TestRun 是核心 Artifact）。
 * 执行事实（非声明式）。SKIPPED 不得自动等于 PASS。
 */
public record TestRun(
        String testRunId,
        String testPlanId,
        String startedAt,
        String completedAt,
        String environment,
        List<TestCaseResult> results,
        Summary summary,
        List<String> evidence) {

    public TestRun {
        results = results == null ? List.of() : List.copyOf(results);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public enum TestCaseStatus {
        PASS, FAIL, SKIPPED, BLOCKED
    }

    public record TestCaseResult(
            String testCaseId,
            TestCaseStatus status,
            String message,
            List<String> evidence) {

        public TestCaseResult {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record Summary(int total, int passed, int failed, int skipped, int blocked) {
    }
}
