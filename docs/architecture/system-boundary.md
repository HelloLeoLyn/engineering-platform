# Engineering Platform System Boundary

> 本文档描述 **当前系统边界是什么**（operative boundary）。
> "为什么这样决定"见 [ADR-001](../adr/ADR-001-engineering-platform-ai-dev-os-boundary.md)。
> 基线：v0.1.0（main b493057）｜ 更新：2026-08-14

## 一句话定位

| 系统 | 定位 | 回答的问题 |
|---|---|---|
| Engineering Platform | 公司级可复用软件工程能力底座 | **拿什么开发** |
| AI Dev OS | AI 软件研发控制平面 | **怎么开发** |

Engineering Platform 把 Engineering Standards / Modules / Capabilities / Providers /
Templates / Generator / Engineering Rules 沉淀为**机器可读、可组合、可解析、可生成、可验证**的
工程资产，让新项目优先通过 **复用 + 配置 + 生成** 获得标准工程能力。

## 系统关系

```
AI Dev OS          ──uses──▶   Engineering Platform
Engineering Platform ──provides engineering assets──▶   Business Project
AI Dev OS          ──drives development──▶   Business Project
```

- AI Dev OS **可以调用** Engineering Platform。
- Engineering Platform **不得反向依赖** AI Dev OS。
- Engineering Platform **不得拥有或调度具体 AI Agent**；OpenClaw / Codex / Future Agent
  属于 AI Dev OS execution/runtime domain。
- Engineering Platform 只提供工程能力、工程事实和工程操作能力。

## 职责矩阵

| Capability | Engineering Platform | AI Dev OS | Notes |
|---|---|---|---|
| Engineering Standards | ✅ IN SCOPE | — | 平台标准/架构规则/工程规范 |
| Reusable Assets（Modules/Capabilities/Providers/Templates/Starters/Libraries/Guides） | ✅ IN SCOPE | — | 可复用工程资产 |
| Project Modeling（Platform/Project/Module/Provider Manifest + Registry） | ✅ IN SCOPE | — | 机器可读工程建模 |
| Resolution（Resolver/EPM/Provenance/Compatibility/Dependency） | ✅ IN SCOPE | — | 纯计算 |
| Generation（GenerationPlan/Generator/Executor/ChangeManifest/Rollback/Transaction） | ✅ IN SCOPE | — | PLAN BEFORE WRITE |
| Engineering Conformance（Manifest/Architecture/Dependency/Compatibility/Structure/Standard Validation） | ✅ IN SCOPE | — | 工程合规验证 |
| Requirement Planning | — | ✅ | 需求规划 |
| WorkItem / Task Planning | 🟡 V0.1 Legacy | ✅ | Ownership Review 待定 |
| Task Graph | 🟡 V0.1 Legacy | ✅ | Ownership Review 待定 |
| Agent Orchestration / Scheduling / Selection | — | ✅ | 属 AI Dev OS |
| Agent Runtime / OpenClaw Adapter / Codex Adapter | — | ✅ | execution/runtime domain |
| Tool / MCP Control | 🟡 V0.1 Legacy（Tool Guard） | ✅ | Ownership Review 待定 |
| Execution Control / Retry / Timeout | 🟡 V0.1 Legacy | ✅ | Ownership Review 待定 |
| Remote / Privileged Operation Approval | — | ✅ | 禁止在 EP 新增 |
| Browser Automation | — | ✅ | 禁止在 EP 新增 |
| Development Task Verification（Requirement/Build/Test/Browser/Evidence/Acceptance/Human Approval） | — | ✅ | 与 EP 的 Engineering Conformance 区分 |
| EngineeringPlan / ImplementationTasks | 🟡 V0.1 Legacy | ✅ | Ownership Review 待定 |
| TestPlan / TestRun | 🟡 V0.1 Legacy | ✅ | Ownership Review 待定 |
| Execution Log | 🟡 V0.1 Legacy | ✅ | Ownership Review 待定 |

图例：✅ 正式职责 ｜ — 不属于该系统 ｜ 🟡 V0.1 Exploration Legacy（见下）

## Verification Boundary

| | Engineering Platform | AI Dev OS |
|---|---|---|
| 验证类型 | **Engineering Conformance Verification** | **Development Task Verification** |
| 内容 | Manifest correctness / Architecture rules / Dependency rules / Capability compatibility / Provider compatibility / Generated structure / Platform standards | Requirement satisfied / Agent execution result / Build result / Test result / Browser test / Execution evidence / Task acceptance / Human approval |

两种 Verification 是**两个平台的职责**，不得合并为同一平台职责。

## V0.1 Exploration Legacy（冻结区）

以下能力在 V0.1 探索阶段实现于 Engineering Platform，属于 **v0.1.0 stable baseline**：

WorkItem、EngineeringPlan、ImplementationTasks、TestPlan、TestRun、Task-oriented Verification、
Agent Execution、Tool Guard、Approval、Retry、Timeout、Execution Log

处理规则（本 ADR ACCEPTED 起生效）：

1. 不删除、不迁移、不重构、不破坏兼容性；
2. 不继续扩展（冻结新增）；
3. V0.2 Implementation 前逐项 Ownership Review，每项仅允许
   **KEEP / SPLIT / DEPRECATE / MOVE_TO_AI_DEV_OS**；
4. 禁止在 Engineering Platform 继续新增：OpenClaw Adapter、Codex Adapter、
   Remote Approval、Privileged Operation Approval、Browser Agent Runtime、Agent Scheduler。

## Architecture Decision Test（新增能力准入）

新增 Engineering Platform capability 必须增强以下至少一个领域：

- Engineering Standards
- Reusable Assets
- Project Modeling
- Resolution
- Generation
- Engineering Conformance

否则默认不进入 Engineering Platform；若主要解决"AI 如何规划/执行/使用工具/审批/测试任务/
完成研发流程"，默认属于 AI Dev OS。

## V0.2 Entry Gate

1. ADR-001 ACCEPTED ✅（2026-08-14）
2. V0.1 Exploration Legacy 逐项 Ownership Review 完成（KEEP/SPLIT/DEPRECATE/MOVE_TO_AI_DEV_OS）✅
   → [v0.1-legacy-ownership-review.md](v0.1-legacy-ownership-review.md)（12/12 明确，ENTRY_GATE=PASS）
3. 本文档与 README 定位同步
4. MOVE_TO_AI_DEV_OS 项由 AI Dev OS 侧承接后迁移
