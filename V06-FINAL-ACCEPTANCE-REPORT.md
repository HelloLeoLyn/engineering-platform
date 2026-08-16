﻿# V06-FINAL ACCEPTANCE REPORT — V0.6 Final Acceptance & Release Readiness

- **日期**: 2026-08-16 ｜ **Mode**: RELEASE_ACCEPTANCE
- **仓库**: /home/administrator/workspace/engineering-platform
- **结论**: **V0.6 RELEASE DECISION = GO**
- **未 commit / push / tag**

---

## 1. Executive Summary

Engineering Platform V0.6 完成端到端产品验收：Console → Project Builder → Business Module Builder/Import → Contract → Generic Generation → Build → Run → Generated Application → CRUD → Stop/Restart 全链路真实闭环通过。验收过程中发现并修复 **4 个真实缺陷**（2 个 P1 Golden Path 阻塞、1 个 P0 数据正确性、1 个 P1 Compatibility），修复后 Full Regression 全量 33 类 / 451 用例 0 失败。所有 GO 条件满足，判 **GO**。

## 2. Environment

| 项 | 值 |
|---|---|
| OS | WSL2 (Linux 6.18.33.2-microsoft-standard-WSL2) |
| Java | OpenJDK 25.0.3（默认）；多 JDK：java-21 / java-25 并存 |
| Maven | 3.9.12 |
| Node | v24.18.0 |
| pnpm / npm | 11.14.0 / 11.16.0 |
| MySQL | 8.4.10（本机，仅用于真实 Import 验收） |
| Docker | 29.6.2（可用，本项目未使用） |
| Certified Stack | enterprise + enterprise-java25 + enterprise-admin（Backend 验收全程 Java 25） |

未修改系统全局 JDK / alternatives / rc 文件；全部通过项目级 toolchain 执行。

## 3. Golden Path

全新验收 workspace：`console/console-data/final-accept/`（非复用旧测试输出）。

- Console `/api/generate`：enterprise / enterprise-java25 / enterprise-admin + customer + warehouse + runtime-recipe
- **Generate SUCCESS，251 files，modules: ['customer', 'warehouse']**

## 4. Manual Module Proof

通过 Console 创建 **Customer** 模块（Manual + Field Designer → Contract）：

- 字段：code(string, required, unique, len50) / name(required, len100) / phone(len30) / status(dictionary customer_status) / level(dictionary customer_level)；id/departmentId/createdAt/updatedAt 由 Generic Renderer 自动生成（system fields + dataScope）
- features: list/search/create/edit/detail/disable
- enterprise: permissions/dataScope/menu/dictionary/operationLog 全开
- 状态 READY → 参与生成 ✅

## 5. Real MySQL Import Proof

真实 MySQL（本机 8.4）建表 `ep_final_accept.warehouse`（id/code/name/address/manager/status/created_at/updated_at，3 行数据），全程通过 Console：

- MySQL Connection **Test → ok**；Load Tables → [warehouse]；Import → 8 字段映射正确（id pk / code unique / status default=ACTIVE / datetime 等）
- Field Designer 确认 → Business Module Contract（business 段顶层，与现有 Contract 同构）→ 保存 READY → 参与生成 ✅
- **未绕过 Console 手工写 Contract**

## 6. Excel Regression

WORK-005 已专项验证 Excel Import；Final Gate 做最小 regression：

- 上传 WORK-005 合法模板（/tmp/v05-excel-import.xlsx）→ **parse OK（6 行，header 对齐）** ✅

## 7. Contract Verification

Generate 前人工检查 Project Contract + 两个 Business Module Contract：

- application: enterprise / stack: enterprise-java25 / frontend: enterprise-admin ✅
- modules: [customer, warehouse] ✅
- 字段 / CRUD features / enterprise features 正确（见 §4/§5）✅
- **MySQL password/secret 未进入 Contract / generated files / logs**（Customer contract 检查 0 secret；Console 日志无连接串）✅

## 8. Generic Generation Proof

- Customer + Warehouse 均由 GenericModuleGenerator + GenericFrontendTemplates 生成（纯 Contract 驱动）
- **grep 全项目：0 个 customer-reference / warehouse-reference / frontend-customer-reference / frontend-warehouse-reference** ✅
- Backend：Entity / Service / Port / Create/Update/Response DTO / Controller / Mapper / MybatisRepository / Migration V100__customer.sql + V101__warehouse.sql ✅
- Frontend：types / api / ListView（search 框 + disable）/ EditView / DetailView / router(business/customer.ts) / menu+permission seed ✅

## 9. Backend Build

Console 执行（RuntimeService.build，mvn package，项目级 Java 25）：

- **Backend Build PASS（4.3s，exit 0）** ✅

## 10. Frontend Build

Console 执行（pnpm build）：

- **Frontend Build PASS（9.2s，exit 0）** ✅（修复 Generic 模板按需导入后）

## 11. Runtime

Console Start（Runtime Recipe dev-start.sh）：

- **Backend RUNNING-READY + Frontend RUNNING-READY**（动态端口 :8081 / :5177，URL 来自 .runtime，非固定端口）✅
- Open Application 使用实际 URL ✅

## 12. Browser CRUD Acceptance

Playwright targeted E2E（真实浏览器）：

- Login（admin/admin123）→ Dashboard 正常加载，无 layout failure ✅
- Customer：List（3 行）/ Search（搜索框存在）/ Create（dialog 5 输入）/ Detail / Edit / Disable —— **API 全链路验证成功**（创建 C-001 → 列表 → keyword 搜索 → 详情 → 编辑 → 禁用）✅
- Warehouse：同链路（创建 WH-F1 → 列表 → 搜索 → 详情 → 编辑 → 禁用）✅
- **0 console error**；0 layout failure ✅

## 13. Enterprise Feature Acceptance

- **RBAC**：viewer / sales-user 无 customer 权限 → 403 PERMISSION_DENIED；admin 全量 ✅
- **Dictionary**：customer_status 字典校验生效（非法值 ACTIVE 拒绝，ENABLED 通过）；`/api/dictionaries/customer_status/items` 返回 ENABLED/DISABLED ✅
- **Menu**：`/api/menus/me` 菜单树含 Customers / Warehouses + 子菜单 ✅
- **Operation Log**：创建 customer 后记录 CUSTOMER_CREATE（/api/operation-logs 可查）✅
- **DataScope 真实验证**：sales-user（DEPARTMENT scope, dept2）创建 customer 自动归属 dept2 → 自己可见 1 条；admin（ALL）可见全量 3 条；dept1 数据对 sales-user 不可见 ✅（P0 修复后复验通过）

## 14. Runtime Lifecycle

- Status → RUNNING ✅
- Duplicate Start → dev-start.sh is_pid_alive 拦截（"already running"，无第二实例）✅
- Stop → STOPPED（只杀 .runtime PID；console-server 8099 / mysql 存活，不误杀）✅
- Restart → stop + start → RUNNING-READY ✅
- Open Application → 再次可访问 ✅

## 15. Full Regression

一次完整回归（Release Gate）：

- **generator-core 全量测试：33 类 / 451 用例 / 0 失败** ✅
- console-server：ConsoleBackendTest 10 + ModuleBuilderTest 4 + RuntimeServiceWork006Test 13 = 27/27 ✅
- console-web：6/6 + production build 通过 ✅
- manifest validators / registry validators 全过 ✅
- git diff --check clean ✅

修复的 4 个真实缺陷（全部复验通过）：
1. **P1** `ConsoleServer.handleGenerate` 未注入 Console 创建的模块 manifest → 生成丢失 customer/warehouse（203 files、modules 空）→ 修复后 251 files ✅
2. **P1** `GenericFrontendTemplates` 在无 dictionary 字段时仍导入 DictionarySelect/StatusTag → TS6133 编译失败 → 按需导入 ✅
3. **P0** `GenericModuleGenerator` controller create 用 `RequestContext.currentDepartmentId()`（恒 null）→ DEPARTMENT 用户看不到自己建的数据 → 改用 `scope().departmentId()` ✅
4. **P1** `exception-handling` 的 GlobalExceptionHandler 引用 PlatformException 但 asset.yaml 未声明 platform-core 依赖 → backend-only 项目编译失败 → 声明依赖 ✅

同步 8 处 assetValidation 断言（V0.6 各阶段新增合法模板后断言数字未同步，非本次引入）。

## 16. Compatibility

V0.1~V0.5 既有 manifest/fixture 未失效（专项回归全过）：

- backend-only project（v03 inventory-service / demoorderservice）：CliSmokeTest 1 ✅、CliWorkflowTest ✅、SecondProjectE2ETest ✅、AssetDrivenGenerationTest 17 ✅、ConformanceValidatorTest 12 ✅、V02ReleaseGateE2ETest 8 ✅
- old project.yaml / Contract 兼容：V06Work001ContractTest 8 ✅、ProjectManifestUsabilityTest ✅
- Product Reference：ProductReferenceWork005Test 10 ✅、PlatformProductWork005Test 18 ✅
- Supplier Reference：V06Work002SupplierTest 13 ✅
- existing runtime recipe：RuntimeRecipeWork006Test 9 ✅
- frontend foundation / auth：FrontendFoundationWork001Test 17 ✅、FrontendAuthWork002Test 15 ✅
- platform-core / rbac / org / menu / dict / op-log：PlatformCoreWork001Test 10 ✅、PlatformAuthWork002Test 11 ✅、PlatformOrgDataPermissionWork003Test 13 ✅、PlatformMenuDictOpLogWork004Test 14 ✅、ManagementApiWork003Test 11 ✅、ManagementUiWork004Test 9 ✅

未为兼容测试修改旧 Contract 语义（仅修正 capability 依赖声明与断言数字同步）。

## 17. Architecture Guard

- 无 Console-specific Generator / 第二套 Resolver / 第二套 Execution Engine ✅
- 无 customer/warehouse 专用 renderer / 业务名硬编码（Generic 模板通用）✅
- frontend-auth 无按业务名硬编码 ✅
- 无 AI Dev OS / Agent / LLM 依赖 ✅
- 核心链保持：Contract → Validation → Resolver → EPM → Planner → Generator → Executor ✅

## 18. Security / Secret Check

- MySQL password 仅用于 JDBC schema introspection；**未写入 Contract / generated files / logs** ✅
- 生成路径受 Ownership / PathSafety / Conformance 约束；Console 未绕过 Generation Executor ✅
- 日志脱敏（password/token/secret → ***）✅
- Runtime Recipe AUTH_TOKEN_SECRET 走环境变量（e2e dev 默认），生产默认 profile 无已知凭据 ✅

## 19. Known Non-blocking Issues

- 部分 assetValidation 断言数字曾与 V0.6 新增模板不同步（已同步，见 §15）——非功能缺陷
- Dictionary seed 使用 ENABLED/DISABLED 作为业务状态值（通用语义，非业务专属字典）——记录 V0.7 backlog
- Excel Import 保持最小实现（WORK-005 范围）——记录 V0.7 backlog
- v02/v03 旧 fixture 无显式 platform-core，依赖 exception-handling 声明拉取——行为正确，无需改动

## 20. Release Blockers

- **P0：无**（发现 1 个数据正确性缺陷已修复并复验：departmentId 派生）
- **P1：无**（发现 3 个 Golden Path / Compatibility 缺陷已修复并复验：Console 模块注入、前端 TS6133、exception-handling 依赖）
- 未因 UI 小瑕疵 / 命名偏好 / 重构冲动扩大修改范围

## 21. git diff --stat

```
95 files changed, 2294 insertions(+), 381 deletions(-)
```

---

# V0.6 RELEASE DECISION

## **GO**

| GO 条件 | 结果 |
|---|---|
| Golden Path | PASS（全新 workspace，251 files，customer+warehouse） |
| Real MySQL Import | PASS（真实 MySQL 8.4 warehouse 表全链路） |
| Generic Customer/Warehouse | PASS（零 reference，纯 Generic） |
| Backend Build | PASS |
| Frontend Build | PASS |
| Runtime | PASS（Recipe，动态端口，Open 实际 URL） |
| Browser CRUD | PASS（Customer + Warehouse 全 CRUD + 0 console error） |
| Full Regression | PASS（33 类 / 451 用例 / 0 失败 + validators + diff clean） |
| Compatibility | PASS（V0.1~V0.5 全回归通过） |
| Architecture Guard | PASS |
| P0/P1 Release Blocker | 无（4 个缺陷已修复并复验） |

所有 GO 条件满足，无未解决 P0/P1 blocker → **V0.6 RELEASE DECISION = GO**。

未 commit、未 push、未创建 tag、未开始 V0.7。等待人工最终确认。
