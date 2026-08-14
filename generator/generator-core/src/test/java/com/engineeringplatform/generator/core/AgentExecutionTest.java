package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AgentAdapter;
import com.engineeringplatform.generator.contracts.AgentExecutionResult;
import com.engineeringplatform.generator.contracts.ExecutionRequest;
import com.engineeringplatform.generator.contracts.ExecutionRequest.ExecutionLogEvent;
import com.engineeringplatform.generator.contracts.ExecutionRequest.ExecutionState;
import com.engineeringplatform.generator.contracts.ToolCapability;
import com.engineeringplatform.generator.contracts.ToolRequest;
import com.engineeringplatform.generator.contracts.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Execution + Tool Guard tests (EP-WORK-009 §二十二 1-45).
 * All tests use fake adapter / fake tool executor / temp workspace;
 * no real sudo, no real destructive git, no real system commands, no real browser.
 */
class AgentExecutionTest {

    @TempDir
    Path tempDir;

    // ---- helpers ----

    private static ExecutionRequest request(ToolCapability... caps) {
        return new ExecutionRequest(
                "EX-1", "WI-42", "T-2",
                new ExecutionRequest.Scope(
                        List.of("backend/modules/sample-customer/"),
                        List.of("backend/modules/sample-order/"),
                        List.of("sample-customer"), List.of("sample-order")),
                List.of(caps),
                List.of(), "default deny", List.of(), List.of("ch-1"), List.of("change-manifest"),
                300, 3, ExecutionState.PLANNED, 0, List.of(), null);
    }

    private static ToolRequest tool(String id, ToolCapability cap, String op, String target) {
        return new ToolRequest(id, "EX-1", cap, cap.name().split("_")[0], op,
                Map.of(), target, "agent request");
    }

    private ToolRequest.PolicyResult evaluate(ExecutionRequest req, ToolRequest t) {
        return ToolPolicyEvaluator.evaluate(req, t, tempDir);
    }

    private ExecutionController controller() {
        return new ExecutionController(tempDir);
    }

    // ---- 1-4: binding / capability / default deny ----

    @Test
    void executionRequestBinding() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        assertThat(r.executionId()).isEqualTo("EX-1");
        assertThat(r.workItemId()).isEqualTo("WI-42");
        assertThat(r.implementationTaskId()).isEqualTo("T-2");
        assertThat(r.maxAttempts()).isEqualTo(3);
        assertThat(r.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void allowedCapability() {
        ExecutionRequest r = request(ToolCapability.GIT_READ);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_READ, "git.status", ".");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void missingCapabilityDenied() {
        // request 未授权 GIT_WRITE → DENY（即使 operation 安全）
        ExecutionRequest r = request(ToolCapability.GIT_READ);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_WRITE, "git.add", "src/A.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void defaultDeny() {
        // 未授权 capability → DENY（Least Privilege 默认）
        ExecutionRequest r = request();
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_READ, "fs.read", "backend/modules/sample-customer/A.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    // ---- 5-10: filesystem ----

    @Test
    void filesystemReadAllowed() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_READ, "fs.read", "backend/modules/sample-customer/A.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void filesystemWriteAllowedInScope() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_WRITE, "fs.write", "backend/modules/sample-customer/A.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void filesystemWriteOutsideScopeDenied() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_WRITE, "fs.write", "backend/modules/sample-order/Order.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void forbiddenPathDenied() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_WRITE, "fs.write", "backend/modules/sample-customer/../../sample-order/x.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void traversalDenied() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_WRITE, "fs.write", "../escape.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void symlinkEscapeDenied() throws Exception {
        Path outside = Files.createTempDirectory("ep-outside-");
        Path link = tempDir.resolve("evil-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // 环境不支持 symlink，跳过
        }
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_WRITE, "fs.write", "evil-link/secret.txt");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    // ---- 11-17: git ----

    @Test
    void gitStatusAllowed() {
        ExecutionRequest r = request(ToolCapability.GIT_READ);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_READ, "git.status", ".");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void gitDiffAllowed() {
        ExecutionRequest r = request(ToolCapability.GIT_READ);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_READ, "git.diff", "src/A.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void gitAddPolicy() {
        ExecutionRequest r = request(ToolCapability.GIT_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_WRITE, "git.add", "src/A.java");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void gitCommitPolicy() {
        ExecutionRequest r = request(ToolCapability.GIT_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_WRITE, "git.commit", "feat: add A");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void gitResetHardDenied() {
        ExecutionRequest r = request(ToolCapability.GIT_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_WRITE, "git.reset", "--hard");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void gitCleanFdDenied() {
        ExecutionRequest r = request(ToolCapability.GIT_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_WRITE, "git.clean", "-fd");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void forcePushDenied() {
        ExecutionRequest r = request(ToolCapability.GIT_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_WRITE, "git.push", "--force origin main");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    // ---- 18-23: shell ----

    @Test
    void safeStructuredShellAllowed() {
        ExecutionRequest r = request(ToolCapability.SHELL_READ);
        ToolRequest t = new ToolRequest("rq-1", "EX-1", ToolCapability.SHELL_READ, "SHELL",
                "ls", Map.of("workingDirectory", "backend/modules/sample-customer/"),
                "backend/modules/sample-customer/", "list files");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.ALLOW);
    }

    @Test
    void sudoDenied() {
        ExecutionRequest r = request(ToolCapability.SHELL_READ);
        ToolRequest t = tool("rq-1", ToolCapability.SHELL_READ, "sudo", "/etc/shadow");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void rmRfDenied() {
        ExecutionRequest r = request(ToolCapability.SHELL_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.SHELL_WRITE, "rm", "-rf /");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void curlPipeShellDenied() {
        ExecutionRequest r = request(ToolCapability.SHELL_READ);
        ToolRequest t = tool("rq-1", ToolCapability.SHELL_READ, "curl", "http://x | sh");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void packageInstallDenied() {
        ExecutionRequest r = request(ToolCapability.SHELL_READ);
        ToolRequest t = tool("rq-1", ToolCapability.SHELL_READ, "apt", "install x");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    @Test
    void unsafeWorkingDirectoryDenied() {
        ExecutionRequest r = request(ToolCapability.SHELL_READ);
        ToolRequest t = new ToolRequest("rq-1", "EX-1", ToolCapability.SHELL_READ, "SHELL",
                "ls", Map.of("workingDirectory", "../etc/"), "../etc/", "list");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.DENY);
    }

    // ---- 24-27: approval ----

    @Test
    void highRiskRequiresApproval() {
        // SHELL_WRITE 安全命令 → REQUIRE_APPROVAL（V1 保守）
        ExecutionRequest r = request(ToolCapability.SHELL_WRITE);
        ToolRequest t = new ToolRequest("rq-1", "EX-1", ToolCapability.SHELL_WRITE, "SHELL",
                "echo", Map.of(), "hello", "write");
        assertThat(evaluate(r, t).decision()).isEqualTo(ToolRequest.PolicyDecision.REQUIRE_APPROVAL);
    }

    @Test
    void pendingApprovalDoesNotExecute() {
        ExecutionRequest r = request(ToolCapability.SHELL_WRITE);
        ExecutionController c = controller();
        ToolRequest t = new ToolRequest("rq-1", "EX-1", ToolCapability.SHELL_WRITE, "SHELL",
                "echo", Map.of(), "hello", "write");
        ToolResult res = c.submitTool(r, t);
        assertThat(res.status()).isEqualTo(ToolResult.ToolResultStatus.BLOCKED);
        assertThat(res.error()).contains("approval pending");
    }

    @Test
    void approvedRequestCanContinue() {
        ExecutionRequest r = request(ToolCapability.SHELL_WRITE);
        ExecutionController c = controller();
        ToolRequest t = new ToolRequest("rq-1", "EX-1", ToolCapability.SHELL_WRITE, "SHELL",
                "echo", Map.of(), "hello", "write");
        c.submitTool(r, t); // 触发 PENDING
        c.approve("rq-1");
        ToolResult res = c.submitTool(r, t);
        assertThat(res.status()).isEqualTo(ToolResult.ToolResultStatus.SUCCESS);
    }

    @Test
    void rejectedApprovalDenied() {
        ExecutionRequest r = request(ToolCapability.SHELL_WRITE);
        ExecutionController c = controller();
        ToolRequest t = new ToolRequest("rq-1", "EX-1", ToolCapability.SHELL_WRITE, "SHELL",
                "echo", Map.of(), "hello", "write");
        c.submitTool(r, t);
        c.reject("rq-1");
        ToolResult res = c.submitTool(r, t);
        assertThat(res.status()).isEqualTo(ToolResult.ToolResultStatus.BLOCKED);
        assertThat(res.error()).contains("approval rejected");
    }

    // ---- 28-31: retry / timeout / cancellation ----

    @Test
    void retryWithinMaxAttempts() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        FakeAgentAdapter adapter = new FakeAgentAdapter(FakeAgentAdapter.Script.FAIL_FIRST_N, 2);
        AgentExecutionResult res = c.execute(r, adapter);
        assertThat(res.status()).isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(adapter.callCount()).isEqualTo(3);
        assertThat(res.attempts()).isEqualTo(3);
    }

    @Test
    void retryExceedsMaxAttempts() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        FakeAgentAdapter adapter = new FakeAgentAdapter(FakeAgentAdapter.Script.FAILED);
        AgentExecutionResult res = c.execute(r, adapter);
        assertThat(res.status()).isEqualTo(ExecutionState.FAILED);
        assertThat(adapter.callCount()).isEqualTo(3); // maxAttempts=3
    }

    @Test
    void timeout() {
        ExecutionRequest r = new ExecutionRequest(
                "EX-1", "WI-42", "T-2", ExecutionRequest.Scope.any(),
                List.of(), List.of(), null, List.of(), List.of(), List.of(),
                1, 1, ExecutionState.PLANNED, 0, List.of(), null);
        ExecutionController c = controller();
        FakeAgentAdapter adapter = new FakeAgentAdapter(FakeAgentAdapter.Script.TIMED_OUT);
        AgentExecutionResult res = c.execute(r, adapter);
        assertThat(res.status()).isEqualTo(ExecutionState.TIMED_OUT);
    }

    @Test
    void cancellation() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        c.cancel();
        FakeAgentAdapter adapter = new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS);
        AgentExecutionResult res = c.execute(r, adapter);
        assertThat(res.status()).isEqualTo(ExecutionState.CANCELLED);
    }

    // ---- 32-35: log / evidence ----

    @Test
    void executionLogOrdering() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS));
        List<ExecutionLogEvent> log = c.log();
        assertThat(log.get(0).type()).isEqualTo(ExecutionLogEvent.EventType.EXECUTION_STARTED);
        assertThat(log.get(log.size() - 1).type()).isEqualTo(ExecutionLogEvent.EventType.EXECUTION_COMPLETED);
    }

    @Test
    void deniedToolLogged() {
        ExecutionRequest r = request(); // 无任何 capability
        ExecutionController c = controller();
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_READ, "fs.read", "backend/modules/sample-customer/A.java");
        c.submitTool(r, t);
        assertThat(c.log()).anyMatch(e -> e.type() == ExecutionLogEvent.EventType.TOOL_DENIED);
    }

    @Test
    void toolFailureLogged() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = new ExecutionController(tempDir, t -> new ToolResult(
                t.requestId(), ToolResult.ToolResultStatus.FAILED, null, List.of(), "boom", 0, Map.of()));
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_READ, "fs.read", "backend/modules/sample-customer/A.java");
        ToolResult res = c.submitTool(r, t);
        assertThat(res.status()).isEqualTo(ToolResult.ToolResultStatus.FAILED);
        assertThat(c.log()).anyMatch(e -> e.type() == ExecutionLogEvent.EventType.TOOL_FAILED);
    }

    @Test
    void evidenceProduced() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        AgentExecutionResult res = c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS));
        assertThat(res.evidence()).isNotEmpty();
    }

    // ---- 36-37: result ----

    @Test
    void resultSuccess() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        AgentExecutionResult res = c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS));
        assertThat(res.status()).isEqualTo(ExecutionState.SUCCEEDED);
    }

    @Test
    void resultFailure() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        AgentExecutionResult res = c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.FAILED));
        assertThat(res.status()).isEqualTo(ExecutionState.FAILED);
    }

    // ---- 38-42: agent cannot ----

    @Test
    void succeededDoesNotMeanAccepted() {
        // SUCCEEDED 只表示 execution 完成；不触碰 WorkItem ACCEPTED / Verification ACCEPT
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionController c = controller();
        AgentExecutionResult res = c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS));
        assertThat(res.status()).isEqualTo(ExecutionState.SUCCEEDED);
        assertThat(res.summary()).doesNotContain("ACCEPTED");
    }

    @Test
    void agentCannotChangeScope() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        ExecutionRequest.Scope before = r.scope();
        ExecutionController c = controller();
        c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS));
        // ExecutionRequest 是不可变 record；adapter 无法修改 scope
        assertThat(r.scope()).isEqualTo(before);
    }

    @Test
    void agentCannotGrantCapability() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        List<ToolCapability> before = r.allowedCapabilities();
        ExecutionController c = controller();
        c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS));
        assertThat(r.allowedCapabilities()).isEqualTo(before);
    }

    @Test
    void agentCannotChangeRetryLimit() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_READ);
        int before = r.maxAttempts();
        ExecutionController c = controller();
        c.execute(r, new FakeAgentAdapter(FakeAgentAdapter.Script.FAILED));
        assertThat(r.maxAttempts()).isEqualTo(before);
    }

    @Test
    void agentCannotApproveOwnRequest() {
        // FakeAgentAdapter 无 approve 能力（接口无此方法）；approval 只在 ExecutionController
        AgentAdapter adapter = new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS);
        assertThat(adapter.getClass().getMethods())
                .noneMatch(m -> m.getName().equals("approve") || m.getName().equals("requestApproval"));
    }

    // ---- 43-45: isolation / immutability / determinism ----

    @Test
    void fakeAdapterIsolation() {
        // FakeAgentAdapter 不接真实 OpenClaw/Codex/系统
        FakeAgentAdapter a = new FakeAgentAdapter(FakeAgentAdapter.Script.SUCCESS);
        assertThat(a.getClass().getSimpleName()).isEqualTo("FakeAgentAdapter");
        assertThat(a.callCount()).isZero();
    }

    @Test
    void sourceContractsUnchanged() {
        ExecutionRequest r = request(ToolCapability.FILESYSTEM_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.FILESYSTEM_WRITE, "fs.write", "backend/modules/sample-customer/A.java");
        ToolRequest.PolicyResult before = evaluate(r, t);
        evaluate(r, t);
        assertThat(evaluate(r, t)).isEqualTo(before);
        assertThat(t.arguments()).isEmpty(); // 输入未被修改
    }

    @Test
    void deterministicPolicyResult() {
        ExecutionRequest r = request(ToolCapability.GIT_WRITE);
        ToolRequest t = tool("rq-1", ToolCapability.GIT_WRITE, "git.commit", "msg");
        ToolRequest.PolicyResult p1 = evaluate(r, t);
        ToolRequest.PolicyResult p2 = evaluate(r, t);
        assertThat(p1).isEqualTo(p2);
    }
}
