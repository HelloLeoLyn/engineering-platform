﻿# V06-WORK-002B RESULT — Contract-driven Generic Module Generator

- **日期**: 2026-08-16 ｜ **Mode**: WP_ACCEPTANCE
- **仓库**: /home/administrator/workspace/engineering-platform
- **状态**: 实现完成，专项验证全绿，**未 commit / push**

---

## Implemented

真正 Contract-driven 的 Generic Business Module Generator（不再是"每个业务实体建一个 *-reference 资产"）：

1. **新 core 组件 `GenericModuleGenerator`**（generator-core）
   - 输入：`EPM.businessModules[]`（WORK-001 的 ResolvedBusinessModule）
   - 输出：后端 + 前端完整文件集，**无名称分支**（不识别 supplier/customer/product 任何名字）
   - 接入 AssetProjectGenerator 现有 `addFile → GenerationPlanner → GeneratorExecutor` 链路（Ownership/PathSafety/Conformance 全部保留，不建第二套 Execution Engine）

2. **后端动态生成**（字段全部来自 Contract）
   - Entity / CreateRequest / UpdateRequest / Response / Port / Mapper / MybatisRepository / Service / Controller / BeansConfig / migration / seed / ModelUnitTest
   - 类型映射：string/text→String、integer→Long、number/decimal→BigDecimal、boolean→Boolean、date→LocalDate、datetime→LocalDateTime
   - features 控制实际能力：list/search/create/edit/detail/disable 只生成声明的方法/端点
   - enterprise 控制集成：permissions（@RequirePermission+seed）、dataScope（departmentId+scope 查询）、menu（sys_menu seed）、dictionary（字典校验+seed）、operationLog（@OperationLog）

3. **前端动态生成**（复用 enterprise-admin 组件）
   - types / api / router(business/*.ts) / ListView / DetailView / EditView + 4 个 Vitest spec
   - 组件复用：AppTable/AppForm/SearchForm/DictionarySelect/StatusTag/ConfirmAction/PermissionButton/PageHeader/PageContainer
   - 路由经统一 `businessRoutes` 导出被 frontend-auth glob 聚合

4. **移除硬编码注册**
   - frontend-auth router 不再有 product/supplier glob → `import.meta.glob('../router/business/*.ts')` 通用聚合
   - product/supplier Reference 路由迁移到 `frontend/src/router/business/` 目录，统一 `businessRoutes` 导出
   - 新增 customer/order/warehouse 零改动 frontend-auth

5. **确定性 ID 分配**（无手写固定 ID）
   - 模块按 id 排序，每个模块分配 ID 段：permission 1000+idx*8 / role_permission 2000+idx*8 / dictionary 3000+idx*8 / menu 5000+idx*8 / migration V100+idx
   - 相同 Contract 重复生成 byte-identical（L 验收）

## Acceptance 对照（A-M）

| 项 | 结果 |
|---|---|
| A. EPM.businessModules 驱动 generation | ✅ |
| B. Generic Backend CRUD generation | ✅ |
| C. Generic Frontend CRUD generation | ✅ |
| D. Contract fields 改变 → generated fields 改变 | ✅ |
| E. Contract features 改变 → generated capabilities 改变 | ✅ |
| F. Dictionary/DataScope/RBAC/Menu/OpLog 由 Contract 控制 | ✅ |
| G. CustomerLite 无专用 asset 生成成功 | ✅ |
| H. WarehouseLite 无专用 asset 生成成功 | ✅ |
| I. Supplier 脱离 supplier-reference 走 Generic | ✅ |
| J. frontend-auth 无 product/supplier glob | ✅ |
| K. 新模块无需新增 *-reference capability | ✅ |
| L. regeneration deterministic | ✅ |
| M. 旧 Product/Supplier Reference 回归 | ✅ |

## Verified

| 项 | 结果 |
|---|---|
| V06Work002BGenericModuleTest（13 验收，含 generatedProjectAcceptance） | **13/13 PASS** |
| 其中 generatedProjectAcceptance（生成项目 mvn test + pnpm test/build） | PASS |
| 回归：V06Work001ContractTest 8/8、V06Work002SupplierTest 13/13、ProductReferenceWork005Test 10/10、FrontendAuthWork002Test 15/15、FrontendFoundationWork001Test 17/17 | 63/63 PASS |
| validate-manifest.py --all | 全部 PASS |
| validate-registry.py --all | 全部 PASS |
| git diff --check | clean |

## 修复记录（测试驱动）

- ModelUnitTest import 包路径（application.X → domain.entity.X）
- 业务路由文件在 business/ 子目录 → 视图 import 需 `../../views/`（非 `../views/`）
- TS6133 未使用 import（StatusTag/SearchForm）、sample 缺 departmentId（dataScope=true 时类型要求）
- product/supplier Reference 路由迁移后：模板 import 路径 + 资产断言同步更新

## 三个必答问题

1. **新增一个完全未知的 Business Module，还需要写 Java / capability / template 吗？**
   **NO** —— 只需要写一份 Business Module Contract（module manifest 的 business 小节：table/entity/fields/features/enterprise）。
2. **frontend-auth 还需要知道 supplier/product/customer 名称吗？**
   **NO** —— 它只 glob `../router/business/*.ts`，业务名完全解耦。
3. **EPM.businessModules 已经成为生成输入吗？**
   **YES** —— GenericModuleGenerator 直接消费 EPM.businessModules[] 产出全栈文件。

## Escalation

**NO** —— 未修改 Generation Executor / Ownership / PathSafety / Conformance 核心语义；Resolver/EPM 扩展为 WORK-001 已有产物，本次只新增一个纯文件集生成器 + 前端路由聚合模板。未跑 Full Regression / Full Playwright / Historical Release Gates（FAST_DEV + WP_ACCEPTANCE 预算）。

## Changed Files

- 新增 `generator/generator-core/.../GenericModuleGenerator.java`（core，~1500 行）
- 修改 `AssetProjectGenerator.java`（addGenericModuleFiles 接入）
- 修改 `capabilities/frontend-auth/templates/src/router/index.ts.ftl`（business/*.ts 聚合）
- 迁移 `frontend-product-reference` / `frontend-supplier-reference` 路由 → `templates/src/router/business/` + asset.yaml target 更新
- 新增 `tests/fixtures/v06-reference/generic/`（project.yaml + customer-lite/warehouse-lite module manifests）
- 新增 `tests/fixtures/v06-reference/generic-supplier/`（supplier generic 迁移证明）
- 修改 `registry/modules.yaml`（+customer-lite/warehouse-lite）
- 新增 `V06Work002BGenericModuleTest.java`（A-M 验收）

## Known Limitations

- Generic 生成是"契约驱动 + 确定性模板"，不含 UI 设计器/字段拖拽（Console WORK-003 方向）
- 单表模型（V0.6 边界）；relation/主从/多对多不做
- dictionary 字段的字典项由 seed 提供 ENABLED/DISABLED 两态，业务自定义字典值可在 Contract 侧扩展 seed
- product/supplier Reference 保留为 Pattern/Regression 资产（M 验收），但已与 Generic 共用同一路由聚合通道

---

**V06-WORK-002B = PASS**
