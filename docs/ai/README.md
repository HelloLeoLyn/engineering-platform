# AI Engineering — Engineering Platform V0.1

## OpenClaw 定位（必须写清楚）

**OpenClaw 当前只是 Engineering Platform 的临时开发执行工具。**

- Engineering Platform V0.1 **不依赖 OpenClaw**：Platform Core（generator-contracts / generator-core）
  零依赖 OpenClaw SDK / Codex SDK / Claude SDK / 任何具体 Agent Runtime SDK。
- 009 的 `AgentAdapter` 是 **Agent-neutral contract**：`execute(ExecutionRequest) → AgentExecutionResult`。
- 未来可以接入：
  - `OpenClawAdapter`
  - `CodexAdapter`
  - 其他 Adapter
- 但 Adapter 都不是 Platform Core dependency——它们是可插拔边界实现。

## AI Engineering Contract（V0.7 §14 [DECIDED]）

- AI Engineering 采用 Work Item 驱动，不是自由聊天式协作
- Agent 之间交换结构化 Artifact：engineering-plan / generation-plan / change-manifest / test-plan / test-run / verification-report
- 任何实际修改超出 Approved Scope 必须触发 SCOPE EXPANSION
- Retry 必须按失败类型受限；Human Approval 按 Risk Profile 决定
- Verification Engine 是正式测试结论唯一来源；Quality Gate 决定 READY/BLOCKED

## 角色边界（V0.7 §14）

| 角色 | 职责 |
|---|---|
| Hermes | 分析 + Engineering Plan（不大量写代码） |
| Generator | 确定性 Scaffold/Metadata/Code Skeleton |
| Codex | 语义实现（受 AGENTS.md / Ownership / Architecture / Scope 约束） |
| Playwright | 稳定 Browser Regression Provider |
| OpenClaw | 探索性 GUI / 环境诊断 / 未知问题复现（非 Platform Core） |
| Test Planner | 决定 WHAT |
| Execution Planner | 决定 HOW |
| Verification Engine | 正式测试结论唯一来源 |

## WorkItem 状态机（V0.7 §23 [DECIDED]）

```
NEW → ANALYZING → PLANNED → APPROVED → IMPLEMENTING → IMPLEMENTED → VERIFYING → READY → DONE
异常：BLOCKED / FAILED / REJECTED / CANCELLED / REOPENED
验收终态：ACCEPTED（只能来自 Verification + Human Approval）
```

- IMPLEMENTED ≠ READY/DONE；代码完成不等于验证通过
- DONE 只能由 Workflow/Quality/Approval Gate 计算得出；**Agent 不能直接自封 DONE**

## 当前实现状态（V0.1）

- 009 已实现 Agent-neutral Control Plane（ExecutionRequest / Tool Guards / Approval / Retry / Timeout / Log）
- 测试使用 FakeAgentAdapter / FakeToolExecutor；**未接入任何真实 Agent Runtime**
- Browser capability 已定义（BROWSER_READ/WRITE）但 **V1 一律 DENY，未实现**
