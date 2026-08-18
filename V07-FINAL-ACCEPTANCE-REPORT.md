# V07-FINAL-ACCEPTANCE-REPORT.md — Engineering Platform V0.7 Release Acceptance & Closeout

日期：2026-08-18
Mode: RELEASE_ACCEPTANCE
Baseline: V07-WORK-001..006 = PASS

---

## 1. Executive Summary

V0.7（Business Modeling）最终版本验收完成。全部 Release Gate 通过：

- **Fresh V0.7 Golden Path PASS**（全新生成 `/tmp/v07final-out`，浏览器 Create 1 parent + 2 children / Edit reconciliation / Detail 全通过）
- **Backend Release Gate PASS**：generator-core 38 测试类 / 487 tests / 0 failures / 0 errors
- **Frontend Release Gate PASS**：Console-web vitest 22/22 + production build；generated enterprise-admin vitest 67/67（build 已在 Golden Path 验证）
- **Validator Gate PASS**：7 个 Python Contract Validators 全绿（scripts/validate.sh --python）
- **Transaction / Reference / DataScope / OperationLog PASS**（全新生成项目实测）
- **Runtime Gate PASS**（Status / Duplicate Start / Stop / Restart / 重启后可用）
- **Genericity Guard PASS**（无 PurchaseOrder-specific 生成器 / 无字段名猜语义 / 无第二套 Resolver）
- **Compatibility PASS**（V0.6 全量兼容测试绿）
- **Security / Secret / Architecture Boundary PASS**（无 secret 泄漏、无 AI/LLM/Codex 依赖、AgentAdapter 为 Agent-neutral 抽象）
- **Repository Hygiene PASS**（proof 产物已 .gitignore 隔离、无生成项目带入、fixture 分类正确）

**V0.7 RELEASE DECISION = GO**（见文末）

---

## 2. Release Scope Audit

V0.7 最终交付范围确认（全部实现并验收）：

| 能力 | 状态 |
|---|---|
| Business Modeling Contract V2 | ✅ |
| Field Semantics V2（reference/enum/money/date/dictionary/text） | ✅ |
| Reference Contract（target/labelField） | ✅ |
| MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE | ✅ |
| composition（Master/Detail） | ✅ |
| Relationship-aware Backend Generation（FK/引用校验/事务/reconciliation） | ✅ |
| Relationship-aware Frontend Generation（ReferenceSelect/EditableDetailTable） | ✅ |
| Business Module Builder 2.0 | ✅ |
| MySQL FK/Relation Candidate Discovery | ✅ |
| Excel Relation/Semantic Candidate | ✅ |
| Candidate Review / Human Confirmation | ✅ |
| Purchase Order Golden Path（Reference Scenario） | ✅ |

计划外能力：未引入（无新增关系类型、无 Contract 扩大、无 V0.8 功能）。

---

## 3. Minor Issue Disposition

### A. Enum / Status Backend Validation

**结论：生成器已有 Contract-driven enum 校验，无需重复实现（记录证据）。**

- `RelationBackendRenderer.enumValidation()` 在 create/update 均生成 `Set.of(...).contains(...)` 强校验（GenericModuleGenerator.java:623/730 调用），错误码 `<ENTITY>_<FIELD>_INVALID`。
- 该校验由字段 `semantic: enum + enum.values` 驱动（纯 Contract-driven，无业务名硬编码）。
- WORK-006 观察到 `NOT-A-REAL-STATUS` 被接受，根因是 **console 保存的 MySQL 导入契约 status 字段丢失 `semantic: enum`**（MySQL 无 enum 语义可推断 → 导入启发式局限），而非生成器缺失；正式 fixture（tests/fixtures/v07-reference/generic/modules/purchase-order.yaml）带完整 enum values。
- **处置**：记录为 V0.8+ backlog（richer import mapping：MySQL/Excel 导入时 enum/semantic 启发式推断），不修改产品逻辑。

### B. V0.6 Seed Test Assertion

**结论：旧断言因确定性 seed 生成变化过时，已更新测试到当前正式 deterministic behavior（不改产品逻辑）。**

- 根因：V0.6+ 将 capability seeds 改为条件插入 `INSERT ... SELECT ... WHERE NOT EXISTS`（幂等防冲突），旧断言仍期望 `VALUES (...)` 字面量。
- 修复：`V06Work002BGenericModuleTest.enterpriseIntegrationControlledByContract` 断言更新为 `SELECT 1000, 'customer_lite:item:read'` + `WHERE NOT EXISTS` 形式。
- 验证：V06Work002BGenericModuleTest **13/13 PASS**。

---

## 4. Repository Hygiene

| 检查项 | 结果 |
|---|---|
| git status / git diff --stat | 23 tracked 修改 + 31 untracked 新文件（全部为正式源码/模板/测试/fixture/报告） |
| target/ / dist/ / node_modules / .runtime / .generator | 未被 tracking（.gitignore 已覆盖） |
| 生成项目带入仓库 | 无（生成产物均在 /tmp，`/tmp/v07final-out` 验证后不入库） |
| console-data/modules proof 模块（product/purchase-order/purchase-order-item/supplier/warehouse-bin） | **已加入 .gitignore**（本次修复），不再可能误提交 |
| proof-schema.sql（WORK-005 临时 MySQL proof） | 已加入 .gitignore |
| console-data/generated/、final-accept/ | 已加入 .gitignore |
| 正式 fixture 位置 | tests/fixtures/v07-reference/generic/modules/（supplier/product/purchase-order/purchase-order-item）✅ |
| 历史 tracked 截图（docs/visual-smoke-v0*） | 属既有历史资产，非本次引入，不阻塞 |
| projects.json（console runtime 状态） | 记录 /tmp 生成历史，属 runtime 状态文件，随仓库保留但不含生成产物 |

---

## 5. Golden Path（Fresh V0.7）

全新版本级 Golden Path（从 Console 保存的正式 Contract 开始，未复用 WORK-006 调试产物）：

```
Supplier/Product/PurchaseOrder/PurchaseOrderItem
→ Console /api/generate（最新生成器）→ /tmp/v07final-out（SUCCESS）
→ 后端 mvn package ✅ → 前端 vue-tsc + vite build ✅
→ dev-start（backend :8084 / frontend :5180，H2 e2e + seed）
→ 浏览器验收
```

- **Create**：Supplier ReferenceSelect 下拉 2 选项 → Add 2 Items → Product ReferenceSelect 2 选项 → qty/price/amount → 一次 Save（API POST 200）→ 数据库：**1 parent**（PO-RE-594244, PENDING, supplier=1）+ **2 children**（prod1/qty2/price10/amount20、prod1/qty3/price15/amount45）✅
- **Edit**：改 existing（qty 2→5）→ 删一行 → 增一行（prod2/qty7/price25/amount175）→ 一次 Save（API PUT 200）→ **existing updated / missing deleted / new inserted** ✅
- **Detail**：重新打开 → parent 正确、items 2 行、amount 正确 ✅

---

## 6. Contract / Resolver / EPM

- Contract V2 + Field Semantics V2 + Reference Contract：generator/schemas/module.schema.yaml（+102 行）✅
- Resolver：BusinessModuleResolver（+368 行）处理 relations/references/relations 校验 ✅
- EPM：ResolvedBusinessModule / BusinessEntityField / ResolvedRelation / ResolutionError 扩展 ✅
- V07Work001ContractV2Test、V07Work002RelationBackendTest 全绿（见 Backend Gate）

---

## 7. Backend Release Gate

generator-core full test（排除前端 E2E build，避免与 Frontend Gate 重复等价 build）：

- **38 测试类 / 487 tests / 0 failures / 0 errors / 0 skipped**
- 覆盖：Contract V2、Relation Backend（Warehouse→WarehouseBin、composition 事务、reconciliation、FK/引用校验）、V0.6 兼容、PathSafety、Conformance、Platform Org/DataPermission、OperationLog、Runtime Recipe、IdString Contract、Release Gate E2E 等
- generator-contracts：随 full test 编译/测试通过
- Java 25 确认（openjdk 25.0.3）✅

---

## 8. Frontend Release Gate

- **Engineering Platform Console**：vitest **22/22 PASS**（api/contract/importReview/moduleContract）+ production build（vue-tsc + vite build）✅
- **Generated enterprise-admin**（/tmp/v07final-out/frontend）：vitest **67/67 PASS**（25 个 spec 文件，含 ReferenceSelect/EditableDetailTable/Master Create/Edit/Detail 关系相关）+ vue-tsc + vite build ✅（build 在 Golden Path 已验证，不重复等价 build）
- 关系相关重点（ReferenceSelect、EditableDetailTable、Master Create/Edit、Detail）均被生成前端测试与浏览器验收覆盖 ✅

---

## 9. Validator Gate

`scripts/validate.sh --python`（仓库正式命令）：

- ✅ validate-manifest.py
- ✅ validate-registry.py
- ✅ validate-resolver-contracts.py
- ✅ validate-generator-contracts.py
- ✅ validate-engineering-work-contracts.py
- ✅ validate-agent-execution-contracts.py
- ✅ validate-assets.py
- **VALIDATION OK**（exit 0）

V0.6 manifests：继续 PASS（validate-manifest/registry 全绿）。V0.7 invalid relation/reference fixtures：完整 Resolver 测试断言正确 FAIL（UNKNOWN_REFERENCE / RELATION_TYPE_UNSUPPORTED / ENUM_VALUES_REQUIRED / REFERENCE_CONFIG_ON_NON_REFERENCE / REFERENCE_TARGET_UNKNOWN / RELATION_TARGET_UNKNOWN / RELATION_NAME_DUPLICATE / COMPATIBILITY_FAILURE 等均在 full test 中验证）✅

---

## 10. Transaction / Reference Validation

- Create invalid productId → FAIL `PURCHASE_ORDER_ITEM_PRODUCT_ID_REFERENCE_NOT_FOUND`，parent 无孤儿 ✅
- Create invalid supplierId → FAIL `PURCHASE_ORDER_SUPPLIER_ID_REFERENCE_NOT_FOUND`（WORK-006 已证，回归无变化）✅
- Update reconciliation 失败 → 整体 rollback（WORK-006 已证）✅
- Master create @Transactional 一次保存 parent + children（Golden Path 实测定格）✅
- deterministic reconciliation：existing update / missing delete / new insert（Golden Path Edit 实测定格）✅

---

## 11. DataScope

- viewer 访问 PO detail → `PERMISSION_DENIED` ✅
- viewer 更新 PO → `PERMISSION_DENIED` ✅
- viewer 创建 child（指向 admin 的 PO）→ `PERMISSION_DENIED`（child 不能绕过 parent scope）✅
- PlatformOrgDataPermissionWork003Test 13/13 PASS ✅

---

## 12. OperationLog

- 生成项目 `/api/operation-logs` 存在 `PURCHASE_ORDER_CREATE` 日志（本次 Create 即产生）✅
- WORK-006 已证 `PURCHASE_ORDER_UPDATE` 同样记录（主资源为业务操作）✅

---

## 13. Runtime

在全新生成项目（/tmp/v07final-out）上执行：

- Start ✅（backend :8084 READY / frontend :5180 READY）
- Status ✅（RUNNING READY 双端）
- Duplicate Start ✅（幂等：already running，不重复启动）
- Stop ✅（双端 STOPPED，端口释放）
- Restart ✅（backend/frontend 重新 READY）
- Restart 后 Open Application ✅（health UP + 登录 OK）
- Runtime Recipe 为唯一运行事实源（dev-start/status/stop 均来自生成项目 scripts/）✅

---

## 14. Browser Acceptance

只跑 V0.7 必要 Golden Path + 最小验证（未跑无关 Playwright suite）：

- Purchase Order List → Create（EditableDetailTable + ReferenceSelect）→ Detail → Edit/Reconciliation：全部浏览器实测 PASS（见 §5）
- invalid reference：API 实测 FAIL（见 §10）✅
- DataScope：viewer 实测 PERMISSION_DENIED（见 §11）✅
- OperationLog：实测存在（见 §12）✅

---

## 15. Genericity Guard

扫描确认不存在：

- ❌ PurchaseOrderGenerator / PurchaseOrder-specific renderer：**无**
- ❌ moduleName == "purchase-order" 硬编码猜语义：**无**
- ❌ supplier/product/price/status 字段名猜业务语义：**无**（生成器仅按 Contract semantic/type 驱动）
- ❌ Console-specific Generator / 第二套 Resolver / Executor：**无**（Console 复用 generator-core 正式链路）
- ✅ PurchaseOrder 只是 Reference Scenario（fixtures/v07-reference 中与 supplier/product 同级）

---

## 16. Compatibility

- V0.6 single-table generic module：V06Work002BGenericModuleTest 13/13 ✅
- V0.6 Supplier：V06Work002SupplierTest 13/13 ✅
- Product：PlatformProductWork005Test 18/18 ✅（含 V0.6 兼容路径）
- CustomerLite / WarehouseLite / no-relations：V06 fixtures 测试全绿 ✅
- backend-only project / old manifests：v02-v06 fixtures 测试继续 PASS（full test 覆盖）✅
- 未为兼容测试改变旧 Contract 语义 ✅

---

## 17. Security / Secret Check

- MySQL password 不进入 Contract：fixtures/contracts 中无凭据 ✅
- proof-schema.sql 仅注释"local MySQL root used only for JDBC introspection; never persisted"，且已 .gitignore 隔离 ✅
- secret 不进 logs/generated files：生成项目 resources 中无密码/API key 泄漏 ✅
- relation generation 不绕 Ownership：departmentId/createdBy 由服务端会话注入（生成代码验证）✅
- PathSafety：PathSafetyTest 6/6 PASS ✅
- child ID 不能跨 parent 更新：reconciliation 按 parent 内 id 定位（WORK-006/Golden Path 验证）✅
- DataScope 守 parent boundary（见 §11）✅
- invalid reference 拒绝 + transaction rollback（见 §10）✅

---

## 18. Architecture Boundary

确认 Engineering Platform 仍只负责 Deterministic Contract → Generation → Build/Run：

- ❌ LLM / Agent / Prompt / Codex / OpenClaw orchestration / AI Dev OS dependency：**未引入**
- ❌ Low-code Page Designer / Workflow Engine / BI/Report platform：**未引入**
- AgentAdapter：仅 Agent-neutral 执行抽象（注释明确"Platform Core 不得依赖 OpenClaw SDK / Codex SDK / Claude SDK"），测试用 FakeAgentAdapter ✅
- 无 OpenAI/Anthropic/Codex/LLM 依赖扫描：干净 ✅

---

## 19. Known Non-blocking Issues

1. MySQL/Excel 导入的 enum/semantic 启发式推断未实现（V0.8 backlog：richer import mapping）——导入契约可能丢失 enum semantic。
2. generated admin bundle > 500kB（chunk size warning，non-blocking）。
3. V06 `enterpriseIntegrationControlledByContract` 旧断言已更新（§3B），非问题。
4. MANY_TO_MANY 明确保留未实现（V0.8 backlog，非阻塞）。

---

## 20. Release Blockers

**P0：无。P1：无。**

---

## 21. Changed Files

tracked 修改（23）：
- .gitignore、README.md
- capabilities/frontend-enterprise-management/asset.yaml
- console/console-data/modules/{customer-lite,excel-warehouse,mysql-customer}.yaml、projects.json
- console/console-server: ConsoleServer.java、GenerationService.java、MySqlImportService.java、XlsxSupport.java
- console/console-web: api/console.ts、router/index.ts、views/BusinessModulesView.vue
- generator-contracts: BusinessEntityField.java、ResolutionError.java、ResolvedBusinessModule.java
- generator-core: BusinessModuleResolver.java、GenericFrontendTemplates.java、GenericModuleGenerator.java、ManagementUiWork004Test.java、V06Work002BGenericModuleTest.java
- generator/schemas/module.schema.yaml

untracked 新增（31，均正式资产）：
- V07-ARCHITECTURE-PLAN.md、V07-WORK-001~006-IMPLEMENTATION-REPORT.md
- frontend-enterprise-management templates：EditableDetailTable/MoneyInput/MoneyText/ReferenceSelect/StatusSelect .vue.ftl
- console-server：ImportCandidateModel/Service、ModuleContractValidator、ImportCandidateWork005Test、ModuleBuilder2Test
- console-web：importReview.ts、moduleContract.ts、ImportReviewView.vue、ModuleBuilderView.vue、importReview.spec.ts、moduleContract.spec.ts
- generator-core：ResolvedRelation.java、MasterDetailBackendRenderer.java、MigrationRelationRenderer.java、RelationBackendRenderer.java、V07Work001ContractV2Test、V07Work002RelationBackendTest、V07Work003FrontendTest
- tests/fixtures/v07-reference/

---

## 22. git diff --stat

```
 .gitignore                                         |  10 +
 README.md                                          |  25 +-
 .../frontend-enterprise-management/asset.yaml      |  26 +
 console/console-data/modules/customer-lite.yaml    |  25 +-
 console/console-data/modules/excel-warehouse.yaml  |  25 +-
 console/console-data/modules/mysql-customer.yaml   |  30 +
 console/console-data/projects.json                 |   2 +-
 .../engineeringplatform/console/ConsoleServer.java | 186 +++++
 .../console/GenerationService.java                 |   9 +
 .../console/MySqlImportService.java                | 162 +++-
 .../engineeringplatform/console/XlsxSupport.java   |  36 +-
 console/console-web/src/api/console.ts             |  54 ++
 console/console-web/src/router/index.ts            |   2 +
 .../console-web/src/views/BusinessModulesView.vue  | 221 +----
 .../generator/contracts/BusinessEntityField.java   |  43 +-
 .../generator/contracts/ResolutionError.java       |  13 +
 .../contracts/ResolvedBusinessModule.java          |  22 +-
 .../generator/core/BusinessModuleResolver.java     | 368 ++++++++-
 .../generator/core/GenericFrontendTemplates.java   | 598 +++++++++++---
 .../generator/core/GenericModuleGenerator.java     | 889 +++++++++++++++++++--
 .../generator/core/ManagementUiWork004Test.java    |   4 +-
 .../core/V06Work002BGenericModuleTest.java         |   7 +-
 generator/schemas/module.schema.yaml               | 102 ++-
 23 files changed, 2437 insertions(+), 422 deletions(-)
```

---

## V0.7 RELEASE DECISION

**GO** ✅

GO 条件核验：

| 条件 | 结果 |
|---|---|
| Golden Path PASS | ✅ |
| Master/Detail PASS | ✅ |
| Transaction PASS | ✅ |
| Reference validation PASS | ✅ |
| DataScope PASS | ✅ |
| OperationLog PASS | ✅ |
| Backend Release Gate PASS | ✅（487 tests 0 fail） |
| Frontend Release Gate PASS | ✅（22 + 67 tests + build） |
| Validators PASS | ✅（7 validators） |
| Compatibility PASS | ✅ |
| Genericity Guard PASS | ✅ |
| Repository Hygiene PASS | ✅ |
| P0/P1 blocker | 无 |

**Engineering Platform V0.7 正式发布。**
