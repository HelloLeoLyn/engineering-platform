package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * AgentAdapter 抽象（EP-WORK-009 §二 Agent-neutral）。
 *
 * Platform Core 不得依赖 OpenClaw SDK / Codex SDK / Claude SDK / 任何具体 Agent Runtime。
 * Agent Adapter 负责：平台 Contract ↔ 具体 Agent Runtime 转换。
 * 本轮不实现正式 OpenClaw/Codex Adapter；测试使用 FakeAgentAdapter。
 */
public interface AgentAdapter {

    /**
     * 执行一个 ExecutionRequest，返回 AgentExecutionResult。
     * Adapter 不得自行扩大 scope / tool permission / timeout / retry；
     * 这些由 ExecutionController 以不可变 ExecutionRequest 传入。
     */
    AgentExecutionResult execute(ExecutionRequest request);
}
