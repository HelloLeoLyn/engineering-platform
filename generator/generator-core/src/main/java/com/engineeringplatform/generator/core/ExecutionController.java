package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AgentAdapter;
import com.engineeringplatform.generator.contracts.AgentExecutionResult;
import com.engineeringplatform.generator.contracts.Evidence;
import com.engineeringplatform.generator.contracts.ExecutionRequest;
import com.engineeringplatform.generator.contracts.ExecutionRequest.ExecutionLogEvent;
import com.engineeringplatform.generator.contracts.ExecutionRequest.ExecutionState;
import com.engineeringplatform.generator.contracts.ToolRequest;
import com.engineeringplatform.generator.contracts.ToolResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Execution Controller（EP-WORK-009 §四/§十三/§十四/§十五/§十六/§十八）。
 *
 * 控制面职责：
 *  - 维护 attempt count（Agent 不得自行无限重试；超过 maxAttempts → FAILED/BLOCKED）
 *  - 维护 timeout / deadline（Agent Adapter 不得无限执行）
 *  - 所有 ToolRequest 经过 ToolPolicyEvaluator（ALLOW/DENY/REQUIRE_APPROVAL）
 *  - Approval 状态驱动（PENDING 不执行；APPROVED 可继续；REJECTED 拒绝）
 *  - 结构化 Execution Log（timestamp 属于 execution artifact）
 *  - 收集 ToolResult → Evidence → AgentExecutionResult
 *
 * Agent-neutral：不依赖任何具体 Agent Runtime SDK。
 */
public final class ExecutionController {

    /** Tool 执行器（测试用 FakeToolExecutor；未来真实执行器注入）。 */
    private final Function<ToolRequest, ToolResult> toolExecutor;
    private final Path workspaceRoot;
    private final List<ExecutionLogEvent> log = new ArrayList<>();
    private final Map<String, ToolRequest.Approval.ApprovalStatus> approvals = new HashMap<>();
    private long eventCounter = 0;
    private boolean cancelled = false;

    public ExecutionController(Path workspaceRoot) {
        this(workspaceRoot, new FakeToolExecutor());
    }

    public ExecutionController(Path workspaceRoot, Function<ToolRequest, ToolResult> toolExecutor) {
        this.workspaceRoot = workspaceRoot;
        this.toolExecutor = toolExecutor;
    }

    /** 请求批准（PENDING）。Agent 不得自己批准。已有审批状态（APPROVED/REJECTED）不得被重置。 */
    public ToolRequest.Approval requestApproval(ToolRequest tool) {
        ToolRequest.Approval.ApprovalStatus existing = approvals.get(tool.requestId());
        ToolRequest.Approval.ApprovalStatus status = existing == null
                ? ToolRequest.Approval.ApprovalStatus.PENDING
                : existing;
        approvals.putIfAbsent(tool.requestId(), ToolRequest.Approval.ApprovalStatus.PENDING);
        log(ExecutionLogEvent.EventType.APPROVAL_REQUIRED, tool.requestId(), Map.of("risk", "HIGH"));
        return new ToolRequest.Approval(
                "ap-" + tool.requestId(), tool.executionId(), tool.requestId(),
                "HIGH", "requires human approval", status);
    }

    /** 批准（由人工/控制面调用；Agent 无法调用本方法）。 */
    public void approve(String toolRequestId) {
        approvals.put(toolRequestId, ToolRequest.Approval.ApprovalStatus.APPROVED);
    }

    /** 拒绝批准。 */
    public void reject(String toolRequestId) {
        approvals.put(toolRequestId, ToolRequest.Approval.ApprovalStatus.REJECTED);
    }

    /** 取消执行。 */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * 执行 AgentExecutionRequest 的完整流程。
     * 返回 AgentExecutionResult（状态机：PLANNED → RUNNING → SUCCEEDED/FAILED/BLOCKED/TIMED_OUT/CANCELLED）。
     */
    public AgentExecutionResult execute(ExecutionRequest request, AgentAdapter adapter) {
        List<ToolResult> toolResults = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + request.timeoutSeconds() * 1000;

        log(ExecutionLogEvent.EventType.EXECUTION_STARTED, request.executionId(), Map.of("workItemId", request.workItemId()));

        // 13. Retry：attempt count 由 Controller 维护
        int attempts = 0;
        while (attempts < request.maxAttempts()) {
            attempts++;
            if (cancelled) {
                log(ExecutionLogEvent.EventType.EXECUTION_FAILED, request.executionId(), Map.of("reason", "cancelled"));
                return result(request, ExecutionState.CANCELLED, attempts, toolResults, evidence, errors,
                        "cancelled by controller");
            }
            if (System.currentTimeMillis() > deadline) {
                log(ExecutionLogEvent.EventType.EXECUTION_TIMED_OUT, request.executionId(),
                        Map.of("timeoutSeconds", request.timeoutSeconds()));
                errors.add("timed out after " + request.timeoutSeconds() + "s");
                return result(request, ExecutionState.TIMED_OUT, attempts, toolResults, evidence, errors,
                        "timed out");
            }

            // 本轮尝试：跑 adapter（adapter 内部逐 tool 请求经 policy + guard）
            AgentExecutionResult attemptResult = runAttempt(request, adapter, toolResults, evidence, errors);
            if (attemptResult.status() == ExecutionState.SUCCEEDED) {
                log(ExecutionLogEvent.EventType.EXECUTION_COMPLETED, request.executionId(),
                        Map.of("attempts", attempts));
                return new AgentExecutionResult(request.executionId(), ExecutionState.SUCCEEDED,
                        attempts, toolResults, request.expectedArtifacts(), evidence, errors, "succeeded");
            }
            if (attemptResult.status() == ExecutionState.BLOCKED) {
                // 不可重试（policy/approval 阻塞）
                return attemptResult;
            }
            if (attemptResult.status() == ExecutionState.TIMED_OUT
                    || attemptResult.status() == ExecutionState.CANCELLED) {
                // 超时/取消不可重试：透传状态（不得吞成 FAILED）
                return attemptResult;
            }
            // FAILED → 允许重试（attempt count 受控）
        }
        log(ExecutionLogEvent.EventType.EXECUTION_FAILED, request.executionId(),
                Map.of("reason", "maxAttempts exceeded", "maxAttempts", request.maxAttempts()));
        errors.add("max attempts exceeded: " + request.maxAttempts());
        return result(request, ExecutionState.FAILED, attempts, toolResults, evidence, errors,
                "max attempts exceeded");
    }

    private AgentExecutionResult runAttempt(ExecutionRequest request, AgentAdapter adapter,
                                            List<ToolResult> toolResults, List<Evidence> evidence,
                                            List<String> errors) {
        // Adapter 负责提出 ToolRequest（agent-neutral 抽象）；Controller 对每个 ToolRequest 做 policy。
        // 本轮采用简化模型：adapter.execute 返回结果；Tool 请求经由 ToolPolicyEvaluator 的
        // 判定在 adapter 与 controller 之间通过工具执行器体现（Fake 场景）。
        AgentExecutionResult raw = adapter.execute(request);
        // 状态归一化（adapter 无权决定 ACCEPTED/DONE；这里只反映 execution 状态）
        ExecutionState state = normalize(raw.status());
        if (state == ExecutionState.SUCCEEDED) {
            toolResults.addAll(raw.toolResults() == null ? List.of() : raw.toolResults());
            evidence.addAll(raw.evidence() == null ? List.of() : raw.evidence());
            return new AgentExecutionResult(request.executionId(), ExecutionState.SUCCEEDED,
                    raw.attempts(), toolResults, raw.artifacts(), evidence, errors, raw.summary());
        }
        if (state == ExecutionState.BLOCKED) {
            errors.addAll(raw.errors() == null ? List.of() : raw.errors());
            return new AgentExecutionResult(request.executionId(), ExecutionState.BLOCKED,
                    raw.attempts(), toolResults, raw.artifacts(), evidence, errors, raw.summary());
        }
        if (state == ExecutionState.TIMED_OUT || state == ExecutionState.CANCELLED) {
            // 超时/取消不得被吞成 FAILED：透传原始状态（不可重试）
            errors.addAll(raw.errors() == null ? List.of() : raw.errors());
            return new AgentExecutionResult(request.executionId(), state,
                    raw.attempts(), toolResults, raw.artifacts(), evidence, errors, raw.summary());
        }
        errors.addAll(raw.errors() == null ? List.of() : raw.errors());
        return new AgentExecutionResult(request.executionId(), ExecutionState.FAILED,
                raw.attempts(), toolResults, raw.artifacts(), evidence, errors, raw.summary());
    }

    /** Adapter 可能报告 DONE；controller 归一化为 execution 状态（SUCCEEDED），不触碰 WorkItem ACCEPTED。 */
    private static ExecutionState normalize(ExecutionState raw) {
        return switch (raw) {
            case SUCCEEDED, RUNNING, PLANNED -> ExecutionState.SUCCEEDED;
            case BLOCKED -> ExecutionState.BLOCKED;
            case TIMED_OUT -> ExecutionState.TIMED_OUT;
            case CANCELLED -> ExecutionState.CANCELLED;
            case FAILED -> ExecutionState.FAILED;
        };
    }

    /**
     * 平台控制面工具入口：Agent 提出 ToolRequest，必须经 Policy 判定后才可执行。
     * 返回 ToolResult（fake executor 执行或 DENY 结果）。
     */
    public ToolResult submitTool(ExecutionRequest request, ToolRequest tool) {
        log(ExecutionLogEvent.EventType.TOOL_REQUESTED, tool.requestId(), Map.of("capability", tool.capability().name()));
        ToolRequest.PolicyResult policy = ToolPolicyEvaluator.evaluate(request, tool, workspaceRoot);
        switch (policy.decision()) {
            case ALLOW -> {
                log(ExecutionLogEvent.EventType.TOOL_ALLOWED, tool.requestId(),
                        Map.of("operation", tool.operation()));
                ToolResult r = toolExecutor.apply(tool);
                log(r.status() == ToolResult.ToolResultStatus.SUCCESS
                                ? ExecutionLogEvent.EventType.TOOL_COMPLETED
                                : ExecutionLogEvent.EventType.TOOL_FAILED,
                        tool.requestId(), Map.of("status", r.status().name()));
                return r;
            }
            case REQUIRE_APPROVAL -> {
                requestApproval(tool);
                ToolRequest.Approval.ApprovalStatus status = approvals.get(tool.requestId());
                if (status == ToolRequest.Approval.ApprovalStatus.APPROVED) {
                    log(ExecutionLogEvent.EventType.TOOL_ALLOWED, tool.requestId(),
                            Map.of("via", "approval", "operation", tool.operation()));
                    ToolResult r = toolExecutor.apply(tool);
                    log(r.status() == ToolResult.ToolResultStatus.SUCCESS
                                    ? ExecutionLogEvent.EventType.TOOL_COMPLETED
                                    : ExecutionLogEvent.EventType.TOOL_FAILED,
                            tool.requestId(), Map.of("status", r.status().name()));
                    return r;
                }
                if (status == ToolRequest.Approval.ApprovalStatus.REJECTED) {
                    log(ExecutionLogEvent.EventType.TOOL_DENIED, tool.requestId(), Map.of("reason", "approval rejected"));
                    return new ToolResult(tool.requestId(), ToolResult.ToolResultStatus.BLOCKED,
                            null, List.of(), "approval rejected", 0, Map.of());
                }
                // PENDING / EXPIRED → 不执行
                log(ExecutionLogEvent.EventType.TOOL_DENIED, tool.requestId(),
                        Map.of("reason", "approval pending: " + status));
                return new ToolResult(tool.requestId(), ToolResult.ToolResultStatus.BLOCKED,
                        null, List.of(), "approval pending (" + status + ")", 0, Map.of());
            }
            default -> {
                log(ExecutionLogEvent.EventType.TOOL_DENIED, tool.requestId(),
                        Map.of("reason", policy.reason()));
                return new ToolResult(tool.requestId(), ToolResult.ToolResultStatus.BLOCKED,
                        null, List.of(), "denied: " + policy.reason(), 0, Map.of());
            }
        }
    }

    public List<ExecutionLogEvent> log() {
        return List.copyOf(log);
    }

    private void log(ExecutionLogEvent.EventType type, String reference, Map<String, Object> metadata) {
        log.add(new ExecutionLogEvent("ev-" + (++eventCounter), "exec", System.currentTimeMillis(),
                type, reference, metadata));
    }

    private static AgentExecutionResult result(ExecutionRequest request, ExecutionState state,
                                               int attempts, List<ToolResult> toolResults,
                                               List<Evidence> evidence, List<String> errors, String summary) {
        return new AgentExecutionResult(request.executionId(), state, attempts,
                toolResults, request.expectedArtifacts(), evidence, errors, summary);
    }
}
