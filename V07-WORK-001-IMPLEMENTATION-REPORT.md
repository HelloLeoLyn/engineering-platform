# V07-WORK-001 — Business Modeling Contract V2 (Implementation Report)

- **日期**: 2026-08-16 ｜ **Mode**: WP_IMPLEMENTATION
- **Baseline**: v0.6.0 已发布（tag v0.6.0 = d86dd7d；main = e46fe8b）
- **状态**: 实现完成，本地验证全绿，未 commit（等人工确认）

---

## 1. Objective（本 WP 交付）

把 V0.6 Business Module Contract：

```
Fields + CRUD Features + Enterprise Features + Frontend Metadata
```

扩展为：

```
Fields + Field Semantics V2 + References + Relations
      + CRUD Features + Enterprise Features + Frontend Metadata
```

完整链路：**Business Module Contract → Validation → BusinessModuleResolver → ResolvedBusinessModule → EPM** 携带业务关系信息。

**未实现（按任务边界）**：后端关系生成 / 前端关系生成 / Builder 2.0 UI / MySQL·Excel 关系发现 / Purchase Order 专用逻辑。

---

## 2. Implemented（代码变更）

### 2.1 generator/schemas/module.schema.yaml（+102 行，兼容扩展）

| 扩展点 | 内容 |
|---|---|
| `entity.fields[].type` | 枚举扩展：`money, enum, status, reference, image, file, richtext`（保留 V0.6 全部 8 类型） |
| `entity.fields[].semantic` | 枚举扩展：`reference, enum`（保留 V0.6 全部 6 语义） |
| `entity.fields[].reference` | 新对象：`target`(必填)/`valueField`/`labelField`/`searchFields`；显式注明"Contract 描述业务引用，transport 由 Generator 决定，禁止写死 HTTP endpoint" |
| `entity.fields[].enum` | 新对象：`values`（`{value 必填, label}` 数组，minItems 1，uniqueItems） |
| `entity.fields[].frontend` | 新增 `searchable`/`placeholder`/`sortable`；保留 `searchVisible`（V0.6 遗留键，等价 searchable——统一审计发现的不一致） |
| `business.relations` | 新数组：`name`/`type`/`target`/`localField`/`mappedBy`/`targetField`/`required`/`composition`；`type` 枚举 `MANY_TO_ONE/ONE_TO_MANY/ONE_TO_ONE/MANY_TO_MANY`（MANY_TO_MANY 预留但 V0.7 明确 unsupported） |
| 字段名 pattern | 放宽 `^[a-z][a-z0-9]*$` → `^[a-z][a-zA-Z0-9]*$`（支持 camelCase：supplierId/purchaseOrderId/orderNo；V0.6 全小写字段仍为合法子集） |
| schemaVersion | **保持 1，不升级** |

### 2.2 generator-contracts（3 文件修改 + 1 新文件）

- **新增 `ResolvedRelation`** record：`(name, type, target, localField, mappedBy, targetField, required, composition)`；compact constructor 默认 `targetField="id"`
- **`BusinessEntityField`**：新增 `reference`(Map) + `enumValues`(List<Map>)；保留 13 参 V0.6 兼容构造器（默认空）
- **`ResolvedBusinessModule`**：新增 `relations`(List<ResolvedRelation>)；保留 8 参 V0.6 兼容构造器（默认空）
- **`ResolutionError`**：新增通用 `of(code, message, source, sourcePath, referenceId)` ERROR 工厂

### 2.3 generator-core：BusinessModuleResolver（+368 行）

- `parseEntity`：解析 `reference` 配置 + `enum.values` → `BusinessEntityField` 新字段
- `parseRelations`：解析 `business.relations` → `ResolvedRelation` 列表，含确定性校验
- **确定性校验（非法即 ERROR，无 silent fallback）**：

| 错误码 | 触发条件 |
|---|---|
| `REFERENCE_CONFIG_REQUIRED` | semantic=reference 但无 reference config |
| `REFERENCE_TARGET_REQUIRED` | reference config 无 target |
| `REFERENCE_TARGET_UNKNOWN` | reference target 模块不存在 |
| `REFERENCE_CONFIG_ON_NON_REFERENCE` | 非 reference semantic 却带 reference config |
| `REFERENCE_FIELD_UNKNOWN` | valueField(显式)/labelField(显式)/searchFields 不存在于目标模块（id 为隐式系统主键） |
| `ENUM_VALUES_REQUIRED` | type/semantic=enum 但无 enum.values |
| `ENUM_CONFIG_ON_NON_ENUM` | 非 enum 字段却带 enum config |
| `ENUM_VALUE_REQUIRED` / `ENUM_VALUE_DUPLICATE` | enum entry 无 value / value 重复 |
| `RELATION_MALFORMED` | relation 条目非对象 |
| `RELATION_NAME_REQUIRED` / `RELATION_NAME_DUPLICATE` | relation 无 name / 同名重复 |
| `RELATION_TYPE_UNKNOWN` | 未知 relation type |
| `RELATION_TYPE_UNSUPPORTED` | **MANY_TO_MANY（预留但 V0.7 明确拒绝）** |
| `RELATION_TARGET_UNKNOWN` | relation target 模块不存在 |
| `RELATION_LOCAL_FIELD_UNKNOWN` | M2O/O2O 的 localField 不在本模块字段 |
| `RELATION_MAPPED_BY_UNKNOWN` | O2M 的 mappedBy 不存在于 target 模块 |

- 关系/引用**只来自结构化 Contract**——零字段名推断（`supplierId`/`productId` 仅当声明 `semantic: reference` + `reference:` 才成为引用）

---

## 3. Fixtures（tests/fixtures/v07-reference/generic/）

```
project.yaml                        # modules: supplier, product, purchase-order, purchase-order-item
modules/
  supplier.yaml                     # V0.6 契约（无 relations）——reference target
  product.yaml                      # money 字段 + enum 字段
  purchase-order.yaml               # 主模块：reference(supplier) + enum(status) + money + ONE_TO_MANY(composition)
  purchase-order-item.yaml          # 子模块：reference(product) + MANY_TO_ONE(purchaseOrder) + composition
```

---

## 4. Verification（本地）

### 4.1 新测试 V07Work001ContractV2Test — **14/14 PASS**

| 分组 | 测试 | 验证点 |
|---|---|---|
| A 兼容 | `v06ManifestResolvesWithoutRelations` | V0.6 manifest 无 relations/reference/enum 正常解析，relations 为空 |
| A 兼容 | `v06ManifestStillGenerates` | V0.6 manifest 仍走完整 generate（CustomerLite/WarehouseLite 生成成功） |
| B 新语义 | `v07ManifestResolvesRelationsReferencesAndEnums` | relations/reference/enum/money 全部结构化解析进 EPM |
| C 结构化 | `fieldNameAloneNeverCreatesRelations` | productId 字段名不产生关系；只有声明的关系存在 |
| D 非法报错 | `duplicateRelationNameFails` 等 10 个 | 全部确定性 ERROR 码（见 §2.3 表） |

### 4.2 V0.6 回归 — **34/34 PASS**
- V06Work002BGenericModuleTest 13/13 ✅
- V06Work002SupplierTest 13/13 ✅
- V02ReleaseGateE2ETest 8/8 ✅

### 4.3 Python schema validators — **全过**
- `validate-manifest.py --all`：8 示例（4 合法 PASS + 4 非法 rejected）+ platform.yaml PASS ✅
- v07 新 fixtures ×4 用新 module.schema 校验：PASS ✅
- v06 fixtures ×2 + generic-supplier 用新 schema 校验（兼容双确认）：PASS ✅

### 4.4 git diff --check — CLEAN

---

## 5. Compatibility（核心证明）

- **schemaVersion 保持 1**；新属性全部可选
- V0.6 manifest（customer-lite/warehouse-lite/supplier）在新 schema 下校验 PASS + resolver PASS + 生成行为不变（relations 默认空列表走 V0.6 代码路径）
- `searchVisible` 保留为遗留键，新代码/Console 统一 `searchable`
- record 扩展保留 V0.6 构造器，外部调用零破坏（编译全绿证明）

---

## 6. 边界确认（本 WP 未做）

- ❌ 后端关系生成（Entity items/FK/事务）——WORK-002
- ❌ 前端关系生成（ReferenceSelect/EditableDetailTable）——WORK-003
- ❌ Business Module Builder 2.0 UI——WORK-004
- ❌ MySQL/Excel 关系发现（FK candidate + NEEDS_CONFIRMATION）——WORK-005
- ❌ Purchase Order 专用逻辑——Reference Scenario 仅作为 fixture 数据

---

## 7. Changed Files

```
M  generator/schemas/module.schema.yaml
M  generator/generator-contracts/.../BusinessEntityField.java
M  generator/generator-contracts/.../ResolvedBusinessModule.java
M  generator/generator-contracts/.../ResolutionError.java
A  generator/generator-contracts/.../ResolvedRelation.java
M  generator/generator-core/.../BusinessModuleResolver.java
A  generator/generator-core/src/test/.../V07Work001ContractV2Test.java
A  tests/fixtures/v07-reference/generic/（project.yaml + modules/×4）
```

5 modified + 3 added（+527/-21），未 commit。

---

## 8. 遗留跟踪（不阻塞本 WP）

- **V06 CI corrective**：`e46fe8b` 已 push 但远程 CI 仍双 FAIL（升级 actions 非根因、annotations 为空、日志 403），根因未最终确认——搁置待指示
- V0.7 WORK-002（后端关系生成）按架构计划推进

---

**V07-WORK-001 实现完成，等待人工确认。**
