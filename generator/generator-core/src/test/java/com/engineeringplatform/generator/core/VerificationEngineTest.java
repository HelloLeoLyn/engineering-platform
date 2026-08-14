package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Evidence;
import com.engineeringplatform.generator.contracts.TestRun;
import com.engineeringplatform.generator.contracts.VerificationReport;
import com.engineeringplatform.generator.contracts.WorkItem;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification Engine tests (EP-WORK-007/008 §十六 11-32): TestRun semantics,
 * scope verification, mechanical criteria, MANUAL pending, decision logic,
 * agent self-acceptance boundary, determinism, input immutability.
 * Pure in-memory fixtures.
 */
class VerificationEngineTest {

    // ---- helpers ----

    private static WorkItem workItem(WorkItem.AcceptanceCriterion... criteria) {
        return new WorkItem(
                "WI-42", "t", "o", WorkItem.WorkItemType.REQUIREMENT,
                WorkItem.WorkItemStatus.VERIFYING,
                new WorkItem.ScopeContract(null, List.of("backend/modules/sample-customer/"),
                        List.of("backend/modules/sample-order/"), List.of(), List.of(),
                        List.of(), List.of()),
                List.of(criteria),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static WorkItem.AcceptanceCriterion criterion(String id, WorkItem.AcceptanceCriterion.CriterionType type,
                                                          boolean required) {
        return new WorkItem.AcceptanceCriterion(id, id + " desc", type, required, List.of());
    }

    private static TestRun run(TestRun.TestCaseResult... results) {
        int pass = 0, fail = 0, skip = 0, block = 0;
        for (TestRun.TestCaseResult r : results) {
            switch (r.status()) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case SKIPPED -> skip++;
                case BLOCKED -> block++;
            }
        }
        return new TestRun("TR-1", "TP-1", "2026-08-14T01:00:00Z", "2026-08-14T01:05:00Z",
                "local", List.of(results),
                new TestRun.Summary(results.length, pass, fail, skip, block), List.of());
    }

    private static TestRun.TestCaseResult result(String caseId, TestRun.TestCaseStatus status) {
        return new TestRun.TestCaseResult(caseId, status, null, List.of());
    }

    private static WorkItem.ScopeResult scopeOk() {
        return new WorkItem.ScopeResult(true, List.of());
    }

    private static WorkItem.ScopeResult scopeFail() {
        return new WorkItem.ScopeResult(false, List.of("out of allowed scope: backend/modules/sample-order/X.java"));
    }

    // ---- TestRun semantics (11-13) ----

    @Test
    void testRunPass() {
        TestRun tr = run(result("TC-1", TestRun.TestCaseStatus.PASS));
        assertThat(tr.summary().passed()).isEqualTo(1);
        assertThat(tr.summary().failed()).isEqualTo(0);
    }

    @Test
    void testRunFail() {
        TestRun tr = run(result("TC-1", TestRun.TestCaseStatus.FAIL));
        assertThat(tr.summary().failed()).isEqualTo(1);
    }

    @Test
    void skippedIsNotPass() {
        // SKIPPED 单独计数，不得并入 PASS；且 skipped-only run 不得让 TEST criterion PASS
        TestRun tr = run(result("TC-1", TestRun.TestCaseStatus.SKIPPED));
        assertThat(tr.summary().skipped()).isEqualTo(1);
        assertThat(tr.summary().passed()).isEqualTo(0);
        WorkItem w = workItem(criterion("AC-2", WorkItem.AcceptanceCriterion.CriterionType.TEST, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), tr, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.BLOCKED);
    }

    // ---- Scope verification (14-16) ----

    @Test
    void scopeVerificationPass() {
        WorkItem.ScopeResult r = ScopeVerifier.verifyPaths(
                workItem().scope(), List.of("backend/modules/sample-customer/Controller.java"));
        assertThat(r.pass()).isTrue();
    }

    @Test
    void outOfScopeChangeFail() {
        WorkItem.ScopeResult r = ScopeVerifier.verifyPaths(
                workItem().scope(), List.of("backend/modules/sample-order/Order.java"));
        assertThat(r.pass()).isFalse();
        assertThat(r.violations()).isNotEmpty();
    }

    @Test
    void forbiddenPathChangeFail() {
        WorkItem.ScopeResult r = ScopeVerifier.verifyPaths(
                workItem().scope(), List.of("backend/modules/sample-order/secret.java"));
        assertThat(r.pass()).isFalse();
    }

    // ---- BUILD criterion (17-18) ----

    @Test
    void buildCriterionPass() {
        WorkItem w = workItem(criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.PASS);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
    }

    @Test
    void buildCriterionMissingEvidence() {
        WorkItem w = workItem(criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), null);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.BLOCKED);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.BLOCKED);
    }

    // ---- TEST criterion (19-20) ----

    @Test
    void testCriterionPass() {
        WorkItem w = workItem(criterion("AC-2", WorkItem.AcceptanceCriterion.CriterionType.TEST, true));
        TestRun tr = run(result("TC-1", TestRun.TestCaseStatus.PASS));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), tr, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.PASS);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
    }

    @Test
    void testCriterionFail() {
        WorkItem w = workItem(criterion("AC-2", WorkItem.AcceptanceCriterion.CriterionType.TEST, true));
        TestRun tr = run(result("TC-1", TestRun.TestCaseStatus.FAIL));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), tr, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.FAIL);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.REJECT);
    }

    // ---- FILE_SCOPE criterion (21-22) ----

    @Test
    void fileScopeCriterionPass() {
        WorkItem w = workItem(criterion("AC-3", WorkItem.AcceptanceCriterion.CriterionType.FILE_SCOPE, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.PASS);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
    }

    @Test
    void fileScopeCriterionFail() {
        WorkItem w = workItem(criterion("AC-3", WorkItem.AcceptanceCriterion.CriterionType.FILE_SCOPE, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeFail(), null, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.FAIL);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.REJECT);
    }

    // ---- ARTIFACT criterion (23-24) ----

    @Test
    void artifactCriterionPass() {
        WorkItem w = workItem(criterion("AC-4", WorkItem.AcceptanceCriterion.CriterionType.ARTIFACT, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(),
                Map.of("AC-4", true), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.PASS);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
    }

    @Test
    void artifactMissing() {
        WorkItem w = workItem(criterion("AC-4", WorkItem.AcceptanceCriterion.CriterionType.ARTIFACT, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(),
                Map.of("AC-4", false), true);
        assertThat(r.criteriaResults().get(0).status()).isEqualTo(VerificationReport.CriterionStatus.BLOCKED);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.BLOCKED);
    }

    // ---- MANUAL (25) ----

    @Test
    void manualRemainsPending() {
        // 非 required MANUAL → PENDING_MANUAL，但不阻断机械 ACCEPT
        WorkItem w = workItem(criterion("AC-5", WorkItem.AcceptanceCriterion.CriterionType.MANUAL, false),
                criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults()).anyMatch(c ->
                c.criterionId().equals("AC-5") && c.status() == VerificationReport.CriterionStatus.PENDING_MANUAL);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
    }

    // ---- Decision logic (26-29) ----

    @Test
    void requiredCriterionBlocked() {
        // required BUILD 无 evidence → BLOCKED → 不得 ACCEPT
        WorkItem w = workItem(criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), null);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.BLOCKED);
    }

    @Test
    void allRequiredMechanicalCriteriaPass() {
        WorkItem w = workItem(
                criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true),
                criterion("AC-2", WorkItem.AcceptanceCriterion.CriterionType.TEST, true),
                criterion("AC-3", WorkItem.AcceptanceCriterion.CriterionType.FILE_SCOPE, true));
        TestRun tr = run(result("TC-1", TestRun.TestCaseStatus.PASS));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), tr, Map.of(), Map.of(), true);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
    }

    @Test
    void failedRequiredCriterionReject() {
        WorkItem w = workItem(
                criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true),
                criterion("AC-2", WorkItem.AcceptanceCriterion.CriterionType.TEST, true));
        TestRun tr = run(result("TC-1", TestRun.TestCaseStatus.FAIL));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), tr, Map.of(), Map.of(), true);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.REJECT);
    }

    @Test
    void missingRequiredEvidenceBlocked() {
        // required ARTIFACT missing → BLOCKED
        WorkItem w = workItem(criterion("AC-4", WorkItem.AcceptanceCriterion.CriterionType.ARTIFACT, true));
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(),
                Map.of(), true);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.BLOCKED);
    }

    // ---- Agent self-acceptance boundary (30) ----

    @Test
    void agentDoneDoesNotImplyAccepted() {
        // Task DONE 是 Task 层状态；VerificationEngine 只计算 decision，
        // 不修改 WorkItem.status；ACCEPTED 只能来自 Verification + Human Approval。
        WorkItem w = workItem(criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true));
        assertThat(w.status()).isEqualTo(WorkItem.WorkItemStatus.VERIFYING);
        VerificationReport r = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), true);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
        // WorkItem 自身 status 不被 engine 修改
        assertThat(w.status()).isEqualTo(WorkItem.WorkItemStatus.VERIFYING);
    }

    // ---- Determinism / immutability (31-32) ----

    @Test
    void deterministicReport() {
        WorkItem w = workItem(criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true));
        VerificationReport r1 = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), true);
        VerificationReport r2 = VerificationEngine.evaluate(w, scopeOk(), null, Map.of(), Map.of(), true);
        assertThat(r1).isEqualTo(r2);
        assertThat(r1.decision()).isEqualTo(r2.decision());
    }

    @Test
    void sourceInputsUnchanged() {
        WorkItem w = workItem(criterion("AC-1", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true));
        WorkItem.ScopeResult scope = scopeOk();
        List<Evidence> ev = List.of(new Evidence("ev-1", Evidence.EvidenceType.BUILD_RESULT, "ref", Map.of()));
        Map<String, List<Evidence>> evidenceByCriterion = new java.util.HashMap<>();
        evidenceByCriterion.put("AC-1", ev);
        VerificationReport r = VerificationEngine.evaluate(w, scope, null, evidenceByCriterion, Map.of(), true);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
        // 输入未被修改
        assertThat(w.acceptanceCriteria()).hasSize(1);
        assertThat(scope.pass()).isTrue();
        assertThat(evidenceByCriterion.get("AC-1")).containsExactly(ev.get(0));
    }
}
