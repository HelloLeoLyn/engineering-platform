# Engineering Platform

Engineering Platform 是 Java/Vue 工程底座 + Generator（AI 辅助工程生成）系统。
基于《Engineering Platform 设计决策记录 V0.7》实现，目标是让
"任务是什么、允许改什么、做了什么、如何证明、是否验收通过"全部成为机器可读 Contract。

## Engineering Platform 是什么

**定位：Engineering Platform 是 reusable engineering capability platform（可复用软件工程能力底座）。**
它沉淀 Engineering Standards / Modules / Capabilities / Providers / Templates / Generator / Engineering Rules
为机器可读、可组合、可解析、可生成、可验证的工程资产，让新项目优先通过 **复用 + 配置 + 生成** 获得标准工程能力。

Engineering Platform **不是**：AI Agent orchestrator、OpenClaw platform、AI Dev OS replacement。
AI 研发控制（Requirement/WorkItem/Agent Runtime/Tool Control/Approval/任务验收）属于 AI Dev OS。
详见 [ADR-001](docs/adr/ADR-001-engineering-platform-ai-dev-os-boundary.md) 与 [System Boundary](docs/architecture/system-boundary.md)。

- 工程底座：Java 25 + Spring Boot + Vue 3 的工程骨架与规范（backend/ frontend/）
- Generator 子系统：Manifest → Registry → Resolver → GenerationPlan → Executor → Verification 的
  确定性、可验证工程生成链路
- AI Engineering Control Plane：WorkItem / EngineeringPlan / ImplementationTasks / Agent Execution /
  Tool Guard / VerificationReport（Agent-neutral，不依赖任何具体 Agent Runtime）

## V0.2 能做什么（Reusable Engineering Asset Platform）

从一份 project.yaml 生成真实可编译的 Spring Boot Reference 项目：

```
project.yaml → 真实 Assets（capabilities/ + providers/）→ Resolver/EPM
→ GenerationPlan → GeneratorExecutor → 真实 Spring Boot 项目
→ Engineering Conformance → mvn test = BUILD SUCCESS
```

- **Asset Contract V1**：MODULE/CAPABILITY/PROVIDER/TEMPLATE 机器可读资产（engineering-asset.schema.yaml）
- **Reference Asset Set**：web/validation/exception-handling/logging/persistence/audit + mybatis-plus provider
- **Asset-aware Resolver**：真实资产依赖闭包（required 自动加入、环/缺失/兼容检查）→ EffectiveProjectModel
- **Asset-driven Generation**：资产模板渲染 + Maven 依赖装配（去重/冲突检测）→ 真实项目
- **Engineering Conformance**：technology/structure/dependency/config/provider/asset 六类规则 → ConformanceResult
- 指南：[Asset Guide](docs/guides/asset-guide.md) ｜ [Generation Guide](docs/guides/generation-guide.md) ｜ [Conformance Guide](docs/guides/conformance-guide.md)
- 发布状态：[V0.2 Release Checklist](docs/release/V0.2-RELEASE-CHECKLIST.md)

## V0.1 能做什么

- 解析 Platform/Project/Module/Provider Manifest（13 步 Resolver Pipeline → EffectiveProjectModel）
- 生成 GenerationPlan（PLAN BEFORE WRITE）→ Dry Run → 事务式 Executor（Staging/Apply/Rollback）
- 表达 WorkItem Scope / Acceptance Criteria / EngineeringPlan / Task DAG
- 执行 Agent-neutral Tool Guard（Filesystem / Git / Shell + Least Privilege + Approval）
- 计算机械验收（BUILD / TEST / FILE_SCOPE / ARTIFACT；MANUAL → PENDING_MANUAL）
- 6 个 Python Contract Validator 全绿（build-time）+ Java Runtime 静态编译检查

## 核心架构

```
Manifest (YAML) → Registry → Resolver (13 步) → EffectiveProjectModel
  → GenerationPlan → DryRun → Executor → ChangeManifest/Transaction
  → WorkItem → EngineeringPlan → ImplementationTasks
  → Agent Execution (AgentAdapter + Tool Guards) → Evidence
  → TestPlan/TestRun → VerificationEngine → VerificationReport → Acceptance Decision
```

## 模块结构

| 目录 | 说明 |
|---|---|
| `generator/schemas/` | 语言无关 Contract（YAML Schema + valid/invalid fixtures） |
| `generator/generator-contracts/` | Contract Java 模型（无业务逻辑） |
| `generator/generator-core/` | Resolver / Planner / Executor / Work Model / Verification / Agent Control |
| `generator/scripts/` | Python Contract Validators（build-time） |
| `registry/` | 能力/提供方/模块/错误等 8 类索引 |
| `backend/` `frontend/` | V0.1 工程骨架（占位/演进中） |
| `docs/` | 架构/指南/标准/AI/ADR + release 文档 |
| `tests/fixtures/e2e/minimal-project/` | E2E 最小项目 fixture |

## Quick Start

```bash
# 统一校验入口（Python validators + Java 静态编译检查）
./scripts/validate.sh

# 仅 Python Contract 校验
./scripts/validate.sh --python

# 仅 Java 静态编译检查（本机 JDK 21 辅助；正式测试需 JDK 25）
./scripts/validate.sh --java
```

## Validation

6 个 Python Contract Validator（全部必须 exit 0）：

```bash
python3 generator/scripts/validate-manifest.py --all              # Manifest 9/9
python3 generator/scripts/validate-registry.py --all              # Registry 21/21
python3 generator/scripts/validate-resolver-contracts.py          # Resolver 11/11
python3 generator/scripts/validate-generator-contracts.py         # Generator 9/9
python3 generator/scripts/validate-engineering-work-contracts.py # Work 15/15
python3 generator/scripts/validate-agent-execution-contracts.py   # Agent 9/9
```

## Build/Test

- 正式技术基线：**Java 25**（禁止降级）
- CI（.github/workflows/ci.yml）：Python validators + `mvn -f generator/pom.xml test`（Java 25）
- 本机 JDK 21 为已知环境限制：`JDK25_BUILD_GATE = PENDING`；
  静态编译（javac --release 21）PASS，但 `TESTS_EXECUTED = NO` / `TESTS_PASSED = NOT_VERIFIED`
- 测试全部使用临时目录 / in-memory fixture；不执行真实 sudo / destructive Git / 系统命令 / Browser

## 当前限制

详见 `docs/release/V0.1-LIMITATIONS.md` 与 `docs/release/V0.1-RELEASE-CHECKLIST.md`。
要点：无真实 Agent Adapter；Browser capability 未实现；Shell Guard 是 Policy Guard 非 OS sandbox；
Approval 无 UI；JDK25 本地 Build Gate 未执行；部分 Operation 类型为 Contract-only（SKIPPED）。
