# V07-WORK-002 Implementation Report — Relationship-aware Generic Backend Generation

- **阶段**: V07-WORK-002（WP_IMPLEMENTATION）
- **日期**: 2026-08-16
- **状态**: **PASS**（实现完成、测试全绿、未 commit 未 push）
- **目标**: 让 Generic Backend Generator 理解 relations & master/detail —— Contract（V2）→ Resolver → GenericModuleGenerator → migration/model/DTO/repo/service/controller → 可运行的关系型业务 API

---

## 1. Implemented

Contract V2 的 structured relations 从解析到可运行后端的全链路实现，未引入任何专用生成器 / module-name 特判 / 第二套执行器：

- `MigrationRelationRenderer`（新）：收集全部跨模块 FK（reference 字段 + MANY_TO_ONE/ONE_TO_ONE localField + ONE_TO_MANY mappedBy，按 table+column 去重）→ 渲染 `V200__relations.sql`（CREATE INDEX + ALTER TABLE ADD CONSTRAINT），在所有模块 baseline 之后执行（模块按 id 字母序建表，内联 FK 会引用不存在的表）。
- `RelationBackendRenderer`（新）：`PortDependency` 收集 + `referenceValidation`（target-exists 走目标模块 Port，零 MyBatis 泄漏）+ `enumValidation`（Set.of 校验 + 确定性错误码）。
- `MasterDetailBackendRenderer`（新）：master/child 识别（isMaster / compositionOneToMany / childOf）、child ItemInput DTO、child Port 方法（findByParent + deleteById）、child Repository 实现、reconcile 逻辑。
- `GenericModuleGenerator` 保持主入口：generate() 挂载 V200、backendFiles 传递主从上下文（masterChild/childParent/childModule/parentModule/refPorts/childRefPorts）、master DTO 三件套带 items[]、Service 主从重写、BeansConfig 注入、masterDetailHttpE2ETestSource（生成 {Entity}MasterDetailHttpE2ETest）。

## 2. Relationship Persistence

- FK 列复用模块 baseline 已声明的列，不重复建列；FK 约束集中在 V200__relations.sql（V100+idx 之后执行）。
- 无 M2M（V0.7 明确不支持，Resolver 确定性报错 RELATION_TYPE_UNSUPPORTED）。
- child→parent FK（MANY_TO_ONE / ONE_TO_ONE localField）、parent→child（ONE_TO_MANY mappedBy 落 child 表）均生成。
- H2 MODE=MySQL 下 CREATE INDEX 保证 child lookup 效率。

## 3. Reference Validation

- 写入（create/update）校验 reference 字段 target 存在：`port.findById(x).orElseThrow(XXX_REFERENCE_NOT_FOUND)`。
- 走 Port（Application 层），不泄漏 MyBatis。
- enum 字段校验限制在 Contract enum values（`Set.of(...).contains` + XXX_INVALID）。

## 4. Master/Detail DTO

- Master：CreateRequest / UpdateRequest / Response 三件套，Response 带 `items[]`（child Response），不暴露实体。
- Child：`{Child}ItemInput`（可选 id、剔除 parent FK 字段）+ `{Child}Response`。
- money→BigDecimal、date→LocalDate、child 跨包 import 均已处理。

## 5. Transaction Model

- Master create：`@Transactional`，顺序 = validate refs（parent+children）→ insert parent → insert children（带 parent FK）→ 全回滚（PlatformException=RuntimeException）。
- 边界在 Application Service（Port 层不持有事务语义）。

## 6. Update Reconciliation

- 确定性 diff（`reconcileItems`）：existingIds/seen 集合；重复 id → ITEM_ID_DUPLICATE；不属于本 parent → ITEM_NOT_IN_PARENT；missing → child Port.deleteById（仅 composition child 物理删除）。
- 单事务；re-validate refs；禁 DELETE ALL→INSERT ALL。

## 7. Composition Semantics

- composition ONE_TO_MANY 生命周期随 parent：create 插入、reconcile 删除 missing、detail 返回 items。
- child 物理删除仅限 composition child（`deleteById`），不扩散通用 cascade。

## 8. ONE_TO_ONE Support

- ONE_TO_ONE localField → ownership FK（V200）+ reference 校验（字段级 semantic:reference 声明，V2 契约契约一致）。

## 9. Money / Enum / Status

- money → BigDecimal + DECIMAL(p,s)；enum → String 持久化 + Contract values 校验；status 复用现有 String/Dictionary 语义，未新建状态机。

## 10. DataScope

- PurchaseOrder（parent）仍支持现有 DataScope：findByIdInScope / scopeWrapper 在 parent 行边界；detail 通过 parent 校验，不能绕过 parent DataScope 访问 child。

## 11. Operation Log

- 主从 create/update 作为一次业务操作记录（主资源 identity），不逐 item 刷日志。

## 12. Genericity Proof

- 第二轻量域 fixture：warehouse → warehouse-bin（内联 manifest），验证 FK/migration/master DTO/child Port 全链路泛化（非 PurchaseOrder 特例）。

## 13. Purchase Order HTTP Proof

- 生成项目内 `PurchaseOrderMasterDetailHttpE2ETest`（纯后端，RANDOM_PORT + TestRestTemplate + admin/admin123）：
  - A: create master + items → 200 + id，detail 返回 items（2 条）
  - B: update reconciliation（keep 1 / delete 1 / insert 1）
  - C: invalid child reference → 400
  - D: duplicate child id → 400
  - E: child of another parent → 400

## 14. Compatibility

- V0.6 无 relations 模块完全不变：无 V200、无 items[]、无 @Transactional/reconcileItems、无 deleteById（v06ModulesUnchanged 断言）。
- V0.6 生成回归：V06Work002BGenericModuleTest 13/13 + V06Work002SupplierTest 13/13 PASS。

## 15. Verification

- V07Work002RelationBackendTest 13/13 PASS（A-L：V200 FK/index、baseline 排序、money/enum 列、master DTO items、reference 校验、事务、detail items、reconciliation、child Port、O2O 定向、第二域泛化、生成项目编译+HTTP E2E、V0.6 不变）。
- V07-WORK-001 回归 14/14 PASS。
- validators（validate.sh --python）全过。
- git diff --check 干净。

## 16. Escalation

- 未触发 VERIFICATION_ESCALATION_REQUIRED：未修改 Ownership 核心语义 / PathSafety / Executor / DataScope 核心安全模型 / OperationLog 核心模型 / V0.6 Contract 行为。
- 修复了 V0.6 潜伏 bug：Repository 的 findPageByScopeFiltered/existsByUnique 与 Port 一样按 features 条件生成（V0.6 模块全 features 未暴露，V0.7 read-only 模块首曝）——不改变 V0.6 模块行为。

## 17. Known Limitations

- MANY_TO_MANY 明确不支持（V0.7 范围外，Resolver 报错）。
- ONE_TO_ONE 校验需字段级 semantic:reference 声明（与 V2 契约一致）。
- seed 文件名在跨模块 FK 项目内带拓扑前缀（`seed-zzz-{level}-{moduleId}.sql`），保证 Spring 按 FK 依赖顺序加载；无 FK 项目文件名不变。
- SQL 列名统一 snake_case（与 MyBatis-Plus 默认 column-underline 一致）；表名 ≠ 默认推断时实体生成 @TableName。

## 18. Changed Files

新增（WORK-002）：
- generator/generator-core/src/main/java/com/engineeringplatform/generator/core/MigrationRelationRenderer.java
- generator/generator-core/src/main/java/com/engineeringplatform/generator/core/RelationBackendRenderer.java
- generator/generator-core/src/main/java/com/engineeringplatform/generator/core/MasterDetailBackendRenderer.java
- generator/generator-core/src/test/java/com/engineeringplatform/generator/core/V07Work002RelationBackendTest.java
- tests/fixtures/v07-reference/（product / purchase-order / purchase-order-item / supplier / project.yaml）

修改（WORK-001 已改 + WORK-002 增强）：
- generator/generator-contracts/.../BusinessEntityField.java / ResolutionError.java / ResolvedBusinessModule.java / ResolvedRelation.java
- generator/generator-core/.../BusinessModuleResolver.java / GenericModuleGenerator.java
- generator/schemas/module.schema.yaml

## 19. git diff --stat

```
 .../generator/contracts/BusinessEntityField.java   |  43 +-
 .../generator/contracts/ResolutionError.java       |  13 +
 .../contracts/ResolvedBusinessModule.java          |  22 +-
 .../generator/core/BusinessModuleResolver.java     | 368 +++++++++-
 .../generator/core/GenericModuleGenerator.java     | 804 +++++++++++++++++++--
 generator/schemas/module.schema.yaml               | 102 ++-
 6 files changed, 1282 insertions(+), 70 deletions(-)
```
（另含 9 个 untracked 新文件：3 个 renderer、V07Work001/002 测试、ResolvedRelation、V07-ARCHITECTURE-PLAN.md、V07-WORK-001-IMPLEMENTATION-REPORT.md、tests/fixtures/v07-reference/）

---

## 结论

**V07-WORK-002 = PASS**

**READY_FOR_V07_WORK_003 = YES**

（未 commit、未 push；等待人工验收后收口）
