# V06-WORK-001 RESULT — Contract & Profile Foundation

- **日期**: 2026-08-16 ｜ **Mode**: WP_ACCEPTANCE
- **仓库**: /home/administrator/workspace/engineering-platform
- **状态**: 实现完成，专项验证全绿，**未 commit / push**

---

## Implemented

一次完成 V0.6 Contract 基础，全部基于现有确定性链路（**不建第二套 Resolver / 不复制 Execution Engine**）：

1. **Project Contract V2**（project.schema.yaml 兼容扩展，schemaVersion 保持 1）
   - `application.profile`（enterprise certified；corporate-portal/ecommerce/custom 预留）
   - `stack.profile`（certified stack 引用）
   - `frontends[]`（{id, template}，template enum 6 个）
   - `modules` 支持字符串简写（`- supplier`）与旧对象形式
   - 旧 manifest（无新字段）完全兼容
2. **Application Profile**：enterprise certified 可解析；预留值解析时明确错误
3. **Certified Stack Profile**：`enterprise-java25`（Java 25/Spring Boot/Maven/MyBatis-Plus/Flyway/MySQL/REST/Jakarta Validation；Vue3/TS/Vite/Pinia/Element Plus/EP UI；JUnit/Vitest/Playwright Golden Path）—— 数据驱动（platform.yaml technology.stackProfiles），技术基线合并进 EPM.technology
4. **Frontend Template Profile**：enterprise-admin certified；modern-console/simple-admin/corporate-website/commerce-storefront/custom 预留 unsupported（稳定明确错误）
5. **Generic Business Module Contract**（module.schema.yaml 可选 `business` 小节）：table/entity.fields（name/type/required/unique/length/precision/scale/default/primaryKey/comment/semantic/dictionary/frontend metadata）/features/enterprise（permissions/dataScope/menu/dictionary/operationLog）/frontend
6. **Resolver/EPM 集成**：`V06ContractProfileResolver`（application/stack/frontends 解析+校验）+ `BusinessModuleResolver`（module business 小节 → 结构化）接入 CompleteResolver Step 7 后；EPM 新增 applicationProfile/stackProfile/frontends/businessModules

## Contract Shapes

```yaml
# project.yaml (V2 兼容扩展)
application: { profile: enterprise }
stack: { profile: enterprise-java25 }
frontends:
  - id: admin
    template: enterprise-admin
modules:
  - supplier

# module manifest (business 小节)
business:
  table: supplier
  entity:
    name: Supplier
    fields:
      - name: code
        type: string
        required: true
        unique: true
        length: 50
        semantic: none
        frontend: { label: 编码, listVisible: true, order: 1 }
  features: [list, search, create, edit, detail, disable]
  enterprise: { permissions: true, dataScope: true, menu: true, dictionary: true, operationLog: true }
```

EPM 新增结构化字段：`applicationProfile: "enterprise"` / `stackProfile: "enterprise-java25"` / `frontends: [ResolvedFrontend(id, template, status)]` / `businessModules: [ResolvedBusinessModule(...)]`。

## Compatibility

- V1 旧 manifest（v05 fixture）解析成功，V0.6 新字段默认空/null —— ✅
- backend-only manifest（无 frontends 声明）不强制生成 frontend —— ✅
- 未声明 modules 行为不变 —— ✅
- enterprise-admin 不影响现有 frontend assets（仅契约解析）—— ✅
- 无 AI Dev OS 依赖（纯声明式解析，无 agent/task/approval）—— ✅

## Verified（Verification Budget 内）

| 项 | 结果 |
|---|---|
| V06Work001ContractTest（A-L Acceptance） | **8/8 PASS** |
| affected：GenerationPlannerTest / CompleteResolverTest / ResolutionFoundationPipelineTest / ProjectManifestUsabilityTest / AssetAwareResolutionTest | **76/76 PASS** |
| validate-manifest.py --all（schema 变化） | 全部 PASS |
| validate-registry.py --all（registry modules.yaml +supplier） | 全部 PASS |
| mvn compile（generator-contracts + generator-core） | PASS |
| git diff --check | clean |

## Escalation

**NO** —— 未修改 Generation Executor / Ownership / PathSafety / Conformance Engine 核心语义；Resolver/EPM 扩展为本 WP 明确要求，未跑 Full Regression / Playwright / 历史 release gates。

## Changed Files

**Schemas**
- `generator/schemas/project.schema.yaml`（V2 兼容扩展）
- `generator/schemas/module.schema.yaml`（business 小节）
- `generator/schemas/effective-project.schema.yaml`（applicationProfile/stackProfile/frontends/businessModules）
- `generator/schemas/platform.schema.yaml`（stackProfiles/applicationProfiles/frontendTemplates）

**Contracts**
- `generator/generator-contracts/.../EffectiveProjectModel.java`（+4 字段）
- `generator/generator-contracts/.../IntermediateResolutionState.java`（+4 字段+builder）
- 新 `ResolvedFrontend.java` / `ResolvedBusinessModule.java` / `BusinessEntityField.java`

**Core**
- 新 `V06ContractProfileResolver.java` / `BusinessModuleResolver.java`
- `CompleteResolver.java`（接入 Step 7 后）
- `EffectiveProjectModelAssembler.java`（装配新字段）

**数据 / 测试**
- `platform.yaml`（stackProfiles.enterprise-java25 + applicationProfiles + frontendTemplates）
- `registry/modules.yaml`（+supplier 条目）
- `generator/generator-core/src/test/.../GenerationPlannerTest.java`（EPM 构造更新）
- 新 `V06Work001ContractTest.java`（A-L Acceptance）

## Known Limitations

- V0.6 只认证 enterprise / enterprise-java25 / enterprise-admin；corporate-portal / ecommerce / corporate-website / commerce-storefront 等为**扩展位（schema enum 存在）但解析明确拒绝**（not certified）
- 业务模块目前只做 Contract 结构化进入 EPM；**生成（WORK-002）不在本 WP**
- modules 字符串简写仍需模块已注册（registry/modules.yaml），未注册模块由 ReferenceResolver 报 UNKNOWN_REFERENCE（现有行为）
- Console / MySQL Import / Generate-Build-Run 属 WORK-003/004，未实现

---

**V06-WORK-001 = PASS**
