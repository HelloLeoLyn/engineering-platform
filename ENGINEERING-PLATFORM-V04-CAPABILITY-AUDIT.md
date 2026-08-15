# Engineering Platform — V0.4 Post-Release Capability Audit

- **日期**: 2026-08-15 ｜ **基线**: v0.4.0（HEAD = d17e7e8 = origin/main）
- **模式**: READ ONLY / AUDIT ONLY（未修改任何代码/文档/测试，未 git 操作）
- **审计尺度**: 《Engineering Platform 设计决策记录 V0.7》（26 章，用户提供 docx，已由 EP-ROADMAP-BASELINE-AUDIT 全文提取）+ V0.2/V0.3/V0.4 Scope 文档
- **证据优先级**: Production Code > 可执行入口 > 真实 Asset/Template > E2E/Integration Test > Unit Test > Schema/Contract > Documentation

## 1. Executive Summary

Engineering Platform v0.4.0 完成了 **Enterprise Backend Foundation（生成面）**：从单声明 `project.yaml`（capabilities: [product-reference]）自动补齐 12 项企业能力，生成可编译（90 tests）、可 boot（真实 Tomcat 启动）、可登录、有 RBAC/数据权限/菜单/字典/操作日志的完整企业后端，并带一个真实消费全部企业能力的 Reference Product CRUD。

**但平台本身远未完成**：Frontend 完全空白、通用业务模块生成器不存在、14 项 Platform Capability 只落地 6 项、backend/ 平台本体（32 个 V0.1 遗留文件）从未构建、管理端 API 只有查询没有增删改。整体定位：**企业后端生成能力成熟，前端/模块生成/平台本体资产处于早期**。

| 维度 | 完成度 |
|---|---|
| Core Engine（生成管线） | DONE（成熟） |
| Enterprise Backend Foundation（可生成） | DONE（V0.4 目标达成） |
| Frontend Foundation | NOT DONE |
| Generic Business Module Generation | NOT DONE |
| Platform Capabilities（14 项） | PARTIAL（6/14） |
| Reusable Enterprise Management Assets | PARTIAL（查询 API only） |
| ERP / Business Assets | NOT DONE（Reference Product ≠ ERP） |

## 2. Release Baseline

| 项 | 状态 |
|---|---|
| branch / HEAD | main = d17e7e8 = origin/main |
| tags | v0.1.0 / v0.2.0 / v0.3.0 / **v0.4.0**（annotated，peeled → d17e7e8） |
| working tree | clean（git status 空） |
| CI | GitHub Actions run 31859922332：Contract Validation ✅ / Java 25 Build + Test ✅ / v0.4-enterprise-e2e ✅ |
| 平台测试 | 342 tests, 0 failure（generator 全量） |
| 生成项目测试 | 90 tests, 0 failure（fresh generate + mvn clean test） |

## 3. Original Platform Goal

原始目标（README + V0.7 设计决策记录 26 章 + ADR-001 + system-boundary）：

> **Engineering Platform = 公司级可复用软件工程能力底座（"拿什么开发"）**，与 AI Dev OS（"怎么开发"）独立。
> 将 Engineering Standards / Modules / Capabilities / Providers / Templates / Generator / Engineering Rules
> 沉淀为机器可读、可组合、可解析、可生成、可验证的工程资产，让新项目优先通过 **Reuse > Configure > Generate > AI Code** 获得标准工程能力。

核心架构：Manifest → Validation → Registry → Resolver（13 步）→ EPM → GenerationPlan → Executor（事务/回滚/Ownership）→ Conformance → 真实项目。

**V0.7 26 章定义**（见 §15 矩阵）：平台原则、Backend、Frontend、Platform Capability、Testing/Delivery/Knowledge、Tech Stack、AI Dev OS 边界、物理结构、Maven 治理、Code Style、Platform Core API、Generator 架构、Ownership、AI Engineering Contract、AI Dev OS 集成、阶段状态、Manifest 四件套、Resolver、EPM、GenerationPlan、Executor/Rollback、AI Artifact Schema、WorkItem 状态机、Bootstrap Readiness、V0.1 Bootstrap Scope、Repository Bootstrap。

**各版本 Scope 演进**：V0.1 探索（legacy AI 控制平面）→ V0.2 Asset Platform（资产契约/解析/生成）→ V0.3 Developer Usability（CLI/第二项目）→ V0.4 Enterprise Application Foundation（企业基座）→ V0.5 Frontend Foundation → V0.6 CRUD & Business Module Generation（规划）。

## 4. Core Engine Matrix

| Capability | Status | Evidence | Current Reality | Missing Pieces |
|---|---|---|---|---|
| Asset Contract | DONE | generator/schemas/engineering-asset.schema.yaml + 11 fixtures | MODULE/CAPABILITY/PROVIDER/TEMPLATE 四类，9 要素，Python validator 强制 | — |
| Asset Registry | DONE | registry/capabilities.yaml（15 项）+ validate-registry.py | 能力/Provider/Module 等 8 类 registry 登记 + 一致性校验 | — |
| Asset Repository | DONE | AssetRepository.java（load/assetFiles/gavFixtures/rawAsset） | capabilities/ + providers/ 加载，id=目录名校验，路径安全 | — |
| Resolver（13 步） | DONE | CompleteResolver + AssetAwareResolver + AssetResolution | 显式声明 + 依赖闭包（required 自动加/环检测/兼容匹配） | — |
| Dependency Resolution | DONE | DependencyResolver + CompatibilityValidator | capability/provider 依赖真实解析，去重，版本冲突→异常 | — |
| Provider Resolution | DONE | AssetResolution（providerRequired 自动解析）+ mybatis-plus | 声明 persistence → mybatis-plus provider 自动补齐 | — |
| EPM | DONE | EffectiveProjectModel + Assembler + schema | 唯一事实模型（capabilities/providers/technology/quality） | — |
| Generation Planner | DONE | GenerationPlanner + GenerationPlan | PLAN BEFORE WRITE，版本化计划 | — |
| Generator Executor | DONE | GeneratorExecutor + ExecutionController + DryRunner | Staging/Apply/Rollback 事务式执行，generation-manifest | — |
| Template Rendering | DONE | TemplateRenderer + AssetProjectGenerator | ${key} 渲染 + {package} 占位 + 资产模板装配 | — |
| Ownership / Write Safety | DONE | OwnershipPolicy + OverwritePolicyEvaluator + ContentHasher | GENERATED/MANAGED/USER_OWNED/IMMUTABLE 四级 + 冲突保护 | Drift 检测 CLI（可选） |
| Deterministic Generation | DONE | 平台测试 deterministicGeneration + repeatedGenerationNoDrift | 同输入字节一致（排除 .generator 事务元数据） | — |
| Conformance | DONE | ConformanceValidator（requiredFiles/deps/config/forbiddenImports） | 六类规则 + 破坏性验证（删除 enforcement → FAIL） | — |
| CLI | DONE | ./ep（validate/resolve/generate/conformance + exit codes） | 真实可用，V0.3 CliTest 23/23 + V0.4 fresh gate | — |
| Manifest / Validation | DONE | schemas + ManifestRuntimeValidator + Python validators 7/7 | platform/project/module/provider schema + 运行时校验 | — |
| Error Model | DONE | ApiResponse/ErrorCode/PlatformException + GlobalExceptionHandler | 统一错误码/响应，生成项目真实使用 | — |
| Release / Compatibility | DONE | V0.2/V0.3/V0.4 checklists + V02ReleaseGateE2ETest + SecondProjectE2ETest | 4 版本发布记录，回归持续全绿 | — |

## 5. Enterprise Foundation Matrix

| Capability | Status | Evidence | Missing Pieces |
|---|---|---|---|
| Platform Core | DONE（可生成） | platform-core 资产 9 模板（ApiResponse/PageQuery/PageResult/RequestContext/ErrorCode/PlatformException/IdGenerator/CurrentClock）+ 生成项目消费测试 8/8 | backend/ 平台本体未构建（V0.1 遗留） |
| Authentication | DONE（可生成） | authentication 资产 11 模板（BCrypt 登录/HMAC token/AuthInterceptor）+ AuthRbacE2ETest 8/8 + 真实 HTTP（错密码 401/disabled 403/篡改 401） | — |
| User | PARTIAL | SysUser 实体/Mapper + GET /api/users + /api/users/me | **无 create/update/delete 用户管理** |
| Role | PARTIAL | SysRole 实体/Mapper + role 数据模型 | **无 Role 管理 API**（仅数据/seed 层） |
| Permission | PARTIAL | SysPermission 实体/Mapper + permission seed | **无 Permission 管理 API** |
| RBAC Enforcement | DONE（可生成） | @RequirePermission + PermissionAspect（真实 enforcement）+ 真实 HTTP viewer 403 | — |
| Organization / Department | DONE（可生成） | organization 资产 8 模板（dept 树 + 用户部门关联）+ OrgModelUnitTest 7/7 + /api/departments/tree | **无 dept 管理 API**（仅查询树） |
| Data Permission | DONE（可生成） | data-permission 核心（DataScope/Context/Resolver）+ 四种范围查询层 enforcement + DataScopeE2ETest 10/10 + detail/update 越权防护 | — |
| Menu | DONE（可生成） | menu 资产 12 模板（DIRECTORY/MENU/ACTION + /api/menus/me 权限过滤）+ MenuE2ETest 4/4 | **无菜单管理 API**（仅查询） |
| Dictionary | DONE（可生成） | dictionary 资产 14 模板（Type/Item + 稳定排序 + disabled 过滤）+ DictionaryE2ETest 4/4 | **无字典管理 API**（仅查询） |
| Operation Log | DONE（可生成） | operation-log 资产 12 模板（@OperationLog 切面 + 落库 + 敏感数据 Gate）+ OperationLogE2ETest 7/7 + 真实 DB 查询 | **无日志查询分页管理 API**（仅 latest 20） |
| Reference Product | DONE | product-reference 资产 17 模板（CRUD + DataScope + RBAC + 字典 + 操作日志 + Menu 映射）+ 90 tests 含全链路 | 仅是验证宿主，非通用模块生成 |

**注**：本表以"平台能否生成该能力"为判定标准（当前定位）；"Engineering Platform 自己运行该业务"不是 V0.4 目标。

## 6. Frontend Reality

| 项 | 状态 |
|---|---|
| Frontend Asset Contract | NOT DONE（无 frontend 资产/schema） |
| Vue/React templates | NOT DONE（frontend/ 仅 .gitkeep） |
| Frontend generator | NOT DONE |
| Login page / Layout / Menu rendering / Router | NOT DONE |
| Permission directive / API client / State management | NOT DONE |
| Frontend build / tests / conformance | NOT DONE |

**后端 Menu 已完成 ≠ Frontend PARTIAL**：V0.5 Frontend Foundation 是官方规划，V0.4 明确 Anti-Goal。**Frontend = NOT DONE**。

## 7. Business Module Generation Reality

| 项 | 状态 |
|---|---|
| Reference Product Asset（product-reference） | DONE（验证宿主，17 模板，手工编写的完整模块） |
| module.yaml 业务模块声明 | NOT DONE（modules/ 空，仅 .gitkeep；module.schema.yaml 存在但无生成器消费） |
| Entity/Model 声明 + Field Schema | NOT DONE |
| 通用 CRUD 生成（Controller/Service/Mapper/DTO/migration/permission/menu 自动生成） | NOT DONE（V0.6 规划） |
| Frontend page generation | NOT DONE |

**Reference Product = DONE（手工资产）｜ Generic Business Module Generation = NOT DONE**。V0.4 明确禁止建设通用 CRUD Generator（留 V0.6）。

## 8. Platform Capability Matrix

| Capability | Status | Evidence |
|---|---|---|
| File | NOT DONE | 无资产 |
| Excel Import/Export | NOT DONE | 无资产 |
| Print | NOT DONE | 无资产 |
| Cache | NOT DONE | 无资产 |
| Scheduled Job | NOT DONE | 无资产 |
| Notification | NOT DONE | 无资产 |
| Event | NOT DONE | registry/events.yaml 存在但无资产/模板 |
| Message Queue | NOT DONE | 无资产 |
| Search | NOT DONE | 无资产 |
| Object Storage | NOT DONE | 无资产 |
| Observability | NOT DONE | 无资产（无 OTel） |
| Audit/Evidence | PARTIAL | audit 资产（2 模板，AOP 切面）；但**不等于操作日志**（operation-log 已独立） |
| Configuration | NOT DONE | 无独立资产（仅 platform/project manifest 配置） |
| Feature Flag | NOT DONE | 无资产 |
| Workflow | NOT DONE | 无资产 |
| Tenant | NOT DONE | 无资产 |
| Data Masking | NOT DONE | 无资产 |
| Rate Limit | NOT DONE | 无资产 |
| Idempotency | NOT DONE | 无资产 |

平台能力落地 **6/14（V0.7 §4 定义）**：web/validation/exception-handling/logging/persistence/audit；其余 8 项（Event/File/Print/Async/Observability 等）NOT DONE。

## 9. Reusable Enterprise Assets

| 资产 | 状态 | 说明 |
|---|---|---|
| User Management | PARTIAL | 有 User 实体/查询（GET /api/users、/api/users/me），**无 create/update/delete** |
| Role Management | PARTIAL | 有 Role 数据模型/seed，**无管理 API** |
| Permission Management | PARTIAL | 有 Permission 数据模型/seed，**无管理 API** |
| Department Management | PARTIAL | 有 dept 树查询，**无增删改** |
| Menu Management | PARTIAL | 有菜单查询，**无增删改** |
| Dictionary Management | PARTIAL | 有字典查询，**无增删改** |
| Operation Log Query | PARTIAL | 有 /api/operation-logs/latest（20 条），**无分页/过滤** |

**结论**：Backend Foundation API（查询面）DONE；完整 Management Module（CRUD 管理面）均 PARTIAL。

## 10. ERP / Business Assets

| 资产 | 状态 |
|---|---|
| Product | PARTIAL（仅 Reference Product 验证宿主，非 ERP 商品域） |
| SKU / SPU / Category / Brand | NOT DONE |
| Customer / Supplier / Warehouse | NOT DONE |
| Inventory / Purchase / Sales / Order | NOT DONE |
| Finance | NOT DONE |

**Reference Product ≠ ERP Product Domain**，不扩大解释。

## 11. Developer Experience

| 项 | 状态 |
|---|---|
| project.yaml usability（单声明 product-reference 自动补齐） | DONE（V0.4 亮点） |
| CLI（validate/resolve/generate/conformance + exit codes + 错误提示） | DONE |
| Getting Started 指南 | DONE（docs/guides/getting-started.md） |
| 错误提示可行动、无 stacktrace | DONE（CliTest 断言） |
| Second project / fresh project generation | DONE（SecondProjectE2ETest 5/5） |
| generated build / boot | DONE（90 tests + 真实 Tomcat 启动） |
| 配置占位符提示（secretRef/env） | DONE（generate 输出 Next 提示） |

DX 成熟度：**高**（生成面）。缺：生成项目的运行配置文档（如如何配 env 启动）。

## 12. Testing / Quality

| 维度 | 状态 | 说明 |
|---|---|---|
| Platform unit tests | DONE | 342 tests 全绿 |
| Generated-project tests | DONE | 90 tests 全绿（15 类） |
| HTTP E2E（真实 boot） | DONE | AuthRbac/DataScope/Menu/Dictionary/Product/OpLog/Composition 全 HTTP |
| Negative tests | DONE | 删除 enforcement → conformance FAIL（V0.4 gate） |
| Deterministic / drift | DONE | 平台测试覆盖 |
| Regression（V0.2/V0.3） | DONE | 48 tests 专项 + 全量 |
| CI | DONE | 3 jobs（Python validators / Java 25 / v0.4 enterprise e2e） |
| Release gate | DONE | V0.1-V0.4 四份 checklist + 真实 gate 执行 |
| ArchUnit / Enforcer | NOT DONE | 无实际执行（V0.7 §9/§10） |
| Testcontainers / Playwright / Browser regression | NOT DONE | 生成项目 H2 内存测试为主 |

**平台强项**：生成管线测试深度（342 全量）、真实 HTTP E2E、破坏性 negative gate、CI 三层。

## 13. AI Dev OS Boundary

| 项 | 状态 |
|---|---|
| 独立于 AI Dev OS | ✅（ADR-001 + system-boundary + 仓库独立） |
| 独立于 OpenClaw / Codex | ✅（Platform Core 零依赖任何 Agent SDK；OpenClaw 仅是临时开发执行工具） |
| 独立于 Agent Runtime | ✅（AgentAdapter 是 Agent-neutral contract，未集成） |
| AI Dev OS integration 不计入完成度 | ✅ 遵守（AI Engineering Contract/Artifact/WorkItem 已 MOVED_TO_AI_DEV_OS） |
| 新增 AI 能力 | ✅ 未新增（V0.4 扫描 0 命中 AgentRuntime/OpenClaw/Codex/MCP） |

## 14. Product Reality

**A. 普通 Java 开发者（不用 AI Dev OS）能否使用？** 能。README + Getting Started + ./ep CLI 即可。

**B. 能否只写 project.yaml 生成项目？** 能。单声明 `capabilities: [product-reference]` → Resolver 自动补齐 12 能力。

**C. 生成的是什么？**
- 不是 Skeleton（V0.1 阶段）
- **是 Enterprise Backend Foundation**（登录/RBAC/数据权限/菜单/字典/操作日志/Product CRUD 全有）
- 不是完整 Enterprise Application（无前端、无管理端 UI、无业务管理 API）

**D. 生成后能否直接 compile/test/boot/login/RBAC/DataPermission/CRUD？** 能（90 tests + 真实 boot + 真实 HTTP 全链路证明）。

**E. 仍需开发者自己做的**：
- 前端（V0.5）
- 业务管理 API（User/Role/Dept/Menu/Dict 的增删改——现在只有查询）
- 业务模块（等 V0.6 通用模块生成；现在需手写资产或复制 product-reference 模式）
- 生产部署配置（当前是 test profile + H2；生产 MySQL/Redis 配置要自己写）
- 运维（监控/日志聚合/CI 部署）

## 15. Original 26-Point Matrix

原始定义：**V0.7《Engineering Platform 设计决策记录》26 章**（非 26 个独立 capability，是 26 个设计章节；用户提供 docx 已全文提取于 EP-ROADMAP-BASELINE-AUDIT）。保持原始名称和顺序：

| # | Capability（V0.7 章名） | v0.4 Status | Evidence | Missing |
|---|---|---|---|---|
| 1 | 平台总原则（Reuse>Configure>Generate>AI Code） | PARTIAL | 资产驱动已实现 | AI Code 环节（归 AI Dev OS） |
| 2 | Backend/API/Domain 基线 | PARTIAL | 生成资产分层正确（API/App/Domain/Infra 模板） | backend/ 平台本体未构建、不在 CI |
| 3 | Frontend/UX 基线 | NOT DONE | frontend/ 空 | 全部（V0.5） |
| 4 | Platform Capability 基线（14 能力） | PARTIAL | 6 资产（web/validation/exception-handling/logging/persistence/audit） | Event/File/Print/Async/Observability 等 8 项 |
| 5 | Testing/Delivery/Knowledge | PARTIAL | Q1-Q3 定义 + 342 tests | Testcontainers/Playwright/ArchUnit/Knowledge |
| 6 | Technology Stack V1 | PARTIAL | Java 25 + Boot 3.x（生成模板） | Boot 4.x 升级决策、前端栈 |
| 7 | AI Dev OS 边界 | DONE | ADR-001 + system-boundary | — |
| 8 | Project Physical Structure | PARTIAL | 目录结构就位 | backend/frontend/modules 内容 |
| 9 | Maven Governance | PARTIAL | generator 依赖单向正确 | backend 构建 + Enforcer/ArchUnit |
| 10 | Code Style / Naming | PARTIAL | 规范文档 + checkstyle.xml | checkstyle 未接入构建 |
| 11 | Platform Core API V1 | PARTIAL→DONE（生成面） | platform-core 资产 9 模板 + 生成项目消费 | backend/ platform-core 本体未构建 |
| 12 | Generator Architecture V1 | DONE（主干） | 完整执行链 + CLI + 真实生成 | CRUD/Domain Skeleton/Frontend 生成（V0.6） |
| 13 | Generator Ownership | DONE | OwnershipPolicy/OverwritePolicy/ContentHasher | Drift CLI（可选） |
| 14 | AI Engineering Contract | MOVED_TO_AI_DEV_OS | contracts legacy 冻结 | —（不属 EP） |
| 15 | AI Dev OS 集成模型 | NOT_STARTED | ./ep 独立 CLI | MCP/API 集成（联合，P2） |
| 16 | 阶段状态/过渡说明 | SUPERSEDED | 已发布 4 版 | — |
| 17 | Manifest 四件套 Schema | DONE | 4 schema + validators | — |
| 18 | Resolver Pipeline V1 | DONE | CompleteResolver 13 步 + AssetAwareResolver | — |
| 19 | EffectiveProjectModel V1 | DONE | EPM + Assembler + schema | — |
| 20 | Generation Plan V1 | DONE | GenerationPlanner | — |
| 21 | Executor/Rollback V1 | DONE | ExecutionController/DryRunner/Staging | 破坏性路径 E2E 已补 |
| 22 | AI Engineering Artifact Schema | MOVED_TO_AI_DEV_OS | schemas 保留冻结 | —（不属 EP） |
| 23 | WorkItem 状态机 | MOVED_TO_AI_DEV_OS | legacy 冻结 | —（不属 EP） |
| 24 | Bootstrap Readiness Review | SUPERSEDED | G1-G8 已执行 | — |
| 25 | V0.1 Bootstrap Scope（Skeleton+Sample） | PARTIAL→DONE（企业后端） | 生成可登录企业后端 | 前端/完整 Sample（V0.5/6） |
| 26 | Repository Bootstrap（施工顺序） | PARTIAL | V0.2-V0.4 按序施工 | Frontend/Backend 本体/CRUD 生成 |

## 16. Completion Assessment

**1. Core Engine Completion ≈ 90%+（实质 DONE）**
- Manifest/Registry/Resolver/EPM/Planner/Executor/Ownership/Conformance/CLI 全链路真实可用，342 tests + 4 版本回归 + 破坏性验证。
- 剩余：CRUD/Domain/Frontend 生成面扩展（架构已支持，属 V0.6 内容），非核心引擎缺陷。

**2. Enterprise Backend Foundation Completion（V0.4 目标）≈ 100%（达成）**
- 12 能力全部可生成、可 boot、可真实 HTTP 验证；Reference Product 消费全部能力；90 tests + 真实链路。
- 注：这是"平台能生成该能力"，不是"平台自己运行业务"。

**3. Overall Engineering Platform Completion ≈ 45-50%**
- 已交付：核心生成引擎（DONE）+ 企业后端生成资产（DONE）。
- 未交付大块：**Frontend Foundation（V0.5，完全空白）≈ 15-20% 平台价值**；**Generic Business Module Generation（V0.6，不存在）**；**Platform Capabilities 14 项只落地 6 项**；**backend/ 平台本体未构建**；**管理端 API 只有查询**；**Knowledge/Delivery 体系**。
- 用单一"90%"掩盖这些缺失是误导——**核心引擎 90%，但平台整体完成度远低于此**。

## 17. Biggest Remaining Gaps

1. **Frontend 完全空白**（V0.5 全量）：无前端资产/模板/生成器/构建/测试——平台生成的是"无头后端"。
2. **通用业务模块生成器不存在**（V0.6 全量）：Reference Product 是手工资产，开发者无法从声明生成新业务模块（无 module.yaml 消费、无 CRUD 生成）。
3. **backend/ 平台本体未构建**：32 个 V0.1 遗留文件从未编译，不在 CI；生成器自举（平台生成平台）未验证。
4. **Platform Capabilities 覆盖窄**：14 项只 6 项，Event/File/Print/Async/Observability 等缺失。
5. **管理端 API 只读**：User/Role/Permission/Dept/Menu/Dict/OpLog 均无管理 CRUD——"企业后端"有认证基座但无管理运营面。
6. **生产化配置缺失**：生成项目是 test profile + H2，生产 MySQL/Redis/部署未覆盖。
7. **质量工具链未接入**：ArchUnit/Maven Enforcer/checkstyle 未实际执行（V0.7 §9/§10）。

## 18. Recommended Next Direction

基于审计结果（仅方向，不创建 V0.5 Work Packages）：

| 优先级 | 方向 | 理由 |
|---|---|---|
| P0 | **Frontend Foundation（V0.5）** | 平台生成物目前无 UI，"企业应用"缺一半；官方 roadmap 下一项；与后端企业基座（登录/菜单/权限）天然衔接 |
| P1 | **Generic Business Module Generation（V0.6）** | Reference Product 证明资产模式可行，但手工 17 文件不可扩展；声明式模块生成是平台核心价值放大器 |
| P1 | **管理端 API 补齐**（User/Role/Dept/Menu/Dict CRUD） | 复用现有资产模式成本低，把"查询面"变成"管理面"，企业后端才完整 |
| P2 | **Platform Capabilities 扩充**（Event/File/Print/Async/Observability 等） | 企业应用通用需求，按 V0.7 §4 的 14 项逐个资产化 |
| P2 | **backend/ 平台本体构建 + 自举** | 验证"平台生成平台"，补 V0.7 §2/§11 的平台本体缺口 |

**推荐顺序**：V0.5 Frontend Foundation 优先（官方规划 + 最大价值缺口），随后 V0.6 模块生成；管理 API 补齐可作为 V0.5 之前的小增量（成本低、收益直接）。

---

## 最终回答

**Engineering Platform v0.4.0 是什么？**
一个成熟的企业后端生成引擎 + 完整的企业后端 Foundation 资产集：单声明 manifest 即可生成可编译、可启动、可登录、带 RBAC/数据权限/菜单/字典/操作日志/参考业务模块的真实 Spring Boot 企业后端（90 tests + 真实 HTTP 全链路验证），并有 4 版本连续发布的生成管线与质量门禁。

**Engineering Platform v0.4.0 不是什么？**
不是完整企业应用平台（无前端）、不是通用业务模块生成器（Reference Product 是手工验证宿主）、不是 ERP/业务资产库、不是 AI Dev OS（独立且不依赖）、不是平台本体（backend/ 未构建、未自举）。

**下一阶段最应该补什么？**
**Frontend Foundation（V0.5）**——平台生成物目前是无头后端，前端是最大价值缺口；其次在合适时点启动通用业务模块生成（V0.6），并低成本补齐管理端 CRUD API。
