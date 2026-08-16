﻿# V06-WORK-004 RESULT — Engineering Platform Console + Project Builder

- **日期**: 2026-08-16 ｜ **Mode**: WP_ACCEPTANCE
- **仓库**: /home/administrator/workspace/engineering-platform
- **状态**: Console v1 完成，Golden Path 全绿，**未 commit / push**

---

## Architecture

Console = 现有 Generator 的可视化 Adapter / UI。**零第二套生成逻辑**：

```
Engineering Platform Console (console-web, Vue 3 + Vite, :8098)
        │  fetch /api/* (vite proxy)
        ▼
Console API (console-server, Java 25 + JDK HttpServer, :8099)
        │  ConsoleContractValidator（友好错误分类）+ GenerationService
        ▼
Project Contract V2 (project.yaml —— buildContract 纯函数组装)
        │
        ▼
现有 Generator 链路（完全复用，与 CLI `ep generate` 同一路径）
   ├── AssetRepository.load
   ├── CompleteResolver.resolve(ResolverInput)
   └── AssetProjectGenerator.generate(epm, repo, outputDir)
```

- 未新建 Resolver / Planner / Generator / Execution Engine / Contract Model
- Contract 就是现有 project.schema.yaml V2（schemaVersion/project/application/stack/frontends/modules/capabilities/quality）
- 前端 buildContract() 是唯一组装点，产物与手工写 project.yaml 语义一致（验收 E）

## Implemented

### Console（console/console-web，Vue 3 + Vite + Element Plus + EP Design Tokens）
- **Sidebar**: Overview / Projects / Project Builder / Business Modules / Templates / Build & Run / Settings（后 4 项占位）
- **Topbar**: breadcrumb（Workspace / 页面）+ user placeholder
- 视觉语言沿用 EP Design System（lavender canvas、tinted surfaces、大圆角、柔和分层阴影、metric typography），有 Console 自己的 identity，不复用 enterprise-admin 业务代码

### Project Builder（6 步 Stepper）
1. **Basic Information** — name / id / description / basePackage / output location
2. **Application Profile** — 从 platform.yaml applicationProfiles 读取：enterprise **Certified 可选**；corporate-portal / ecommerce / custom **Coming Soon 不可选**
3. **Technology Stack** — 从 stackProfiles 读取 enterprise-java25，展示 Backend(Java 25/Spring Boot/Maven/MyBatis-Plus/Flyway/MySQL/REST/Jakarta Validation) + Frontend(Vue 3/TS/Vite/Pinia/Element Plus/EP UI) + Testing(JUnit/Vitest/Playwright Golden Path)
4. **Frontend Template** — enterprise-admin **Certified 可选**；modern-console / simple-admin / corporate-website / commerce-storefront / custom **Coming Soon**
5. **Business Modules** — 从 registry/modules.yaml + capabilities 读取（sample-customer / supplier / customer-lite / warehouse-lite / product-reference），显示 kind + description；Product→capability、Supplier→module 自动映射
6. **Review** — Project Summary + **YAML Preview**（/api/preview 实时渲染）+ Generate

### Contract Preview
- UI state → `buildContract()` → Project Contract V2（application.profile / stack.profile / frontends / modules / capabilities / quality）
- Review 页只读展示 project.yaml，与最终落盘文件一致

### Generation
- `/api/generate` → 现有 CompleteResolver + AssetProjectGenerator
- 阶段展示（coarse-grained）：Preparing → Validating → Resolving → Planning → Generating → Completed/Failed
- 输出目录非空时 409 拒绝（Output Path Invalid），与 CLI 语义一致
- 成功后 contract 落盘 `project.yaml` + 全量生成文件

### Validation（/api/validate）
- 错误分类：Invalid Project Configuration / Unsupported Application Profile / Unsupported Stack Profile / Unsupported Frontend Template / Unknown Module / Output Path Invalid / Generation Conflict
- 保留 message 作 Technical Details；不 dump 原始异常

### Projects（filesystem-backed）
- `console/console-data/projects.json` 单文件元数据（name/profile/stack/frontend/modules/location/lastGenerated/status），无数据库

### Overview
- 真实平台数据：Projects / Generated Modules / Certified Templates / Recent Projects（非假 ERP 数据）

## Golden Path（一次真实浏览器流程，Playwright）

Open Console → New Project → enterprise → enterprise-java25 → enterprise-admin → Product + Supplier → Review → Generate → Result → Projects

| 步骤 | 结果 |
|---|---|
| Overview 3 KPI + recent projects | ✅ |
| Builder 6 步 Stepper | ✅ |
| Stack 展示 17 个技术 tag | ✅ |
| Template 1 certified + 5 reserved | ✅ |
| Module 5 卡片（含 product-reference / supplier） | ✅ |
| Review YAML 776 chars（含 supplier + product-reference） | ✅ |
| **Generate 成功**（真实生成） | ✅ |
| Projects 页显示 1 行 | ✅ |
| Console errors | **0** ✅ |

## Generated Project

- **path**: `console/console-data/generated/console-demo/`
- **profile**: enterprise
- **stack**: enterprise-java25
- **frontend**: enterprise-admin
- **modules**: supplier（Product 经 product-reference capability）
- 结构验证：**504 files**，backend `application/supplier/` + `application/product/`、frontend `views/supplier/` + `views/product/`（List/Detail/Edit）、migration、project.yaml 落盘 ✅

## Verified

| 项 | 结果 |
|---|---|
| Console backend targeted tests（ConsoleBackendTest） | **10/10 PASS**（catalog / validation 分类 / project store / 真实 Product+Supplier 生成 / YAML preview） |
| Console frontend targeted tests（contract.spec + api.spec） | **6/6 PASS** |
| Project Builder contract 组装（buildContract） | 3 tests PASS |
| console-web pnpm build | PASS |
| console-server mvn compile + package | PASS |
| 一次真实 Product + Supplier 生成 | PASS（504 files，双模块齐全） |
| 生成项目结构验证 | PASS |
| git diff --check | clean |

未跑 Full Regression / Full Playwright suite / Historical gates（WP 预算内）。

## Visual Artifacts（`docs/visual-smoke-v04/`）

- **Overview**: 01-console-overview.png
- **Builder**: 02-builder-template-selection.png + 03-builder-module-selection.png
- **Review + YAML Preview**: 04-review-yaml.png
- **Generation Result**: 05-generation-result.png
- Projects: 06-projects.png

## Boundary Check

- **no duplicate generator** ✅ —— Console 只调 CompleteResolver + AssetProjectGenerator（与 CLI 同一链路），GenerationService 仅做 Contract 翻译与结果包装
- **no AI Dev OS dependency** ✅ —— 零 AI 集成
- 未做：MySQL/Excel/CSV Import、Table Designer、Dynamic Module Builder、Build/Run orchestration、Docker、Deployment、Portal/Ecommerce 生成、Auth/RBAC、Cloud 项目管理（全部标记后续 WP；Build & Run 页面占位 "Coming in next step"）

## Known Limitations

- Generate 进度为 coarse-grained（现有 Execution Engine 不提供实时阶段事件；按 WP 允许，不重构引擎）
- Projects 元数据为单 JSON 文件（首版 lightweight，无并发锁）
- Business Modules / Templates / Build & Run / Settings 为占位页
- Console 未加登录（明确 Non-goal）
- frontends 首版固定单前端 `admin`

## Escalation

**NO** —— 未新建第二套生成链路；未改现有 Contract schema / Resolver / Planner / Generator / Execution Engine / Ownership / PathSafety。仅新增：console-server（Adapter）+ console-web（UI）+ console-data（元数据文件）。

## Changed Files

- `console/console-server/`（新）：pom.xml + Json / YamlDumper / GenerationService / ConsoleServer / ProjectStore / ConsoleContractValidator + ConsoleBackendTest（10 tests）
- `console/console-web/`（新）：Vue 3 + Vite 应用（AppLayout / Overview / Projects / ProjectBuilder 6 步 / Placeholder / contract.ts / console.ts API client / tokens.css）+ tests（6）
- `console/console-data/projects.json`（运行时生成，未 commit）

未 commit、未 push、未开始 WORK-005。

---

**V06-WORK-004 = PASS**（Console v1 交付，Golden Path 全绿，等待后续 WP）
