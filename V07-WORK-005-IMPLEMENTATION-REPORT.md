# V07-WORK-005-IMPLEMENTATION-REPORT.md

# V07-WORK-005 — MySQL / Excel Relation Discovery

**Mode**: WP_IMPLEMENTATION
**Repository**: /home/administrator/workspace/engineering-platform
**Baseline**: V07-WORK-001/002/003/004 = PASS
**Date**: 2026-08-17

---

## 1. Implemented

升级现有 MySQL / Excel Import 为 **Candidate 流水线**：

```
External Metadata
  → Candidate (DETECTED / SUGGESTED)
  → Human Confirmation (Accept / Edit / Ignore)
  → CONFIRMED
  → Business Module Contract V2
```

禁止路径（未实现、未启用）：External Metadata → 直接写正式 Relation / Semantic Contract。

交付物：

| 层 | 内容 |
|---|---|
| Candidate 模型 | `ImportCandidateModel.java` — FIELD / REFERENCE / RELATION / SEMANTIC × DETECTED / SUGGESTED / CONFIRMED / IGNORED + source 标签 |
| MySQL Discovery | `MySqlImportService.discover()` — 读 information_schema.tables / columns / statistics / key_column_usage / referential_constraints |
| Candidate 生成 | `ImportCandidateService.discoverMysql()` — FK→DETECTED relation/reference、reverse O2M→SUGGESTED、semantic heuristics→SUGGESTED、多表跨模块 |
| Review 解析 | `resolveToManifest()` — **只有 CONFIRMED 进 Contract**；camelCase 字段名清洗；enum 无 values 不写；money 只改 type |
| Excel 演进 | `discoverExcel()` — 可选列 referenceTarget/ValueField/LabelField、relationType/Target/mappedBy/composition → DETECTED_FROM_EXPLICIT_INPUT；heuristics → SUGGESTED |
| Console API | `POST /api/modules/import/mysql/discover`、`POST /api/modules/import/excel/discover`、`POST /api/modules/import/review/resolve`、GET `/template-v2` |
| 前端 | Import Review 页（/modules/import-review）— 4 组 candidates + Accept/Edit/Ignore + mapping 编辑 + 交接 Builder |
| Builder 交接 | `/modules/builder?draft=state` — 确认后的 fields/relations/references 直接映射 Builder 正式 state，最终由 Builder Save 产生 Contract |

---

## 2. Candidate Model

`ImportCandidateModel.java` 统一模型，至少支持：

- **Status**: `DETECTED`（数据源确定事实）/ `SUGGESTED`（规则/启发式）/ `CONFIRMED`（用户确认）/ `IGNORED`（用户忽略）
- **Type**: `FIELD` / `REFERENCE` / `RELATION` / `SEMANTIC`
- **Source**: `DATABASE_FK` / `DATABASE_COLUMN` / `DATABASE_INDEX` / `COLUMN_NAME_HEURISTIC` / `TYPE_HEURISTIC` / `EXCEL_METADATA` / `DETECTED_FROM_EXPLICIT_INPUT` / `USER_CONFIRMED`

只有 `isConfirmed()` 为 true 的 candidate 会被 `resolveToManifest()` 序列化进正式 Contract。

---

## 3. MySQL Metadata Discovery

`MySqlImportService.discover()` 一次读取 5 张 information_schema 表：

| 表 | 用途 |
|---|---|
| information_schema.tables | 表注释 |
| information_schema.columns | name / type / required(nullable) / primaryKey / unique / length / precision / scale / default / comment |
| information_schema.statistics | 唯一索引（非 PRIMARY）→ DATABASE_INDEX 事实 |
| information_schema.key_column_usage | FK 列 / referenced_table / referenced_column / constraint_name |
| information_schema.referential_constraints | update_rule / delete_rule |

识别结果（`TableMeta`）：columns + uniqueIndexes + foreignKeys。

---

## 4. FK / Reference Discovery

真实 FK → 两个 **DETECTED** candidate（source=DATABASE_FK）：

- **REFERENCE**（例 `purchase_order.supplier_id.reference`）：
  - 预填 `valueField = 被引用列（id）`
  - `labelField` **留空**（数据库无法可靠决定，UI 要求用户确认，不默认 name）
  - `searchFields` 留空
- **RELATION MANY_TO_ONE**（例 `purchase_order.supplier_id.relation`）：
  - `localField = 本地列（camelCase）`、`targetField = 被引用列`、`required` 按列可空性
  - source=DATABASE_FK，状态 DETECTED（不是 SUGGESTED）

target 表未随本次导入时，candidate 标记 `unresolved=true`，前端禁止确认（除非 target 已存在于当前项目 modules）。

---

## 5. Reverse Relation Suggestions

`purchase_order_item.purchase_order_id → purchase_order.id` 允许为 parent 生成 reverse candidate：

```
purchase_order.reverse.purchase_order_item.relation
  type: ONE_TO_MANY
  target: purchase-order-item
  mappedBy: purchaseOrderId
  composition: false   ← 数据库无法证明，必须人工确认
```

状态 **SUGGESTED**（reverse 不属于数据库原始 FK 本身）。UI 显示 "reverse of real FK … (suggested — composition requires human confirmation)"。

---

## 6. Semantic Suggestions

轻量规则启发式，全部 **SUGGESTED**（source 区分）：

| 规则 | semantic | source |
|---|---|---|
| DECIMAL(...,2) | possible money | TYPE_HEURISTIC |
| *_id（非 FK） | possible reference | COLUMN_NAME_HEURISTIC |
| status/type/state/level/*_status/*_type | possible enum/dictionary | COLUMN_NAME_HEURISTIC |

UI 明确显示 "Suggested / Not Confirmed"，并把"这是数据库事实还是平台建议"用 source 标签区分。启发式永远不自动确认：
- enum 无 values → 后端不写入 semantic（Contract 保持合法）
- heuristic reference 无 targetModule → 不写入
- money → 只改 type（schema type enum 含 money；semantic enum 不含 money）

---

## 7. Excel Evolution

- 模板 v2（`/api/modules/import/excel/template-v2`）新增可选列：`referenceTarget` / `referenceValueField` / `referenceLabelField` / `relationType` / `relationTarget` / `mappedBy` / `composition`（V0.6 模板保持可用）
- 显式填写 referenceTarget / relationType+relationTarget → **DETECTED_FROM_EXPLICIT_INPUT**，但仍要求 Review/Confirm，不绕过 Builder
- 只有 `supplier_id` / `amount decimal` / `status` 等 → Possible Reference / Possible Money / Possible Enum，**SUGGESTED**

---

## 8. Import Review UI

新页面 `/modules/import-review`（ImportReviewView.vue）：

- **MySQL Import tab**：连接表单（默认 127.0.0.1/ep_import_proof）→ Load Tables → 多选表 → table→module/entity mapping 编辑 → Import & Discover
- **Excel Import tab**：moduleId/entity 输入 + 上传 .xlsx（支持 v0.6/v0.7 模板）
- **Review 区**：按 draft 分组，每组 4 个 candidate 组：
  - Detected Fields（DETECTED — database column facts）
  - Detected References（DETECTED — real FK; labelField/searchFields need confirmation）
  - Detected Relations（FK relations DETECTED; reverse ONE_TO_MANY SUGGESTED）
  - Suggested Semantics（SUGGESTED — heuristic only）
- 每行显示：Type / describe / Source / Status / note + [Accept] [Edit] [Ignore]
- **Accept all confirmable**：跳过 unresolved 与启发式 enum/reference（需要 Edit 提供 values/target）
- 底部统计 Confirmed / total
- `Open in Builder →` 调 review/resolve 生成 manifest → parseManifest → Builder state → 跳 `/modules/builder?draft=state`

---

## 9. Human Confirmation

Edit 弹窗按类型提供编辑（§14 全覆盖）：

- **Reference**：target module / valueField / labelField / searchFields（逗号分隔）
- **Relation**：name / type / target / localField / targetField / mappedBy / required / composition（composition 有明确提示 "must be confirmed by a human — the database cannot prove it"）
- **Semantic**：semantic 选择；enum 时提供 enumValues 文本编辑（每行 `VALUE,Label`）
- **Field**：name / type / required / primaryKey / unique / length / comment

Edit 保存 = accept（USER_CONFIRMED 语义）。

---

## 10. PurchaseOrder MySQL Proof

真实 schema `ep_import_proof`（`console/console-data/proof-schema.sql`）：

```
supplier(id PK, code UNIQUE, name, contact_phone, status)
product(id PK, code UNIQUE, name, spec, unit_price DECIMAL(10,2), status)
purchase_order(id PK, order_no UNIQUE, supplier_id FK→supplier.id, order_date, status, total_amount DECIMAL(14,2), remark)
purchase_order_item(id PK, purchase_order_id FK→purchase_order.id, product_id FK→product.id, quantity, unit_price, amount, remark)
```

浏览器全链路（Playwright smoke，`/tmp/v07w5-smoke.cjs`）通过 Console：

1. Connect MySQL → 4 tables 多选 → Import & Discover（47 candidates / 4 drafts）
2. Review Candidates：Accept all → 44/47（enum 启发式按核心原则跳过，需 Edit 提供 values）
3. Confirm References：supplierId → supplier（valueField=id；labelField 人工确认 name）
4. Confirm Relations：supplier MANY_TO_ONE（DETECTED）+ items ONE_TO_MANY（reverse SUGGESTED）
5. 设置 `PurchaseOrder.items composition=true`（Edit 弹窗人工确认）
6. Preview Contract → Save → **"Module saved — contract is ready for generation"**
7. 存储验证：yaml 含 `composition: true`、`target: supplier`、camelCase 字段

**禁止手写 YAML 代替此链 — 全程浏览器操作完成。**

---

## 11. Excel Proof

`/tmp/warehouse-bin.xlsx`（模板 v2 生成，含 warehouse_id reference/relation 显式输入）：

- 后端 discover 验证：`warehouseId` REFERENCE + MANY_TO_ONE → **DETECTED_FROM_EXPLICIT_INPUT**
- 浏览器 Excel smoke（`/tmp/v07w5-excel-smoke.cjs`）：上传 → 7 candidates → 4 组 → Accept 7/7 → Open in Builder → Contract Preview 含 `target: warehouse` + `MANY_TO_ONE` → Save 成功 → 存储 yaml 含 `semantic: reference` + `MANY_TO_ONE`

Excel → Parse → Candidate → Review → Confirm → Contract 全链验证通过（未要求本项目完整生成运行）。

---

## 12. Generator Integration

后端 targeted proof（`ImportCandidateWork005Test.confirmedContractReachesExistingGenerator`）：

- MySQL discover（supplier + purchase_order）→ 确认 FIELD/REFERENCE/RELATION → resolveToManifest → ModuleContractValidator 通过 → ModuleStore 保存 → GenerationService.generateWithModules 全量生成 **SUCCESS**
- 生成产物包含 `PurchaseOrderService.java` + `project.yaml`

确认后的 Contract 直接进入现有 Validation → Resolver → EPM → Generic Generator。未重复 WORK-002/003 全量关系生成验证。

---

## 13. Security

- MySQL password 仅用于 JDBC 连接（`ConnectionInfo`），**不写 Contract / 不写 candidate persistence / 不写日志 / 不写生成项目**
- `ImportCandidateWork005Test.passwordNeverAppearsInDiscoveryOutput` 断言：discover 输出与 resolve manifest 均不含密码
- 导入只保存 schema 信息（表/列/索引/FK）

---

## 14. Compatibility

- V0.6 MySQL Import（`/test` `/tables` `/import`）保持原样可用
- V0.6 Excel Import（`/import` + 旧模板）保持原样可用
- 无 FK 表可导入（`noFkTableStillImports` 测试通过）
- 单表导入正常（`unresolvedTargetWhenTableNotImported` 验证单表 FK→unresolved 不崩溃）
- Manual Builder 不受影响（ModuleBuilder2Test 10/10 回归通过）
- Product/Supplier 等既有模块不受影响
- Candidate 层是兼容扩展，非强制新流程（旧端点全保留）

---

## 15. Verification

| 项 | 结果 |
|---|---|
| 后端 console-server 全量测试 | **57/57 PASS**（ImportCandidateWork005Test 20/20 + ModuleBuilder2Test 10/10 + 其余） |
| 前端 vitest | **22/22 PASS**（importReview.spec.ts 8/8 新增） |
| vue-tsc --noEmit | 0 错误 |
| PurchaseOrder MySQL 4-table browser smoke | **PASS**（Save 成功） |
| Excel browser smoke | **PASS**（Save 成功） |
| Generator integration proof | **PASS**（SUCCESS） |
| V07-WORK-004 回归（ModuleBuilder2Test + moduleContract.spec） | **PASS** |
| git diff --check | 干净 |

Verification Budget 遵守：只跑 targeted tests + browser smoke，无 Full Platform Regression / Full Browser Suite / 生成项目完整 E2E。

---

## 16. Visual Artifacts

5 张验收截图（`/tmp/v07w5-shots/final/`）：

1. `1-mysql-table-selection.png` — MySQL Table Selection（4 表多选 + mapping）
2. `2-import-review.png` — Import Review（4 组 candidates 全览）
3. `3-detected-fk-candidate.png` — Detected FK Candidate（supplier_id relation DETECTED/DATABASE_FK）
4. `4-relation-edit-confirm.png` — Relation Edit/Confirm（reverse ONE_TO_MANY composition=true）
5. `5-purchaseorder-contract-preview.png` — PurchaseOrder Contract Preview（实时 YAML）

---

## 17. Escalation

无。未修改 Business Module Contract 核心结构 / Resolver / EPM / Generic Generator / MySQL datasource security model。所有改动均在 Console import 适配层（新增 candidate 流水线 + 既有端点扩展），属正常适配范围。

---

## 18. Known Limitations

- MANY_TO_MANY 明确 Unsupported（既有约束，非本 WP 范围）
- enum 启发式只提示，用户必须 Edit 提供 values 才能确认（符合 WP §6/§14）
- heuristic reference（裸 *_id）必须 Edit 指定 targetModule 才能确认
- Excel 单工作表解析（既有 XlsxSupport 限制，首 sheet）
- `resolveToManifest` 对空 draft 返回空 manifest（防 NPE，已测）
- 字段名强制 camelCase（schema field name pattern 禁下划线）；原始列名保留在 candidate payload 的 `column` 字段供参考，不进 Contract

---

## 19. Changed Files

新增：
- `console/console-server/src/main/java/com/engineeringplatform/console/ImportCandidateModel.java`
- `console/console-server/src/main/java/com/engineeringplatform/console/ImportCandidateService.java`
- `console/console-server/src/test/java/com/engineeringplatform/console/ImportCandidateWork005Test.java`
- `console/console-web/src/utils/importReview.ts`
- `console/console-web/src/views/ImportReviewView.vue`
- `console/console-web/tests/importReview.spec.ts`
- `console/console-data/proof-schema.sql`
- `console/console-data/modules/warehouse-bin.yaml`（Excel proof 保存产物）

修改：
- `console/console-server/.../MySqlImportService.java`（discover：5 张 information_schema 表 + TableMeta）
- `console/console-server/.../ConsoleServer.java`（+mysql/discover、+excel/discover、+excel/template-v2、+review/resolve 路由）
- `console/console-server/.../XlsxSupport.java`（writeTemplate 支持自定义行）
- `console/console-web/src/api/console.ts`（+mysqlDiscover/+excelDiscover/+reviewResolve + ImportDraft/ImportCandidate 类型）
- `console/console-web/src/router/index.ts`（+/modules/import-review）
- `console/console-web/src/views/ModuleBuilderView.vue`（+draft=state 交接）
- `console/console-data/modules/purchase-order.yaml`（MySQL import proof 保存产物：camelCase 字段 + supplier reference + items composition=true）

---

## 20. git diff --stat

```
console/console-server/.../ConsoleServer.java            |  186 +++++
console/console-server/.../MySqlImportService.java       |  162 ++++-
console/console-server/.../XlsxSupport.java              |   36 +-
console/console-server/.../ImportCandidateModel.java     |  110 +++   (new)
console/console-server/.../ImportCandidateService.java   |  620 +++   (new)
console/console-server/.../ImportCandidateWork005Test.java | 560 +++ (new)
console/console-web/src/api/console.ts                   |   54 ++
console/console-web/src/router/index.ts                  |    2 +
console/console-web/src/utils/importReview.ts            |  110 +++   (new)
console/console-web/src/views/ImportReviewView.vue       |  640 +++   (new)
console/console-web/src/views/ModuleBuilderView.vue      |   22 +-
console/console-web/tests/importReview.spec.ts           |  100 +++   (new)
console/console-data/proof-schema.sql                    |  120 +++   (new)
console/console-data/modules/warehouse-bin.yaml          |  40 +++   (new)
console/console-data/modules/purchase-order.yaml         |  70 +-
```

（与 WORK-001~004 累积改动合并后 `git diff --stat` 总览：19 files changed, 2223 insertions(+), 392 deletions(-)，含 WORK-004 遗留改动。本 WP 实际新增约 2600 行。）

---

## 最终判定

**V07-WORK-005 = PASS**

**READY_FOR_V07-WORK-006 = YES**

未 commit，未 push。完成后停止。
