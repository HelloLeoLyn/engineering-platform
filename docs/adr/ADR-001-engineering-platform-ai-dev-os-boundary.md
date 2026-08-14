# ADR-001 — Engineering Platform and AI Dev OS Responsibility Boundary

- **Status**: ACCEPTED
- **Date**: 2026-08-14
- **Baseline**: v0.1.0（main b493057）
- **Type**: Post-V0.1 Architecture Decision（为 V0.2 做前置决策）

## Context

V0.1 探索阶段在 Engineering Platform 内实现了部分本应属于 AI 研发控制平面的能力
（WorkItem / EngineeringPlan / ImplementationTasks / TestPlan / TestRun / Agent Execution /
Tool Guard / Approval / Retry / Timeout / Execution Log）。

随着 AI Dev OS（OpenClaw / Codex Adapter / Agent Runtime / MCP / Browser Automation 等）
成为独立研发控制平面，必须正式界定两个系统的长期职责边界，避免：

- Engineering Platform 被误认为 AI Agent orchestrator / AI Dev OS replacement；
- AI Dev OS 域能力在 Engineering Platform 中无限扩张；
- 两种 Verification（Engineering Conformance vs Development Task）职责混淆。

## Decision

正式确定系统定位：

| 系统 | 一句话定位 | 回答的问题 |
|---|---|---|
| Engineering Platform | 公司级可复用软件工程能力底座 | **"拿什么开发"** |
| AI Dev OS | AI 软件研发控制平面 | **"怎么开发"** |

Engineering Platform 将 Engineering Standards / Modules / Capabilities / Providers /
Templates / Generator / Engineering Rules 沉淀为机器可读、可组合、可解析、可生成、可验证的
工程资产，使新项目优先通过 **复用 + 配置 + 生成** 获得标准工程能力，而非从头开发。

AI Dev OS 负责 Requirement Planning / WorkItem 与 Task Planning / Task Graph / Agent
Orchestration / Agent Runtime / OpenClaw 与 Codex Adapter / Tool 与 MCP Control /
Execution Control / Retry / Timeout / Remote Approval / Privileged Operation Approval /
Browser Automation / Development Task Verification / Human Acceptance。

## Engineering Platform Scope（IN SCOPE）

1. **Engineering Standards** — 平台标准、架构规则、工程规范
2. **Reusable Engineering Assets** — Modules / Capabilities / Providers / Templates / Starters / Libraries / Engineering Guides
3. **Project Modeling** — Platform Manifest / Project Manifest / Module Manifest / Provider Manifest / Registry
4. **Resolution** — Resolver / EffectiveProjectModel / Provenance / Compatibility / Dependency Resolution
5. **Generation** — GenerationPlan / Generator / Generator Executor / ChangeManifest / Rollback / Transaction Safety
6. **Engineering Conformance** — Manifest Validation / Architecture Conformance / Dependency Validation / Capability & Provider Compatibility / Generated Structure Validation / Platform Standard Validation

## AI Dev OS Scope（OUT OF SCOPE for Engineering Platform）

Requirement Planning、WorkItem orchestration、Engineering task planning、Agent scheduling、
Agent selection、OpenClaw Adapter、Codex Adapter、Agent Runtime、MCP orchestration、
General Tool Control、Remote Approval、sudo / privileged approval、Retry orchestration、
Timeout orchestration、Browser Automation、Development Task Acceptance、Human Approval、
AI workflow orchestration —— 以上均属 AI Dev OS。

## Dependency Direction

```
AI Dev OS          ──uses──▶   Engineering Platform
Engineering Platform ──provides engineering assets──▶   Business Project
AI Dev OS          ──drives development──▶   Business Project

禁止：
Engineering Platform ──depends on──▶   AI Dev OS
```

- AI Dev OS **可以调用** Engineering Platform。
- Engineering Platform **不得反向依赖** AI Dev OS。
- Engineering Platform **不得拥有或调度具体 AI Agent**。
- OpenClaw / Codex / Future Agent 属于 AI Dev OS execution/runtime domain。
- Engineering Platform 只提供工程能力、工程事实和工程操作能力。

## Verification Boundary

必须区分两种 Verification：

| 维度 | Engineering Conformance Verification（Engineering Platform） | Development Task Verification（AI Dev OS） |
|---|---|---|
| 负责 | Manifest correctness / Architecture rules / Dependency rules / Capability compatibility / Provider compatibility / Generated structure / Platform standards | Requirement satisfied / Agent execution result / Build result / Test result / Browser test / Execution evidence / Task acceptance / Human approval |

不得继续把两种 Verification 作为同一个平台职责。

## V0.1 Legacy Decision

V0.1 探索阶段实现的以下能力，虽属 AI Dev OS domain，但已是 **v0.1.0 stable baseline**：

WorkItem / EngineeringPlan / ImplementationTasks / TestPlan / TestRun /
Task-oriented Verification / Agent Execution / Tool Guard / Approval / Retry / Timeout / Execution Log

本轮决策：

- **不得删除、不得迁移、不得重构、不得破坏兼容性**；
- 正式标记为 **V0.1 Exploration Legacy**；
- V0.2 Implementation 前必须逐项做 Ownership Review，每项只能得到
  **KEEP / SPLIT / DEPRECATE / MOVE_TO_AI_DEV_OS** 之一；
- Ownership Review 完成之前，**不得继续扩展这些 Legacy capability**；
- 尤其禁止在 Engineering Platform 中继续新增：OpenClaw Adapter、Codex Adapter、
  Remote Approval、Privileged Operation Approval、Browser Agent Runtime、Agent Scheduler。

## Architecture Decision Test（未来能力准入）

新增 Engineering Platform capability 必须回答：

> "这个能力是否直接增强以下至少一个领域？"
> Engineering Standards / Reusable Assets / Project Modeling / Resolution / Generation / Engineering Conformance

- 是 → 可进入 Engineering Platform；
- 否，且主要解决"AI 如何规划 / 执行 / 使用工具 / 审批 / 测试任务 / 完成研发流程" → 默认属于 AI Dev OS。

## Consequences

**正面**：
- 两个系统的职责可长期稳定演进，避免 scope 蔓延；
- 新增能力有明确准入测试，减少架构漂移；
- Verification 语义清晰，避免"工程合规"与"任务验收"混为一谈。

**代价**：
- V0.1 Exploration Legacy 需要在 V0.2 前完成 Ownership Review（KEEP/SPLIT/DEPRECATE/MOVE_TO_AI_DEV_OS）；
- 未来新增 Engineering Platform capability 需通过 Architecture Decision Test，流程成本略增。

## V0.2 Entry Gate

V0.2 Implementation 的前置条件：

1. 本 ADR 已 ACCEPTED（本文档）；
2. V0.1 Exploration Legacy 逐项 Ownership Review 完成，每项得到 KEEP / SPLIT / DEPRECATE / MOVE_TO_AI_DEV_OS；
3. 边界落地文档（docs/architecture/system-boundary.md）与 README 定位已同步；
4. 任何 MOVE_TO_AI_DEV_OS 项在 AI Dev OS 侧有明确承接方后再迁移（不在 Engineering Platform 内继续扩展）。
