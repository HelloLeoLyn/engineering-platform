﻿# V06-WORK-005 RESULT — Business Module Builder + Schema Import + Generic CRUD Generation

- **日期**: 2026-08-16 ｜ **Mode**: WP_ACCEPTANCE
- **仓库**: /home/administrator/workspace/engineering-platform
- **状态**: 全部验收 A-I 通过，**未 commit / push**

---

## 1. Implemented

- **Generic Renderer 增强**（GenericModuleGenerator + GenericFrontendTemplates）：
  - searchable 字段 → 列表搜索区独立输入框 + 后端 per-field 过滤参数（controller/service/port/repository/applyFilters 全链路）
  - defaultValue → migration DEFAULT 子句（H2 MODE=MySQL 兼容）
  - detailVisible → detail 页字段显隐
  - comment / length / precision / scale 完整消费
- **Console Business Modules 页面**（原占位页 → 真实建模入口）：
  - Manual 创建（模块基本信息 + Field Designer + features/enterprise 配置）
  - MySQL Schema Import（Test Connection → Load Tables → Import Table）
  - Excel Import（上传 .xlsx → 解析 → Field Designer）+ 模板下载
  - Preview Contract（module manifest YAML）+ Generate（走现有生成链路）
- **console-server 模块 API**：/api/modules CRUD + /api/modules/import/mysql/{test,tables,import} + /api/modules/import/excel/{import,template} + /api/modules/{id}/generate
- **新服务类**：ModuleStore（filesystem-backed 模块库）、MySqlImportService（JDBC information_schema）、XlsxSupport（零依赖 xlsx 解析/模板生成）

## 2. Architecture

```
Business Modules 页面 (console-web)
   │  Manual / MySQL Import / Excel Import
   ▼
Field Designer → Business Module Contract (module manifest YAML, business 段在顶层)
   │  Console 不建私有 schema
   ▼
console-server /api/modules/{id}/generate
   │  GenerationService.generateWithModules (extraManifests 注入现有链路)
   ▼
现有 CompleteResolver（BusinessModuleResolver 解析 manifest.business）
   → AssetProjectGenerator → 全栈 CRUD 生成
```

- 零第二套 Generator / Resolver / Execution Engine / Contract Model
- 模块 manifest 与 fixtures（customer-lite.yaml 等）同构：`business` 段在 manifest **顶层**（BusinessModuleResolver 读取 `manifest.get("business")`）

## 3. Generic Renderer Design

- 同一 GenericModuleGenerator + GenericFrontendTemplates 渲染所有业务域，无业务名分支
- BusinessEntityField 消费：type/required/unique/length/precision/scale/defaultValue/primaryKey/comment/semantic/dictionary/frontend(label/order/listVisible/searchable/formVisible/detailVisible)
- searchable 全链路：controller `@RequestParam String <field>` → service pageFiltered 透传 → repository applyFilters `wrapper.like(field, value)`
- defaultValue：migration `DEFAULT <literal>`（string 引号 / boolean 1|0 / date DATE 'x' / datetime TIMESTAMP 'x'）

## 4. Business Module Contract Flow

UI state → buildManifest() → module manifest（schemaVersion/module/compatibility/business: table/entity/fields/features/enterprise/frontend）→ Console API save → /generate → 现有 resolver → EPM → generator → 全栈产物 + projects.json 记录

## 5. Manual Builder

- Field Designer 支持：增/删/改字段、上下移动排序、类型下拉（string/text/integer/long/decimal/boolean/date/datetime）、required/PK/unique 勾选、length、label、searchable、dictionary、comment
- CRUD features 勾选（list/search/create/edit/detail/disable）+ enterprise 勾选（permissions/dataScope/menu/dictionary/operationLog）
- 模块基本信息：id/name/table/entity/description

## 6. MySQL Import

- Console 输入 host/port/database/username/password → Test Connection → Load Tables → Select Table → Import
- 读取 information_schema.columns：类型映射（VARCHAR/CHAR→string、TEXT→text、INT→integer、BIGINT→long、DECIMAL→decimal、TINYINT/BOOLEAN→boolean、DATE→date、DATETIME/TIMESTAMP→datetime）、PK/nullable/unique/length/precision/scale/default/comment
- 导入结果进入 Field Designer 可修改后再生成
- **密码只用于 JDBC 连接，不写入 Contract/日志/生成文件**

## 7. Excel Import

- 上传 .xlsx，模板表头：column/field/type/label/required/primaryKey/unique/length/comment + searchable/listVisible/formVisible/detailVisible/dictionary
- 模板下载入口：GET /api/modules/import/excel/template
- 零外部依赖解析（JDK zip + XML，支持 inlineStr / sharedStrings / 自闭合空单元格）

## 8. CustomerLite Proof

- Console Manual 创建 customer-lite（code/name/phone/status/level + searchable）→ Generate → **223 files**：CustomerLite entity/service/controller/DTO/migration V100__customer_lite.sql + 前端 ListView/DetailView/EditView/API/route

## 9. WarehouseLite Proof

- Console 创建 warehouse-lite（code/name/address/manager/status + warehouse_status dictionary）→ Generate → **223 files**：WarehouseLite 全栈
- **无 warehouse 专用 capability/template**：grep capabilities/ registry/ 无 warehouse-lite-reference / WarehouseLiteTemplate
- 同一 Generic Renderer + 不同 Contract = 不同完整 CRUD 模块

## 10. Generated Backend

每条链路（Manual/MySQL/Excel）生成项目均含：Entity、Mapper、Port、MyBatis Repository（DataScope 过滤 + searchable like）、Service（校验/唯一性/字典校验）、Controller（分页 + searchable 参数）、CreateRequest/UpdateRequest/Response DTO、Flyway migration（含 DEFAULT）

## 11. Generated Frontend

ListView（PageHeader + StatCard stats + SearchForm 含 searchable 输入框 + AppTable + StatusTag）、Create/Edit（AppForm + FormDrawer）、Detail、API client、router/menu 集成 —— 全部沿用 EP UI 2.0 视觉

## 12. Enterprise Feature Composition

按 contract.enterprise 组合：permissions（@RequirePermission + PermissionButton）、dataScope（DataPermissionContext 过滤）、menu（sys_menu seed）、dictionary（DictionarySelect + validateDictionary）、operationLog（@OperationLog）

## 13. Console Integration

- Business Modules 页面：模块列表（含状态）+ Create Module（Manual/MySQL/Excel 三 tab）+ Field Designer + Save + Generate + Delete
- 模块契约落盘 console-data/modules/*.yaml（与 CLI/generator 共享现有 Contract，无私有 schema）
- Projects 页可见生成的模块项目（name/profile/stack/modules/location/status）

## 14. Security

- MySQL 密码：仅 MySqlImportService JDBC 连接使用；不写入 Contract、不写日志、不进入生成文件
- 生成路径受现有 Ownership / PathSafety / Conformance 约束；Console 不绕过 Generation Executor 直接写目标工程

## 15. Compatibility

- 旧 Product/Supplier reference generation 不回归：V06Work002SupplierTest **13/13**、ProductReferenceWork005Test **10/10**
- 旧 manifest 兼容：V06Work001ContractTest **8/8**
- 既有 fixtures 结构（business 在 manifest 顶层）保持不变，V06Work002BGenericModuleTest **13/13**

## 16. Verification

| 项 | 结果 |
|---|---|
| A. Manual CustomerLite → Contract → Generate | PASS（223 files，全栈 CRUD） |
| B. MySQL table → Import → Field Designer → Contract → Generate | PASS（demo_customer 6 字段映射正确，223 files） |
| C. Excel → Import → Field Designer → Contract → Generate | PASS（5 字段模板导入，223 files） |
| D. WarehouseLite 同 Renderer，无专用模板 | PASS（223 files，grep 无 warehouse 专用资产） |
| E. 生成项目 backend compile + frontend test/build | PASS（customer-lite：mvn compile OK、pnpm test 46/46、pnpm build OK） |
| F. 旧 Product/Supplier reference 不回归 | PASS（13/13 + 10/10） |
| G. 旧 manifest 兼容 | PASS（8/8） |
| H. validate-assets / validate-registry | PASS |
| I. git diff --check clean | PASS |
| console-server 测试 | ConsoleBackendTest 10/10 + ModuleBuilderTest 4/4 |
| console-web 测试 | 6/6 |

## 17. Escalation

**NO** —— 未新建第二套生成链路；未改 Contract schema 语义 / Executor / Ownership / PathSafety；未引入 AI Dev OS。仅增强现有 Generic Renderer 元数据消费 + Console 建模/导入入口。

## 18. Changed Files

- `generator/generator-core`：GenericModuleGenerator（searchable 全链路 + defaultValue DEFAULT + port/controller/service/repository 签名）、GenericFrontendTemplates（searchable 输入框 + detailVisible + Query 类型）、V06Work002BGenericModuleTest（fixtures 字段断言同步）
- `tests/fixtures/v06-reference/generic/modules/`：customer-lite.yaml、warehouse-lite.yaml（WP 规格字段）
- `console/console-server`：ConsoleServer（模块路由）、GenerationService（generateWithModules + 动态 registry）、ModuleStore、MySqlImportService、XlsxSupport、ModuleBuilderTest、pom（+mysql-connector-j）
- `console/console-web`：BusinessModulesView.vue、api/console.ts（模块/MySQL/Excel API）、router

## 19. Known Limitations

- Excel 解析为最小实现（仅第一个 worksheet，支持 inlineStr/sharedStrings/自闭合空单元格；不含公式计算/样式）
- MySQL 导入为 schema introspection 快照；导入后字段需经 Field Designer 人工确认再生成
- ModuleStore 单文件 YAML 存储（无并发锁，单机 Console 场景）
- 生成项目 E 验收只跑了 compile + 相关 pnpm test/build（WP 预算内，未跑全量历史 gate）

## 20. git diff --stat

```
87 files changed, 2266 insertions(+), 370 deletions(-)
```

---

## 三问三答（WP 明确要求）

**新增第三个业务域是否还需要开发新的 xxx-reference capability？**

**NO。**

- V06-WORK-005 已证明：Manual / MySQL / Excel 三种方式创建的任意新业务域，只需一份 Business Module Contract（module manifest + business 段），现有 GenericModuleGenerator 即可生成完整前后端 CRUD
- CustomerLite / WarehouseLite / MySqlCustomer / ExcelWarehouse 四域均为零专用 capability / 零专用模板生成
- 新增业务域路径 = Console Field Designer（或导入）→ Contract → 现有 pipeline → 全栈产物

---

**V06-WORK-005 = PASS**（A-I 全部通过；未 commit、未 push；等待人工验收）
