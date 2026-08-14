# Architecture Overview — Engineering Platform V0.1

## 定位

Engineering Platform 是 Java/Vue 工程底座 + Generator（V0.7 §7 [DECIDED]）。
AI Dev OS 与 Engineering Platform 是两个独立项目；Business Projects 复用 Engineering Platform。

**系统边界**：Engineering Platform = 拿什么开发（可复用工程能力底座）；AI Dev OS = 怎么开发（AI 研发控制平面）。
详见 [System Boundary](system-boundary.md) 与 [ADR-001](../adr/ADR-001-engineering-platform-ai-dev-os-boundary.md)。

## 核心架构

```
Platform/Project/Module/Provider Manifest (YAML)
  → Manifest Validation (Python contract/build-time + Java runtime boundary)
  → Registry (capability/provider/module/error/...)
  → Resolver Pipeline (13 steps) → EffectiveProjectModel (EPM)
  → GenerationPlanner → GenerationPlan (PLAN BEFORE WRITE)
  → DryRunner → GeneratorExecutor → Staging → Apply → ChangeManifest/Transaction
  → WorkItem → EngineeringPlan → ImplementationTasks (DAG)
  → Agent Execution (AgentAdapter + ExecutionController + Tool Guards)
  → Execution Evidence → TestPlan/TestRun → VerificationEngine → VerificationReport
  → Acceptance Decision (ACCEPT / REJECT / BLOCKED)
```

## V0.2 资产驱动链路（Reusable Engineering Asset Platform）

```
project.yaml → capabilities/ + providers/（Asset Contract V1）→ AssetAwareResolver（依赖闭包+兼容）
→ EffectiveProjectModel → AssetProjectGenerator（模板渲染+依赖装配）→ GenerationPlan → GeneratorExecutor
→ 真实 Spring Boot 项目 → ConformanceValidator（六类规则）→ ConformanceResult → mvn test
```

详细：docs/guides/asset-guide.md ｜ generation-guide.md ｜ conformance-guide.md ｜ release/V0.2-RELEASE-CHECKLIST.md

## 子系统划分

| 子系统 | 目录 | 职责 |
|---|---|---|
| Manifest Contract | `generator/schemas/` | 语言无关 Schema（YAML） |
| Registry | `registry/` | 能力/提供方/模块/错误 等 8 类索引 |
| Contracts (Java) | `generator/generator-contracts/` | 契约 Java 模型（无业务逻辑） |
| Core (Java) | `generator/generator-core/` | Resolver / Planner / Executor / Work Model / Verification / Agent Control |
| Python Validators | `generator/scripts/` | build-time Contract 校验 |
| Backend/Frontend | `backend/` `frontend/` | V0.1 占位（本仓库 V0.1 重心在 Generator 子系统） |

## 核心原则

- Reuse > Configure > Generate > AI Code（V0.7 §1）
- PLAN BEFORE WRITE：任何项目文件修改之前必须先存在 GenerationPlan（V0.7 §20）
- Resolver 是纯计算（V0.7 §18）：不修改源 Manifest，无副作用
- Ownership 四级：GENERATED / MANAGED / USER_OWNED / IMMUTABLE（V0.7 §13）
- Agent 可以提出操作请求，但 Agent 本身不能决定自己拥有什么权限（EP-WORK-009）
- Verification Engine 是正式测试结论唯一来源；Agent 不能自封 DONE/ACCEPTED（V0.7 §14/§23）

## 依赖方向

`generator-contracts` ← `generator-core`（37 个文件依赖 contracts；contracts 零依赖 core）。
禁止反向依赖。Maven：`generator/pom.xml`（父）→ generator-contracts / generator-core。

## OpenClaw 定位

OpenClaw 当前只是 Engineering Platform 的**临时开发执行工具**，不是 Platform Core 依赖。
009 的 `AgentAdapter` 是 Agent-neutral contract；未来可接入 OpenClawAdapter / CodexAdapter /
其他 Adapter，但都不是 Platform Core dependency（详见 docs/ai/README.md）。
