package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * TestPlan（V0.7 §14 Test Planning Engine 决定 WHAT / §22 TestPlan 是核心 Artifact）。
 * 声明式计划，绑定 workItemId + engineeringPlanId。不得执行测试。
 */
public record TestPlan(
        String testPlanId,
        String workItemId,
        String engineeringPlanId,
        List<TestCase> testCases) {

    public TestPlan {
        testCases = testCases == null ? List.of() : List.copyOf(testCases);
    }

    public record TestCase(
            String testCaseId,
            TestType type,
            String target,
            String expectedResult,
            boolean required,
            String acceptanceCriterion) {

        public enum TestType {
            UNIT, INTEGRATION, CONTRACT, E2E, COMPATIBILITY, MANUAL
        }
    }
}
