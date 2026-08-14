package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EngineeringPlan;
import com.engineeringplatform.generator.contracts.Evidence;
import com.engineeringplatform.generator.contracts.ImplementationTask;
import com.engineeringplatform.generator.contracts.TestPlan;
import com.engineeringplatform.generator.contracts.TestRun;
import com.engineeringplatform.generator.contracts.VerificationReport;
import com.engineeringplatform.generator.contracts.WorkItem;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engineering Work Model tests (EP-WORK-007/008 §十六 1-10): WorkItem model,
 * structured scope, EngineeringPlan binding, task DAG, evidence reference,
 * TestPlan binding. Pure in-memory fixtures.
 */
class WorkModelTest {

    private static WorkItem validWorkItem() {
        return new WorkItem(
                "WI-42", "Add sample-customer CRUD", "Scaffold CRUD resource",
                WorkItem.WorkItemType.REQUIREMENT,
                WorkItem.WorkItemStatus.PLANNED,
                new WorkItem.ScopeContract(
                        "sample-customer module only",
                        List.of("backend/modules/sample-customer/"),
                        List.of("backend/modules/sample-order/"),
                        List.of("sample-customer"), List.of("sample-order"),
                        List.of("CREATE_FILE", "UPDATE_MANAGED_FILE"),
                        List.of("DELETE")),
                List.of(new WorkItem.AcceptanceCriterion(
                        "AC-1", "compiles", WorkItem.AcceptanceCriterion.CriterionType.BUILD,
                        true, List.of("build-result")),
                        new WorkItem.AcceptanceCriterion(
                                "AC-2", "tests pass", WorkItem.AcceptanceCriterion.CriterionType.TEST,
                                true, List.of("test-run")),
                        new WorkItem.AcceptanceCriterion(
                                "AC-5", "manual smoke", WorkItem.AcceptanceCriterion.CriterionType.MANUAL,
                                false, List.of())),
                List.of("backend/modules/sample-customer/"),
                List.of("backend/modules/sample-order/"),
                List.of("gp-1"), List.of("ch-1"),
                List.of(), List.of(), List.of("build-result", "test-run"));
    }

    // 1. valid WorkItem
    @Test
    void validWorkItemModel() {
        WorkItem w = validWorkItem();
        assertThat(w.workItemId()).isEqualTo("WI-42");
        assertThat(w.status()).isEqualTo(WorkItem.WorkItemStatus.PLANNED);
        assertThat(w.acceptanceCriteria()).hasSize(3);
    }

    // 2. invalid WorkItem — empty acceptance criteria must never ACCEPT (engine guards)
    @Test
    void invalidWorkItemNoCriteriaBlocked() {
        WorkItem w = new WorkItem(
                "WI-0", "x", "y", WorkItem.WorkItemType.BUG,
                WorkItem.WorkItemStatus.PLANNED,
                WorkItem.ScopeContract.any(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        VerificationReport r = VerificationEngine.evaluate(w,
                new WorkItem.ScopeResult(true, List.of()), null, Map.of(), Map.of(), true);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.BLOCKED);
    }

    // 3. structured allowed scope
    @Test
    void structuredAllowedScope() {
        WorkItem.ScopeContract scope = validWorkItem().scope();
        assertThat(scope.allowedPaths()).contains("backend/modules/sample-customer/");
        assertThat(scope.allowedOperationTypes()).contains("CREATE_FILE");
        assertThat(scope.forbiddenOperationTypes()).contains("DELETE");
    }

    // 4. forbidden scope
    @Test
    void forbiddenScopeExpressible() {
        WorkItem.ScopeContract scope = validWorkItem().scope();
        assertThat(scope.forbiddenPaths()).contains("backend/modules/sample-order/");
        assertThat(scope.forbiddenModules()).contains("sample-order");
    }

    // 5. EngineeringPlan binding
    @Test
    void engineeringPlanBinding() {
        EngineeringPlan plan = new EngineeringPlan(
                "EP-1", "WI-42",
                List.of(new EngineeringPlan.Step("s1", "resolve", List.of(), List.of()),
                        new EngineeringPlan.Step("s2", "generate", List.of("s1"), List.of("gp-1"))),
                List.of("gp-1"), List.of("ch-1"), "unit tests", List.of(), "rollback via executor");
        assertThat(plan.planId()).isEqualTo("EP-1");
        assertThat(plan.workItemId()).isEqualTo("WI-42");
        assertThat(plan.steps().get(1).dependencies()).contains("s1");
    }

    // 6. task DAG valid
    @Test
    void taskDagValid() {
        List<ImplementationTask> tasks = List.of(
                task("T-1", List.of()),
                task("T-2", List.of("T-1")),
                task("T-3", List.of("T-2")));
        ImplementationTask.DagResult r = TaskGraphValidator.validate(tasks);
        assertThat(r.valid()).isTrue();
        assertThat(r.unknownDependencies()).isEmpty();
        assertThat(r.cycles()).isEmpty();
    }

    // 7. unknown task dependency
    @Test
    void unknownTaskDependency() {
        List<ImplementationTask> tasks = List.of(
                task("T-1", List.of()),
                task("T-2", List.of("T-99")));
        ImplementationTask.DagResult r = TaskGraphValidator.validate(tasks);
        assertThat(r.valid()).isFalse();
        assertThat(r.unknownDependencies()).contains("T-2 -> T-99");
    }

    // 8. task cycle
    @Test
    void taskCycle() {
        List<ImplementationTask> tasks = List.of(
                task("T-1", List.of("T-2")),
                task("T-2", List.of("T-1")));
        ImplementationTask.DagResult r = TaskGraphValidator.validate(tasks);
        assertThat(r.valid()).isFalse();
        assertThat(r.cycles()).isNotEmpty();
    }

    // 9. evidence reference (metadata only, no big content)
    @Test
    void evidenceReference() {
        Evidence e = new Evidence("ev-1", Evidence.EvidenceType.BUILD_RESULT,
                "artifacts/build-report.json", Map.of("status", "SUCCESS"));
        assertThat(e.reference()).isEqualTo("artifacts/build-report.json");
        assertThat(e.metadata().get("status")).isEqualTo("SUCCESS");
        // 引用方式：不内嵌大内容
        assertThat(e.metadata().keySet()).doesNotContain("fullLog");
    }

    // 10. TestPlan binding
    @Test
    void testPlanBinding() {
        TestPlan tp = new TestPlan("TP-1", "WI-42", "EP-1",
                List.of(new TestPlan.TestCase("TC-1", TestPlan.TestCase.TestType.UNIT,
                        "SampleCustomerService", "CRUD pass", true, "AC-2")));
        assertThat(tp.testPlanId()).isEqualTo("TP-1");
        assertThat(tp.workItemId()).isEqualTo("WI-42");
        assertThat(tp.engineeringPlanId()).isEqualTo("EP-1");
        assertThat(tp.testCases().get(0).acceptanceCriterion()).isEqualTo("AC-2");
    }

    private static ImplementationTask task(String id, List<String> deps) {
        return new ImplementationTask(id, "WI-42", "EP-1", "objective " + id,
                ImplementationTask.TaskStatus.PLANNED, deps, List.of(), List.of(), List.of());
    }
}
