package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.AgentAdapter;
import com.engineeringplatform.generator.contracts.AgentExecutionResult;
import com.engineeringplatform.generator.contracts.Evidence;
import com.engineeringplatform.generator.contracts.ExecutionRequest;
import com.engineeringplatform.generator.contracts.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake Agent Adapter（EP-WORK-009 §二十）。
 *
 * 测试专用：支持脚本化 成功/失败/请求 tool/请求 forbidden tool/超时/重试。
 * 不接真实 OpenClaw/Codex。
 *
 * 边界：Adapter 收到的 ExecutionRequest 是不可变的；它无法修改 scope/capability/retry/timeout，
 * 也无法访问 Approval 机制（approve 只在 ExecutionController）。
 */
public final class FakeAgentAdapter implements AgentAdapter {

    /** 脚本模式。 */
    public enum Script {
        SUCCESS,          // 立即成功
        FAILED,           // 失败
        BLOCKED,          // 策略/审批阻塞
        TIMED_OUT,        // 超时
        FAIL_FIRST_N      // 前 N 次失败，之后成功（测试 retry）
    }

    private final Script script;
    private final int failFirstCount;
    private int callCount = 0;

    public FakeAgentAdapter(Script script) {
        this(script, 0);
    }

    public FakeAgentAdapter(Script script, int failFirstCount) {
        this.script = script;
        this.failFirstCount = failFirstCount;
    }

    public int callCount() {
        return callCount;
    }

    @Override
    public AgentExecutionResult execute(ExecutionRequest request) {
        callCount++;
        switch (script) {
            case SUCCESS -> {
                return new AgentExecutionResult(request.executionId(),
                        ExecutionRequest.ExecutionState.SUCCEEDED, callCount,
                        List.of(), request.expectedArtifacts(),
                        List.of(new Evidence("ev-" + request.executionId() + "-" + callCount,
                                Evidence.EvidenceType.COMMAND_RESULT, "fake-ref", java.util.Map.of())),
                        List.of(), "fake success");
            }
            case FAILED -> {
                return new AgentExecutionResult(request.executionId(),
                        ExecutionRequest.ExecutionState.FAILED, callCount,
                        List.of(), request.expectedArtifacts(), List.of(),
                        List.of("fake failure"), "fake failed");
            }
            case BLOCKED -> {
                return new AgentExecutionResult(request.executionId(),
                        ExecutionRequest.ExecutionState.BLOCKED, callCount,
                        List.of(), request.expectedArtifacts(), List.of(),
                        List.of("fake blocked"), "fake blocked");
            }
            case TIMED_OUT -> {
                return new AgentExecutionResult(request.executionId(),
                        ExecutionRequest.ExecutionState.TIMED_OUT, callCount,
                        List.of(), request.expectedArtifacts(), List.of(),
                        List.of("fake timeout"), "fake timed out");
            }
            case FAIL_FIRST_N -> {
                if (callCount <= failFirstCount) {
                    return new AgentExecutionResult(request.executionId(),
                            ExecutionRequest.ExecutionState.FAILED, callCount,
                            List.of(), request.expectedArtifacts(), List.of(),
                            List.of("fake transient failure #" + callCount), "fake failed");
                }
                return new AgentExecutionResult(request.executionId(),
                        ExecutionRequest.ExecutionState.SUCCEEDED, callCount,
                        List.of(), request.expectedArtifacts(),
                        List.of(new Evidence("ev-" + request.executionId() + "-" + callCount,
                                Evidence.EvidenceType.COMMAND_RESULT, "fake-ref", java.util.Map.of())),
                        List.of(), "fake success after retries");
            }
            default -> {
                return new AgentExecutionResult(request.executionId(),
                        ExecutionRequest.ExecutionState.FAILED, callCount,
                        List.of(), request.expectedArtifacts(), List.of(),
                        List.of("unknown script"), "unknown");
            }
        }
    }
}
