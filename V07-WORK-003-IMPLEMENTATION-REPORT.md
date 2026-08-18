# V07-WORK-003 实现报告 — Relationship-aware Generic Frontend Generation

- **阶段**: V07-WORK-003（V0.7 关系感知通用前端生成）
- **日期**: 2026-08-17
- **结论**: **PASS** — 全部验证通过，READY_FOR_V07_WORK_004 = **YES**
- **状态**: 未 commit 未 push（等人工验收）

---

## 1. 任务定义

按 PurchaseOrder 结构图方向实现 **Relationship-aware Generic Frontend Generation**：
master 字段按 Contract 字段类型映射关系感知控件，items 子表可编辑；**全部做成契约驱动的通用生成能力，禁止 PurchaseOrder 专用页面**。

要求控件映射（全部由字段类型/语义驱动，非模块专用）：

| 字段 | 控件 |
|---|---|
| reference 语义 | ReferenceSelect（引用选择器，跨模块 api 加载） |
| date 类型 | el-date-picker（DatePicker） |
| enum 语义 | StatusSelect（枚举/字典下拉） |
| money 类型 | MoneyInput（编辑）/ MoneyText（详情展示） |
| 子表 O2M 组合 | EditableDetailTable（行内编辑 + Add Row / Remove Row） |

## 2. 交付内容

### 2.1 新增 5 个通用前端组件模板（capabilities/frontend-enterprise-management/templates/src/components/）

| 组件 | 职责 |
|---|---|
| `ReferenceSelect.vue.ftl` | 通用引用选择器：接收目标模块 api + valueField/labelField，按关键词加载选项 |
| `EditableDetailTable.vue.ftl` | 通用主从可编辑表格：columns 契约驱动，行内 reference/select/date/money/number/text 编辑，Add Row / Remove Row |
| `MoneyText.vue.ftl` | 金额展示（precision/scale 格式化） |
| `MoneyInput.vue.ftl` | 金额输入（precision 绑定） |
| `StatusSelect.vue.ftl` | 枚举/字典下拉（options 由 Contract enum 生成） |

全部登记进 `asset.yaml`（files + conformance requiredFiles），与 Framework 组件平级。

### 2.2 GenericFrontendTemplates.java 改造

- `generateFrontend(module, allModules)`：签名加全模块上下文（childModule、referenceTargets、masterChild）
- `typesSource`：master 实体加 `items?: ChildEntity[]`；Create/UpdateRequest 加 `items: ChildItemInput[]`；生成 `ChildItemInput` 接口（排除 mappedBy 父 FK）
- `detailViewSource`：money → MoneyText、items → 明细表格
- `editViewSource`：reference → ReferenceSelect、date → el-date-picker、enum → StatusSelect、money → MoneyInput、子表 → EditableDetailTable；reference 目标模块 api import；submit 带 items
- 新增 helpers：hasReference / hasEnum / enumOptionsExpr / scaleOf / targetApiVar / masterChildMappedBy
- `GenericModuleGenerator`：调用处传 modules

### 2.3 验证测试

`V07Work003FrontendTest.java`（generator-core）A-E 五组：
- A `relationshipComponentsExist`：capabilities key + 5 个组件 .ftl 存在
- B `masterEditViewRelationshipControls`：EditView 含 ReferenceSelect/supplierApi/label-field、el-date-picker/value-format、StatusSelect 枚举值、MoneyInput/precision、EditableDetailTable/form.items/productApi；组件文件含 detail-add-row/detail-remove-row
- C `masterDetailViewItemsAndMoney`：DetailView 含 MoneyText/row.items/detail-items；types 含 items/ItemInput 接口
- D `v06ModuleNoRelationLeakage`：V0.6 模块无任何关系控件泄漏
- E `generatedProjectFrontendTestAndBuild`：生成 v07 项目 → 断言组件文件 → pnpm install/test/build 全绿

## 3. 验证结果

| 验证项 | 结果 |
|---|---|
| A-D 快速断言组 | 4/4 PASS |
| E 组（生成项目 pnpm install/test/build） | PASS（EXIT 0） |
| 全量 A-E | **5/5 PASS（BUILD SUCCESS）** |
| V07Work001ContractV2Test 回归 | 14/14 PASS |
| V07Work002RelationBackendTest 回归 | 13/13 PASS |
| V06Work002BGenericModuleTest 回归 | 13/13 PASS |
| V06Work002SupplierTest 回归 | 13/13 PASS |
| ManagementUiWork004Test 回归 | 9/9 PASS（资产数断言 29→34 同步） |
| ConformanceValidatorTest | 12/12 PASS |
| git diff --check | 干净（EXIT 0） |

## 4. 本轮修复的 9 类缺陷（通用生成器边界，V0.6 features 全配从未暴露）

1. **money 类型缺失**：tsType/tsDefault 漏 money case → 生成 string 与 number sample 冲突（TS2322/TS2345）
2. **types 缺子模块 import**：master types 引用 `PurchaseOrderItem` 未 import → TS2304
3. **departmentId 重复**：模块自带 departmentId 字段 + dataScope 追加 → TS2300/TS2717
4. **EditView 缺 ItemInput import**：`items: [] as XxxItemInput[]` 未导入类型 → TS2304
5. **ListView submit body 缺 items**：Create/UpdateRequest 现在必填 items → TS2345（body 补 `items: []` + 签名加 childModule）
6. **child 模块误生成 EditView**：features 只有 [list, detail] 却生成 EditView 引用 Api.update → TS2339（按 create||edit 条件化生成）
7. **permissions:false 模块 openCreate 未用**：模板无 create 按钮但 script 生成 openCreate → TS6133（openCreate 条件加 permissions）
8. **ListView 表单 refs / enabledCount 无条件生成**：无 create/edit 或无 status 字段时 TS6133（按 features/statusField 条件化；`filter(r => true)` → `filter(r => r.status...)` 仅在 status 存在时生成）
9. **api spec post/put spy 无条件生成**：child 模块无 create/edit/disable 时 TS6133（post 需 create||disable，put 需 edit）+ EditableDetailTable 模板死代码（labelOf/_componentRef/Component import）清理 + apiSource CreateRequest/UpdateRequest import 按 features 条件化

## 5. 架构铁律遵守确认

- ✅ 无 PurchaseOrder 专用页面/组件——ReferenceSelect/EditableDetailTable/MoneyText/MoneyInput/StatusSelect 全部是通用组件，列定义来自 Contract
- ✅ 零业务名硬编码：组件通过 props（columns/api/valueField/labelField/options）接收契约数据
- ✅ 关系结构化声明：主从/引用关系来自 ResolvedRelation，非字符串匹配
- ✅ schemaVersion 未升
- ✅ 前端资产沿用 frontend-enterprise-management，未重写 V0.6 前端生成
- ✅ 未 commit 未 push，未动 v0.6.0 tag

## 6. 遗留与下一步

- V07-WORK-003 代码未 commit 未 push，等人工验收
- V07-WORK-001 / V07-WORK-002 同样未 commit（之前已交付报告）
- 下一步候选：V07-WORK-004（或按用户指示收口/提交）

---

**V07-WORK-003 = PASS**
**READY_FOR_V07_WORK_004 = YES**
