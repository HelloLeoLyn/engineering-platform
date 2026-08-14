package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AgentAdapter;
import com.engineeringplatform.generator.contracts.AgentExecutionResult;
import com.engineeringplatform.generator.contracts.ChangeManifest;
import com.engineeringplatform.generator.contracts.DryRunResult;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ExecutionRequest;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.OperationType;
import com.engineeringplatform.generator.contracts.OverwritePolicy;
import com.engineeringplatform.generator.contracts.Ownership;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolverInput;
import com.engineeringplatform.generator.contracts.TestPlan;
import com.engineeringplatform.generator.contracts.TestRun;
import com.engineeringplatform.generator.contracts.ToolCapability;
import com.engineeringplatform.generator.contracts.ToolRequest;
import com.engineeringplatform.generator.contracts.ToolResult;
import com.engineeringplatform.generator.contracts.VerificationReport;
import com.engineeringplatform.generator.contracts.WorkItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EP-WORK-010A E2E Tests（tests/fixtures/e2e/minimal-project/ 的等价 in-memory 输入）。
 *
 * Happy Path：声明式输入 → Resolver → EPM → GenerationPlan → DryRun → Executor(temp workspace)
 * → WorkItem → Fake Agent → Evidence → TestRun → VerificationReport → ACCEPT。
 * 12 个 Failure Paths：每种失败停在正确层级，不得被后续阶段误认为 SUCCESS。
 *
 * 不使用真实 OpenClaw/Codex/Shell/Git destructive/Browser/network。
 */
class E2EMinimalProjectTest {

    @TempDir
    Path tempDir;

    // ---- fixture：与 tests/fixtures/e2e/minimal-project/ 等价的声明式输入 ----

    private static final ManifestValidationPort RUNTIME_VALIDATOR = new ManifestRuntimeValidator();

    private static Map<String, Object> platformManifest() {
        return Map.of(
                "schemaVersion", 1,
                "platform", Map.of("id", "engineering-platform", "version", "0.1.0"),
                "technology", Map.of("java", "25", "node", "24"),
                "profiles", Map.of(
                        "presets", Map.of(
                                "standard", Map.of("application", "standard", "infrastructure", "standard",
                                        "security", "standard", "quality", "Q2")),
                        "default", Map.of("application", "standard", "infrastructure", "standard",
                                "security", "standard", "quality", "Q2")),
                "registries", Map.of(
                        "capability", Map.of("path", "registry/capabilities.yaml"),
                        "provider", Map.of("path", "registry/providers.yaml"),
                        "module", Map.of("path", "registry/modules.yaml")));
    }

    private static Map<String, Object> projectManifest() {
        return Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "e2e-minimal", "name", "E2E Minimal Project", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform", "version", "0.1.0"),
                "profiles", Map.of("default", "standard"),
                "modules", List.of(Map.of("id", "sample-customer")));
    }

    private static Map<String, Map<String, Object>> moduleManifests() {
        return Map.of(
                "sample-customer", Map.of(
                        "schemaVersion", 1,
                        "module", Map.of("id", "sample-customer", "name", "Sample Customer", "version", "0.1.0"),
                        "compatibility", Map.of("platformVersion", "0.1.x")));
    }

    private static ResolverInput resolverInput() {
        return new ResolverInput(platformManifest(), projectManifest(), moduleManifests(), Map.of(),
                Map.of("modules", Set.of("sample-customer")));
    }

    // ---- 7. E2E Happy Path ----

    @Test
    void e2eHappyPath() throws Exception {
        // 1. Resolver：声明式输入 → EPM（SUCCESS）
        ResolutionResult resolved = new CompleteResolver(RUNTIME_VALIDATOR).resolve(resolverInput());
        assertThat(resolved.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = resolved.effectiveProject();
        assertThat(epm).isNotNull();
        String resolutionId = epm.resolution().resolutionId();
        String inputHash = epm.resolution().inputHash();

        // 2. GenerationPlan（绑定 resolutionId + inputHash）
        GenerationPlanner planner = new GenerationPlanner();
        GenerationPlan plan = planner.plan(epm, "0.1.0", "SCAFFOLD",
                List.of(GenerationOperation.builder()
                        .operationId("op-1").type(OperationType.CREATE_FILE)
                        .targetPath("backend/modules/sample-customer/api/Controller.java")
                        .ownership(Ownership.GENERATED).overwritePolicy(OverwritePolicy.ALLOWED)
                        .content("public class Controller {}").reason("scaffold")
                        .build()));
        assertThat(plan.resolutionId()).isEqualTo(resolutionId);
        assertThat(plan.inputHash()).isEqualTo(inputHash);

        // 3. Dry Run（不写文件）
        DryRunResult dry = new DryRunner().dryRun(plan, tempDir, java.util.Map.of());
        assertThat(dry.executable()).isTrue();
        assertThat(Files.exists(tempDir.resolve("backend/modules/sample-customer/api/Controller.java"))).isFalse();

        // 4. Executor（temp workspace）
        ExecutionResult exec = new GeneratorExecutor().execute(plan, tempDir);
        assertThat(exec.status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ChangeManifest changeManifest = exec.changeManifest();
        assertThat(changeManifest).isNotNull();
        assertThat(changeManifest.planId()).isEqualTo(plan.planId());
        assertThat(changeManifest.transactionId()).isNotBlank();
        String transactionId = changeManifest.transactionId();
        String changeId = changeManifest.changeId();

        // 5. WorkItem + ScopeVerifier（scope 匹配 change paths）
        WorkItem workItem = new WorkItem(
                "WI-E2E", "E2E scaffold", "scaffold sample-customer", WorkItem.WorkItemType.REQUIREMENT,
                WorkItem.WorkItemStatus.VERIFYING,
                new WorkItem.ScopeContract(null, List.of("backend/modules/sample-customer/"),
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new WorkItem.AcceptanceCriterion(
                        "AC-1", "build ok", WorkItem.AcceptanceCriterion.CriterionType.BUILD, true, List.of()),
                        new WorkItem.AcceptanceCriterion(
                                "AC-2", "tests ok", WorkItem.AcceptanceCriterion.CriterionType.TEST, true, List.of()),
                        new WorkItem.AcceptanceCriterion(
                                "AC-3", "scope ok", WorkItem.AcceptanceCriterion.CriterionType.FILE_SCOPE, true, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        WorkItem.ScopeResult scopeResult = ScopeVerifier.verify(workItem.scope(), changeManifest);
        assertThat(scopeResult.pass()).isTrue();

        // 6. Fake Agent Execution + Evidence
        ExecutionRequest execReq = new ExecutionRequest(
                "EX-E2E", "WI-E2E", "T-1",
                new ExecutionRequest.Scope(List.of("backend/modules/sample-customer/"), List.of(), List.of(), List.of()),
                List.of(ToolCapability.FILESYSTEM_READ), List.of("fs.read"),
                "default deny", List.of(), List.of("ch-" + transactionId.substring(3)), List.of("change-manifest"),
                300, 2, ExecutionRequest.ExecutionState.PLANNED, 0, List.of(), null);
        ExecutionController controller = new ExecutionController(tempDir);
        AgentExecutionResult agentResult = controller.execute(execReq, new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS));
        assertThat(agentResult.status()).isEqualTo(ExecutionRequest.ExecutionState.SUCCEEDED);
        assertThat(agentResult.evidence()).isNotEmpty();

        // 7. TestRun（PASS）
        TestPlan testPlan = new TestPlan("TP-E2E", "WI-E2E", "EP-E2E",
                List.of(new TestPlan.TestCase("TC-1", TestPlan.TestCase.TestType.UNIT,
                        "Controller", "compiles", true, "AC-2")));
        TestRun testRun = new TestRun("TR-E2E", "TP-E2E", "2026-08-14T01:00:00Z", "2026-08-14T01:01:00Z",
                "e2e-temp", List.of(new TestRun.TestCaseResult("TC-1", TestRun.TestCaseStatus.PASS, null, List.of())),
                new TestRun.Summary(1, 1, 0, 0, 0), List.of());

        // 8. VerificationReport → ACCEPT
        VerificationReport report = VerificationEngine.evaluate(workItem, scopeResult, testRun,
                Map.of(), Map.of(), true);
        assertThat(report.decision()).isEqualTo(VerificationReport.Decision.ACCEPT);
        assertThat(report.workItemId()).isEqualTo("WI-E2E");
        assertThat(report.verificationId()).startsWith("vr-");

        // 9. identity 串联断言
        assertThat(changeId).startsWith("ch-");
        assertThat(transactionId).startsWith("tx-");
        assertThat(resolutionId).startsWith("res-");
        assertThat(plan.planId()).startsWith("gp-");
        // 下游 → 上游
        assertThat(changeManifest.planId()).isEqualTo(plan.planId());
        assertThat(agentResult.executionId()).isEqualTo("EX-E2E");
        assertThat(testRun.testPlanId()).isEqualTo("TP-E2E");
    }

    // ---- 8. E2E Failure Paths ----

    // A. invalid manifest → 停在 Resolver（FAILED，不产生 EPM）
    @Test
    void failureInvalidManifest() {
        Map<String, Object> badProject = new java.util.HashMap<>(projectManifest());
        badProject.remove("schemaVersion");
        ResolutionResult r = new CompleteResolver(RUNTIME_VALIDATOR).resolve(
                new ResolverInput(platformManifest(), badProject, moduleManifests(), Map.of(), Map.of()));
        assertThat(r.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(r.effectiveProject()).isNull();
    }

    // B. unknown reference → UNKNOWN_REFERENCE，停在 Resolver
    @Test
    void failureUnknownReference() {
        Map<String, Object> project = new java.util.HashMap<>(projectManifest());
        project.put("modules", List.of(Map.of("id", "ghost-module")));
        ResolutionResult r = new CompleteResolver(RUNTIME_VALIDATOR).resolve(
                new ResolverInput(platformManifest(), project, Map.of(), Map.of(), Map.of("modules", Set.of())));
        assertThat(r.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(r.errors()).anyMatch(e -> e.code().equals("UNKNOWN_REFERENCE"));
    }

    // C. dependency conflict → DEPENDENCY_CONFLICT，停在 Resolver
    @Test
    void failureDependencyConflict() {
        Map<String, Map<String, Object>> modules = Map.of(
                "app", Map.of("schemaVersion", 1,
                        "module", Map.of("id", "app", "version", "0.1.0"),
                        "compatibility", Map.of("platformVersion", "0.1.x"),
                        "dependencies", Map.of("requiredModules", List.of("lib"))),
                "lib", Map.of("schemaVersion", 1,
                        "module", Map.of("id", "lib", "version", "0.1.0"),
                        "compatibility", Map.of("platformVersion", "0.1.x")));
        Map<String, Object> project = new java.util.HashMap<>(projectManifest());
        project.put("modules", List.of(Map.of("id", "app"), Map.of("id", "lib", "enabled", false)));
        ResolutionResult r = new CompleteResolver(RUNTIME_VALIDATOR).resolve(
                new ResolverInput(platformManifest(), project, modules, Map.of(),
                        Map.of("modules", Set.of("app", "lib"))));
        assertThat(r.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(r.errors()).anyMatch(e -> e.code().equals("DEPENDENCY_CONFLICT"));
    }

    // D. generation path violation → 停在 Executor（PathSafety 拒绝，不写文件）
    @Test
    void failureGenerationPathViolation() {
        GenerationPlan plan = new GenerationPlan("gp-e2e", "SCAFFOLD", "0.1.0", "res-x", "hash-x",
                "e2e", List.of(GenerationOperation.builder()
                        .operationId("op-1").type(OperationType.CREATE_FILE)
                        .targetPath("../escape.java").ownership(Ownership.GENERATED)
                        .overwritePolicy(OverwritePolicy.ALLOWED).content("x").reason("e2e")
                        .build()),
                List.of(), List.of(), Map.of("create", 1, "modify", 0, "delete", 0));
        ExecutionResult r = new GeneratorExecutor().execute(plan, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
        assertThat(Files.exists(tempDir.resolve("../escape.java"))).isFalse();
    }

    // E. file precondition conflict → expectedBeforeHash 不匹配，停在 Executor（不覆盖）
    @Test
    void failureFilePreconditionConflict() throws Exception {
        Path target = tempDir.resolve("src/A.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "actual");
        GenerationPlan plan = new GenerationPlan("gp-e2e", "SCAFFOLD", "0.1.0", "res-x", "hash-x",
                "e2e", List.of(GenerationOperation.builder()
                        .operationId("op-1").type(OperationType.UPDATE_MANAGED_FILE)
                        .targetPath("src/A.java").ownership(Ownership.GENERATED)
                        .overwritePolicy(OverwritePolicy.ALLOWED)
                        .expectedBeforeHash("deadbeef").content("new").reason("e2e")
                        .build()),
                List.of(), List.of(), Map.of("create", 0, "modify", 1, "delete", 0));
        ExecutionResult r = new GeneratorExecutor().execute(plan, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
        assertThat(Files.readString(target)).isEqualTo("actual");
    }

    // F. executor rollback → 部分失败 → ROLLED_BACK，先前操作恢复
    @Test
    void failureExecutorRollback() throws Exception {
        Files.createDirectories(tempDir.resolve("src/dir-as-file"));
        GenerationPlan plan = new GenerationPlan("gp-e2e", "SCAFFOLD", "0.1.0", "res-x", "hash-x",
                "e2e", List.of(
                        GenerationOperation.builder().operationId("op-1").type(OperationType.CREATE_FILE)
                                .targetPath("src/A.java").ownership(Ownership.GENERATED)
                                .overwritePolicy(OverwritePolicy.ALLOWED).content("a").reason("e2e").build(),
                        GenerationOperation.builder().operationId("op-2").type(OperationType.CREATE_FILE)
                                .targetPath("src/dir-as-file").ownership(Ownership.GENERATED)
                                .overwritePolicy(OverwritePolicy.ALLOWED).content("b").reason("e2e").build()),
                List.of(), List.of(), Map.of("create", 2, "modify", 0, "delete", 0));
        ExecutionResult r = new GeneratorExecutor().execute(plan, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.ROLLED_BACK);
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isFalse();
    }

    // G. out-of-scope change → ScopeVerifier FAIL → Verification REJECT
    @Test
    void failureOutOfScopeChange() {
        WorkItem.ScopeContract scope = new WorkItem.ScopeContract(null,
                List.of("backend/modules/sample-customer/"), List.of(), List.of(), List.of(), List.of(), List.of());
        ChangeManifest cm = new ChangeManifest("ch-1", "gp-1", "tx-1",
                List.of(new ChangeManifest.ChangeEntry("backend/modules/sample-order/Order.java",
                        OperationType.CREATE_FILE, Ownership.GENERATED,
                        ChangeManifest.ChangeStatus.APPLIED,
                        new ChangeManifest.FileState(false, null, 0, null),
                        new ChangeManifest.FileState(true, "aa", 1, null), "e2e")),
                new ChangeManifest.ChangeResult("SUCCESS", 1, 0, 0, null), null);
        WorkItem.ScopeResult sr = ScopeVerifier.verify(scope, cm);
        assertThat(sr.pass()).isFalse();
        assertThat(sr.violations()).isNotEmpty();
    }

    // H. forbidden tool request → ToolPolicyEvaluator DENY，Tool 不执行
    @Test
    void failureForbiddenToolRequest() {
        ExecutionRequest req = new ExecutionRequest("EX-1", "WI-1", "T-1",
                ExecutionRequest.Scope.any(), List.of(ToolCapability.FILESYSTEM_READ),
                List.of(), null, List.of(), List.of(), List.of(), 300, 1,
                ExecutionRequest.ExecutionState.PLANNED, 0, List.of(), null);
        ToolRequest tool = new ToolRequest("rq-1", "EX-1", ToolCapability.GIT_WRITE, "GIT",
                "git.reset", Map.of(), "--hard", "e2e");
        ToolRequest.PolicyResult p = ToolPolicyEvaluator.evaluate(req, tool, tempDir);
        assertThat(p.decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    // I. approval pending → SHELL_WRITE REQUIRE_APPROVAL → 不执行（BLOCKED）
    @Test
    void failureApprovalPending() {
        ExecutionRequest req = new ExecutionRequest("EX-1", "WI-1", "T-1",
                ExecutionRequest.Scope.any(), List.of(ToolCapability.SHELL_WRITE),
                List.of(), null, List.of(), List.of(), List.of(), 300, 1,
                ExecutionRequest.ExecutionState.PLANNED, 0, List.of(), null);
        ToolRequest tool = new ToolRequest("rq-1", "EX-1", ToolCapability.SHELL_WRITE, "SHELL",
                "echo", Map.of(), "hello", "e2e");
        ExecutionController c = new ExecutionController(tempDir);
        ToolResult r = c.submitTool(req, tool);
        assertThat(r.status()).isEqualTo(ToolResult.ToolResultStatus.BLOCKED);
        assertThat(r.error()).contains("approval");
    }

    // J. agent execution failed → FAILED（不进入 Verification ACCEPT）
    @Test
    void failureAgentExecutionFailed() {
        ExecutionRequest req = new ExecutionRequest("EX-1", "WI-1", "T-1",
                ExecutionRequest.Scope.any(), List.of(), List.of(), null, List.of(), List.of(), List.of(),
                300, 1, ExecutionRequest.ExecutionState.PLANNED, 0, List.of(), null);
        AgentExecutionResult r = new ExecutionController(tempDir).execute(req,
                new FakeAgentAdapter(FakeAgentAdapter.Script.FAILED));
        assertThat(r.status()).isEqualTo(ExecutionRequest.ExecutionState.FAILED);
    }

    // K. required test failed → VerificationEngine REJECT
    @Test
    void failureRequiredTestFailed() {
        WorkItem w = new WorkItem("WI-1", "t", "o", WorkItem.WorkItemType.REQUIREMENT,
                WorkItem.WorkItemStatus.VERIFYING, WorkItem.ScopeContract.any(),
                List.of(new WorkItem.AcceptanceCriterion("AC-2", "tests",
                        WorkItem.AcceptanceCriterion.CriterionType.TEST, true, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        TestRun tr = new TestRun("TR-1", "TP-1", "t", "t", "e2e",
                List.of(new TestRun.TestCaseResult("TC-1", TestRun.TestCaseStatus.FAIL, "boom", List.of())),
                new TestRun.Summary(1, 0, 1, 0, 0), List.of());
        VerificationReport r = VerificationEngine.evaluate(w, new WorkItem.ScopeResult(true, List.of()),
                tr, Map.of(), Map.of(), true);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.REJECT);
    }

    // L. manual criterion pending → PENDING_MANUAL（required manual → BLOCKED）
    @Test
    void failureManualCriterionPending() {
        WorkItem w = new WorkItem("WI-1", "t", "o", WorkItem.WorkItemType.REQUIREMENT,
                WorkItem.WorkItemStatus.VERIFYING, WorkItem.ScopeContract.any(),
                List.of(new WorkItem.AcceptanceCriterion("AC-9", "manual review",
                        WorkItem.AcceptanceCriterion.CriterionType.MANUAL, true, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        VerificationReport r = VerificationEngine.evaluate(w, new WorkItem.ScopeResult(true, List.of()),
                null, Map.of(), Map.of(), true);
        assertThat(r.criteriaResults().get(0).status())
                .isEqualTo(VerificationReport.CriterionStatus.PENDING_MANUAL);
        assertThat(r.decision()).isEqualTo(VerificationReport.Decision.BLOCKED);
    }
}
