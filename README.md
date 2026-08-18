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

## V0.6 能做什么（Console-Driven Full-Stack Generation + Developer Loop）

从 Console 可视化创建/导入业务模块，生成完整全栈 CRUD 应用，并直接在 Console 完成 Build / Run / 日志 / Stop / Restart（V0.6 Certified Stack：`enterprise` + `enterprise-java25` + `enterprise-admin`）。

- **Project Contract V2**：Project / Application / Stack / Frontend Profile 统一契约
- **Generic Business Module Contract**：业务模块即 Contract（字段/features/enterprise 元数据），零专用 capability
- **Generic Full-stack CRUD Generation**：任意业务域（如 Customer / Warehouse）→ 完整前后端 CRUD，无业务名硬编码
- **Enterprise Admin UI 2.0**：EP Design Tokens / AppLayout / 组件体系
- **Engineering Platform Console**：Project Builder（6 步向导） / Business Module Builder / MySQL Schema Import / Excel Import / Build & Run / Logs / Runtime
- **Generate → Environment Preflight → Build → Start → Status → Logs → Open → Stop/Restart**：全部复用 Runtime Recipe，项目级 Java 25 隔离
- **Real MySQL + Browser Golden Path**：真实 MySQL 导入 → 生成 → 运行 → 浏览器 CRUD 全链路验收

报告：[V06-ARCHITECTURE-PLAN](V06-ARCHITECTURE-PLAN.md) ｜ [V06-WORK-005](V06-WORK-005-IMPLEMENTATION-REPORT.md) ｜ [V06-WORK-006](V06-WORK-006-IMPLEMENTATION-REPORT.md) ｜ [V06-FINAL-ACCEPTANCE](V06-FINAL-ACCEPTANCE-REPORT.md)（V0.6 RELEASE DECISION = GO）

## V0.7 能做什么（Business Modeling + Relations + Master/Detail）

从 Business Modeling Contract 描述**关系型业务域**，生成完整的前后端 Master/Detail 应用（V0.7 Certified Stack：`enterprise` + `enterprise-java25` + `enterprise-admin`）。

- **Business Modeling Contract V2 + Field Semantics V2**：业务模块显式声明字段语义（reference / enum / money / date / dictionary / text）与关系（MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE / composition）
- **Reference Contract**：字段级 `reference` 配置（target / labelField），生成 ReferenceSelect 下拉 + 后端引用存在性校验（`*_REFERENCE_NOT_FOUND`）
- **Relationship-aware Backend Generation**：FK 约束 / 引用校验 / Master-Detail 事务（create 一次保存 parent+children）/ deterministic reconciliation（update：existing updated / missing deleted / new inserted）
- **Relationship-aware Frontend Generation**：`ReferenceSelect`（canonical PageResult 契约）+ `EditableDetailTable`（Add Row / Remove Row / 行内 reference/money/number/text 编辑）+ Master Create/Edit/Detail 页面
- **Business Module Builder 2.0**：Console 可视化构建带关系/语义的业务模块（模块即 Contract）
- **MySQL FK / Relation Candidate Discovery**：真实 MySQL schema → FK/关系候选 → 导入
- **Excel Relation/Semantic Candidate**：Excel 导入关系/语义候选
- **Candidate Review / Human Confirmation**：导入候选人工确认后才生成
- **Purchase Order Golden Path**（Reference Scenario，非专用代码）：Supplier/Product/PurchaseOrder/PurchaseOrderItem → 浏览器 Create（1 parent + 2 children 一次保存）→ Edit（改/删/增 reconciliation）→ Detail 全链路
- 验收：[V07-ARCHITECTURE-PLAN](V07-ARCHITECTURE-PLAN.md) ｜ [V07-WORK-001~006 报告](V07-WORK-006-IMPLEMENTATION-REPORT.md) ｜ [V07-FINAL-ACCEPTANCE](V07-FINAL-ACCEPTANCE-REPORT.md)（V0.7 RELEASE DECISION = GO）

## Getting Started（V0.3）

新用户从这里开始：[Getting Started Guide](docs/guides/getting-started.md) —— 只需一份 `project.yaml` + `./ep` 命令，即可生成真实可编译的 Spring Boot 项目：

```bash
./ep validate project.yaml
./ep resolve project.yaml
./ep generate project.yaml --output ./my-project
./ep conformance project.yaml ./my-project
cd my-project && mvn test
```

## V0.4 能做什么（Enterprise Application Foundation）

从一份**单声明** project.yaml 生成可登录、可授权、可数据权限隔离、可记操作日志的完整企业后端（V0.4 Enterprise Foundation）：

```yaml
capabilities:
  - id: product-reference   # Resolver 自动补齐全部企业能力
```

- **企业资产集**：Platform Core / Authentication / RBAC（User/Role/Permission）/ Organization / Data Permission V1 / Menu / Dictionary / Operation Log
- **Reference Product**（验证宿主）：真实消费全部企业能力的 CRUD 业务模块（登录→RBAC→数据权限→菜单→字典→操作日志→落库全链路）
- **单声明自动组合**：`product-reference` 的依赖闭包自动补齐 12 项企业能力 + mybatis-plus，无需手工声明
- **真实运行验收**：生成项目 boot → 真实 HTTP（Auth/RBAC/DataScope/Menu/Dictionary/Product/Operation Log）→ 操作日志从 DB 查证
- 发布状态：[V0.4 Release Checklist](docs/release/V0.4-RELEASE-CHECKLIST.md)

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

## V0.8+ Backlog（记录，不实施）

- MANY_TO_MANY 关系（V0.7 明确保留未实现）
- richer relation semantics（cascade 策略、on-delete 行为、关系级权限）
- UI refinement（Enterprise Admin UI 2.x 持续打磨）
- richer enum / dictionary semantics（enum 强校验回填、国际化 label、依赖字典）
- richer import mapping（MySQL/Excel 导入时 enum/semantic 启发式推断、列映射编辑）
- persistent build/runtime history
- stronger datasource preflight
- additional frontend templates
- Portal / E-commerce 后续方向
