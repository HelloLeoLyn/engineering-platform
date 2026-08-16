# Engineering Platform V0.6 Architecture Plan — Visual Project & Business Module Builder

- **日期**: 2026-08-16 ｜ **Mode**: READ ONLY / PLANNING ONLY
- **状态**: 基于 V0.5 真实代码审计，未修改任何文件

---

## 1. Current Reuse（现有可复用资产）

### 1.1 Generator Core（全部复用，零重写）
| 组件 | 现状 | V0.6 复用方式 |
|---|---|---|
| `AssetProjectGenerator` | 生成 backend+frontend 完整项目 | 唯一生成链，Console/CLI/UI 全部走它 |
| `CapabilityResolver` / `ProviderResolver` / `DependencyResolver` | 资产闭包解析 | Project Contract → 解析 → EPM |
| `EffectiveProjectModelAssembler` | EPM 装配 | 不变 |
| `GenerationPlanner` / `GeneratorExecutor` | 生成计划 + 执行 | 不变（含 DryRun/Staging/Rollback/Transaction） |
| `ConformanceValidator` | 生成物契约校验 | 不变（frontend/backend conformance 已成熟） |
| `TemplateRenderer` | 模板渲染（${key} 语法） | 不变 |
| `FilesystemGuard` / `OwnershipPolicy` / `PathSafety` / `ShellGuard` / `GitGuard` | 安全边界 | 不变 |
| `VerificationEngine` / `ExecutionController` | 质量门 | 不变 |
| `EngineeringPlatformCli` | CLI 入口 | 保留为权威 CLI；Console 是它的前端壳 |

### 1.2 Contracts / Schemas（全部复用）
- `project.schema.yaml` V1（Project Composition Contract）— 已含 identity/platform/profiles/technology/modules/capabilities/quality
- `module.schema.yaml` V1（Module Contract）— 已含 type: business/platform/capability/provider-binding
- `effective-project.schema.yaml`、`engineering-asset.schema.yaml`、`generation-plan.schema.yaml` 等

### 1.3 Assets（22 个，全部复用）
- **Backend 企业能力**：platform-core / web / validation / exception-handling / logging / persistence / audit / authentication / rbac / organization / data-permission / menu / dictionary / operation-log / product-reference
- **Frontend**：frontend-shell（工程配置）/ frontend-auth（Login+Shell+Request）/ frontend-permission（权限 UI+UX 基础）/ frontend-enterprise-management（管理 UI）/ frontend-product-reference（Product 全栈）
- **Infra**：runtime-recipe（dev-start/stop/status）/ playwright-e2e（Golden Path）

### 1.4 Product Reference = Pattern Source
`product-reference` + `frontend-product-reference` 已是完整前后端 Reference Module：
List/Search/Create/Edit/Detail/Disable + RBAC/DataScope/Menu/Dictionary/OperationLog + Golden Path E2E。
**V0.6 Generic Module Generation 以它为模式源**（非复制，抽象其结构）。

### 1.5 Runtime Recipe
`scripts/dev-start.sh` 等已解决 start/status/stop/端口冲突/readiness —— V0.6 Generate→Build→Run 直接复用。

---

## 2. Target Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Engineering Platform Console (V06-WORK-003)  │
│   Project Builder │ Capability Selection │ Frontend Template    │
│   Module Builder  │ Generation Preview   │ MySQL Import Wizard  │
└──────────────┬──────────────────────────────────────────────────┘
               │ 只产 Contract（YAML）——不产代码、不另造生成链
               ▼
        Project/Module Contract (YAML)          ← UI / CLI 共享同一契约
               │
               ▼
   ┌───────────────────────────┐
   │ Resolver/EPM (现有)        │
   │ Generator (现有,唯一)       │
   │ Conformance (现有)         │
   │ Runtime Recipe (现有)      │
   └───────────┬───────────────┘
               ▼
        generated project (backend+frontend)
        → build → test → dev-start → run → acceptance
```

**核心原则**：
- Console 只负责"可视化编辑 Contract + 预览 + 触发 CLI"；生成链唯一 = `ep`
- 所有新能力以 Asset 落地（frontend 模板 = 正式资产 profile）
- Application Profile（业务形态）与 Frontend Template（视觉形态）**解耦**

---

## 3. Contract Design

### 3.1 Project Contract 扩展（V06-WORK-001）
在 `project.schema.yaml` V1 基础上**兼容扩展**（不破坏 V1）：

```yaml
project:
  id: supplier-portal
  name: Supplier Portal
  version: 1.0.0
  basePackage: com.acme.portal
  # NEW: Certified Technology Stack（V0.6）
  stack:
    certified: true
    ref: v0.5-certified            # 平台认证的 BOM/依赖集
  # NEW: Application Profile（业务形态，与前端模板解耦）
  applicationProfile: enterprise-admin   # enterprise-admin | corporate-website | commerce-storefront | custom
  # NEW: Frontend Template（视觉形态）
  frontendTemplate: enterprise-admin     # V0.6 仅实现 enterprise-admin；其余保留枚举扩展点
capabilities:
  - id: product-reference
```

**扩展点（V0.6 不实现，仅枚举/文档）**：
- `applicationProfile: corporate-website` / `commerce-storefront` —— 未来 Portal/E-commerce
- `frontendTemplate: corporate-website` 等 —— 未来模板族

**防锁死设计**：
- Application Profile ≠ Frontend Template（两个独立字段，可任意组合）
- Contract 字段命名业务中立（enterprise-admin 是"管理型应用"而非"ERP"）
- capabilities 仍是组合声明，不写死业务

### 3.2 Generic Business Module Contract（V06-WORK-002）
复用 `module.schema.yaml` V1 的 `type: business`，新增模块级业务契约：

```yaml
module:
  id: supplier
  name: Supplier
  version: 1.0.0
  type: business
  # NEW: Business Module Contract（V0.6）
  entity:
    name: Supplier
    fields:
      - name: code
        type: string
        required: true
        unique: true
      - name: name
        type: string
        required: true
      - name: status
        type: dictionary
        ref: supplier_status
      - name: category
        type: dictionary
        ref: supplier_category
      - name: departmentId
        type: department-reference
      - name: contactPhone
        type: string
  features:
    - list            # + search/filter/pagination
    - create
    - edit
    - detail
    - disable
  enterprise:
    rbac: true        # 4 权限码（read/create/update/disable）
    dataScope: true   # 部门/自建数据范围
    menu: true        # 动态菜单注册
    dictionary: true  # 字典字段
    operationLog: true
```

**实现路径**：Module Contract → 映射到 Product Reference 已验证的资产组合（rbac+data-permission+menu+dictionary+operation-log+前端组件）→ 生成。

**Acceptance 证明**：用 Supplier 模块证明 Generic Generation（Product Reference 是 Pattern Source，Supplier 是第二个消费者——两个不同业务域都成功才算"通用"）。

### 3.3 Frontend Template Profile（V06-WORK-001 定义 / WORK-002 实现）
```
frontendTemplate: enterprise-admin
  ├── frontend-shell（工程配置，现有）
  ├── frontend-auth（Login/Shell/Request，现有）
  ├── frontend-permission（权限 UI/UX，现有）
  └── frontend-enterprise-management（管理页面框架，现有）
```
V0.6 的 enterprise-admin = 现有 5 个 frontend-* 资产的**组合 Profile**（不新建模板引擎）；corporate-website / commerce-storefront 仅注册枚举 + 文档扩展点。

---

## 4. Console / DB Import Design

### 4.1 Console（V06-WORK-003）
**形态**：独立小 Web 应用（放在 `console/` 目录，自身也用平台技术栈生成或轻量 Vue+Spring Boot）。

```
console/
├── frontend/   # Vue3 + Element Plus（复用平台组件风格）
│   ├── views/project-builder/     # 新建项目向导
│   ├── views/capability-select/   # 能力勾选（读 registry/capabilities.yaml）
│   ├── views/frontend-template/   # 模板选择
│   ├── views/module-builder/      # 模块构建器（字段/字典/权限配置）
│   ├── views/db-import/           # MySQL 表导入向导
│   └── views/generation-preview/  # 预览（调 CLI dry-run 或解析 EPM）
└── server/     # Spring Boot：代理 CLI + 读 registry + MySQL 元数据读取
```

**约束（硬）**：
- Console **不 import generator-core 做生成**；只通过 `ProcessBuilder` 调 `ep`（或 HTTP 桥接 CLI）
- Console 产出的唯一产物 = YAML Contract（project.yaml / module.yaml）
- 预览 = `ep generate --dry-run`（现有 DryRunner）或展示解析后的 EPM 摘要

### 4.2 MySQL Table Import（V06-WORK-003）
```
1. 连接 MySQL（用户提供只读账号 + schema）
2. 列出表（information_schema）→ 批量勾选
3. 每表 metadata：列名/类型/可空/主键/唯一键/外键
4. Suggested Mapping（规则引擎，非 AI）：
     id BIGINT PK        → entity.id（EntityId string contract）
     *_status / status   → dictionary ref（suggest supplier_status）
     *_category          → dictionary ref
     dept_id/department  → department-reference
     created_by/created_at/updated_at → audit 字段（跳过）
     name/code/title     → 主显示字段
5. Human confirmation（改字段名/类型/字典映射/是否启用 rbac）
6. 生成 Module Contract → 进入 Module Builder 流程
```

**边界**：V0.6 只支持**单表模型**；不做关系/主从/多对多（明确 Non-Scope）。外键列只映射为 reference 字段（department_id 等），不建 JOIN 关系。

---

## 5. WORK-001~004 Implementation Plan + Acceptance

### V06-WORK-001 — Project & Module Contract Foundation
**代码边界**：
- `generator/schemas/project.schema.yaml`（兼容扩展：stack/applicationProfile/frontendTemplate）
- `generator/schemas/module.schema.yaml`（兼容扩展：entity/features/enterprise）
- `generator-contracts`：EPM 增加 applicationProfile/frontendTemplate 字段（默认值向后兼容）
- `capabilities/frontend-shell/asset.yaml` 或新 `capabilities/frontend-template-enterprise-admin/`（组合 Profile 声明）
- docs：applicationProfile/frontendTemplate 枚举 + 扩展点文档

**依赖**：现有 Resolver/EPM/Generator 全部不动（契约向后兼容）
**Acceptance**：
- project.schema 新旧 manifest 都通过 validate
- V0.5 fixture（无新字段）仍可 resolve/generate（回归不破）
- 新增字段默认值正确进入 EPM
- FAST_DEV：只跑 contract validator + EPM 相关平台测试

### V06-WORK-002 — Full-stack Business Module Generation
**代码边界**：
- 新资产 `capabilities/business-module-generator/`（**Asset 而非 Core Engine 新引擎**——用现有 TemplateRenderer + 资产组合，产 Module 后端 CRUD + 前端页面）
  - 或者更优：`capabilities/supplier-reference/`（第二个 Reference Module 证明通用性）+ 文档化"Module 生成模式"
- 前端：复用 frontend-enterprise-management 组件（AppTable/AppForm/SearchForm/DictionarySelect/StatusTag/ConfirmAction/PermissionButton）
- 后端：复用 product-reference 模式（Port/Service/Controller/DataScope/OpLog/字典校验）

**依赖**：V06-WORK-001 Contract
**Acceptance**：
- 用 Supplier 业务域生成完整前后端（List/Search/Create/Edit/Detail/Disable + RBAC/DataScope/Menu/Dictionary/OpLog）
- Supplier 与 Product 是两个不同域，均生成成功（证明 Generic，非 Copy-Paste）
- 生成项目 build + 相关 E2E 通过
- FAST_DEV：只跑 Supplier 相关测试 + frontend affected tests

### V06-WORK-003 — Engineering Platform Console + MySQL Import
**代码边界**：
- 新目录 `console/`（独立应用，不侵入 generator-core）
- server：Spring Boot，提供 ① registry 读取 ② MySQL metadata ③ CLI 代理（spawn `ep`）
- frontend：Vue3 向导（Project Builder / Capability Select / Frontend Template / Module Builder / DB Import / Preview）
- MySQL Import：information_schema 读取 + 规则映射 + 确认 UI + Module Contract 输出

**依赖**：V06-WORK-001/002 Contract
**Acceptance**：
- 向导完成 → 产出合法 project.yaml + module.yaml（通过 `ep validate`）
- MySQL 导入：真实 MySQL（或 H2 模拟）→ 选表 → mapping → 确认 → 产出 Module Contract
- Preview：显示解析后的能力/模块清单（调 CLI dry-run）
- **Console 不直接生成代码**（架构 gate：代码只来自 ep）
- FAST_DEV：Console 自身测试（Vue 组件 + server 集成）；不跑平台全量

### V06-WORK-004 — Generate / Build / Run + Supplier Product Proof
**代码边界**：
- `examples/supplier-project/project.yaml`（含 Supplier 模块的完整 manifest）
- 复用 Runtime Recipe（dev-start/stop/status）做 Run 验证
- Golden Path E2E 扩展到 Supplier 域（复用 playwright-e2e 资产模式）

**依赖**：WORK-001~003
**Acceptance**：
- Fresh project：`ep validate/resolve/generate` → backend `mvn test` → frontend `pnpm test/build` → `dev-start` → 真实运行
- Supplier 全功能浏览器验证（Golden Path）
- Product Reference + Supplier 双域证明 Generic Generation
- **V0.6 Final Release Gate**（Full Regression + Golden Path + validators + CI）仅在此执行一次

---

## 6. Risks / Decisions

### 6.1 可能导致 Portal/E-commerce 锁死的设计（必须避免）
| 风险 | 缓解 |
|---|---|
| Contract 写死 admin/ERP 语义 | applicationProfile 与 frontendTemplate 独立枚举，字段业务中立 |
| 前端模板与业务耦合 | enterprise-admin 只是组合 Profile；页面组件库中立（AppTable 等） |
| Module Contract 假设管理 CRUD | features 可枚举（list/create/…），未来扩展展示型字段 |
| 生成器假设后端 admin API | Product Reference 模式已证明 CRUD 中立；Supplier 域验证 |

### 6.2 可能越过 AI Dev OS 边界的设计（必须禁止）
| 风险 | 约束 |
|---|---|
| MySQL Import 用 AI 自动映射 | **禁止**：只用规则引擎（suggested mapping）+ 人工确认 |
| Console 引入 Agent/自动编码 | **禁止**：Console 只编辑 Contract + 调 CLI；无 Agent/Planning/Task/Approval/Sandbox |
| "智能"生成模块 | **禁止**：Generic Generation = 确定性模板组合，非 AI 生成 |
| Autonomous Coding | 不引入；生成=确定性资产组合 |

### 6.3 关键决策
1. **Business Module Generator 以 Asset 形式落地**（capabilities/business-module-*），不新建 Core Engine 生成器 —— 保持"生成链唯一"
2. **Supplier 作为第二个 Reference 域**证明通用性（Product=Pattern，Supplier=Proof）
3. **Console 独立应用 + CLI 代理**，不 import generator-core —— 架构隔离，防止"第二套生成链"
4. **MySQL Import 只读元数据 + 规则映射 + 人工确认** —— 无 AI，无写库
5. V0.6 不做：Low-Code Designer / 关系 / 主从 / 多对多 / 部署平台 / Portal/E-commerce 实现

---

## 7. GO / NO-GO

**GO**

依据：
1. **全部基础已存在**：唯一生成链（AssetProjectGenerator）、22 资产、Product Reference 双端模式源、Runtime Recipe、Conformance、Golden Path 全部 V0.5 验证可用
2. **Contract 扩展兼容**：project/module schema 可向后兼容扩展（EPM 默认值），V0.5 fixture 不破
3. **Generic 证明路径清晰**：Product（已有）+ Supplier（新增）= 两域证明，无投机
4. **边界明确**：Console 不造生成链、MySQL Import 无 AI、无 Agent —— 不越 AI Dev OS 边界
5. **防锁死设计内建**：Application Profile / Frontend Template 解耦，业务中立命名

前置条件：
1. **V0.5 RELEASE_GATE 完成**（当前验收收尾优先）
2. EP-REPO-MODERNIZATION-001（仓库自举）可作为 V0.6 前置或并行（examples/enterprise-reference 与 examples/supplier-project 可合并为同一示例目录族）
3. 每个 WORK 独立验收，FAST_DEV 模式；Full Regression 只在 WORK-004 最终 Gate 一次

**建议**：V0.5 验收通过后，从 V06-WORK-001 开始（Contract 扩展，风险最小，且解锁后续全部）。

---

*本计划未修改任何文件；等待人工审核。*
