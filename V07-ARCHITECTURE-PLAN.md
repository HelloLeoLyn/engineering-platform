# Engineering Platform V0.7 Architecture Plan — Business Modeling

- **日期**: 2026-08-16 ｜ **Mode**: ARCHITECTURE_ONLY
- **状态**: 基于 V0.6 release（tag v0.6.0 = d86dd7d，main = e46fe8b）真实代码审计，未修改任何文件
- **主题**: 把 Engineering Platform 从"单表 CRUD 项目生成平台"提升为"能够描述业务关系并生成真实业务模块的平台"

---

## 1. Current V0.6 Architecture

### 1.1 生成链（唯一，V0.6 认证）
```
Contract (project.yaml + module.yaml)
  → CompleteResolver（V06ContractProfileResolver + BusinessModuleResolver）
  → EffectiveProjectModel（EPM，含 businessModules）
  → AssetProjectGenerator（GenerationPlanner / GeneratorExecutor / Stager / Rollback）
  → GenericModuleGenerator（后端单表 CRUD）
  → GenericFrontendTemplates（前端单表 CRUD）
  → generated project（backend + frontend）
  → build → test → dev-start → run → Browser acceptance
```

### 1.2 V0.6 Generic Business Module Contract（module.schema.yaml `business` 小节）
```yaml
business:
  table: customer_lite
  entity:
    name: CustomerLite
    fields:
      - name: code
        type: string
        required: true
        unique: true
        length: 50
      - name: status
        type: string
        semantic: dictionary
        dictionary: customer_status
      # ...（type: string/number/integer/decimal/boolean/date/datetime/text）
  features: [list, search, create, edit, detail, disable]
  enterprise: { permissions, dataScope, menu, dictionary, operationLog }
  frontend: { route, label, menuIcon }
```

### 1.3 解析模型（generator-contracts）
- `ResolvedBusinessModule(id, name, version, table, entity, features, enterprise, frontend)`
- `BusinessEntity(name, fields)`
- `BusinessEntityField(name, type, required, unique, length, precision, scale, defaultValue, primaryKey, comment, semantic, dictionary, frontend)`
- EPM 增加 `List<ResolvedBusinessModule> businessModules`（V06-WORK-001）

### 1.4 后端生成（GenericModuleGenerator，全部确定性模板）
Entity / CreateRequest / UpdateRequest / Response / Port / Service / Mapper / MybatisRepository / BeansConfig / Controller / Migration(V100+) / Seed(permission+role+dict+menu) / ModelUnitTest

### 1.5 前端生成（GenericFrontendTemplates，复用 enterprise-admin Design System）
types / api / router/business / ListView / DetailView / EditView / tests(api/route/list/detail)
组件：AppForm / AppTable / SearchForm / FormDrawer / DictionarySelect / StatusTag / StatCard / ConfirmAction / PageHeader / PageContainer / DepartmentTree

### 1.6 Console（console/，纯 Adapter）
- `console-server`：ModuleStore（module manifest YAML 存储）/ MySqlImportService（information_schema.columns）/ XlsxSupport（zip+XML 零依赖解析）/ GenerationService（桥接现有 resolver+generator）/ RuntimeService（preflight/build/start/stop/restart/status/logs 全走 Runtime Recipe）/ ConsoleServer（/api/meta|overview|projects|validate|preview|generate|modules|runtime）
- `console-web`：OverviewView / ProjectBuilderView / BusinessModulesView（Manual + MySQL + Excel 三 Tab + Field Designer）/ BuildRunView / ProjectsView
- 核心约束：Console 只产 Contract YAML，生成链唯一 = 现有 resolver+generator（GenerationService 动态注入 ModuleStore manifests）

### 1.7 资产（capabilities/）
Backend：platform-core / web / validation / exception-handling / logging / persistence / audit / authentication / rbac / organization / data-permission / menu / dictionary / operation-log / product-reference / supplier-reference
Frontend：frontend-shell / frontend-auth / frontend-permission / frontend-enterprise-management / frontend-product-reference / frontend-supplier-reference
Infra：runtime-recipe / playwright-e2e

### 1.8 V0.6 已知限制（V0.7 要解决）
- **单表模型**：无关系、无主从、无 FK JOIN、无事务性主从写入
- 字段语义仅 semantic: none/dictionary/department/ownership/currentUser/system；无 reference/enum/money/image/file/richtext
- 前端组件选择由 `semantic=dictionary` + 少量 frontend metadata 决定；无 Reference Selector
- MySQL Import 只读 columns（PK/UNI），**不读 FK** → 无法发现关系候选
- Excel Import 只支持字段定义（column/field/type/label/required/primaryKey/unique），无关系列
- **不一致点（审计发现）**：module.schema.yaml 的 `frontend` 用 `searchVisible`，但实现（GenericFrontendTemplates）与 Console（FieldDef）用 `searchable` —— V0.7 统一为 `searchable` 并修正 schema

---

## 2. V0.7 Goal

把平台从"单表 CRUD 生成平台"提升为"能够描述业务关系并生成真实业务模块的平台"。

**V0.7 Reference Scenario：采购订单（PurchaseOrder）**
```
Supplier ──┐
Product ───┤ (reference)
           ▼
PurchaseOrder
├── orderNo / supplier / orderDate / status / totalAmount / department
└── items[]
    ├── product / quantity / unitPrice / amount / remark
```

成功标准：Console Business Module Builder → 创建 PurchaseOrder → 配置 PurchaseOrderItem → 配置 Supplier/Product Reference → 配置主从关系 → Preview Contract → Generate → Build → Run → Browser，生成**真正可运行**的采购订单业务模块。

V0.7 **不推翻 V0.6**：所有 V0.6 Contract（无 relations / 无高级 semantic / 无主从）仍然正常生成。

---

## 3. Business Modeling Model

### 3.1 建模元模型（三层）
```
Module（业务域，= 现有 business module）
 ├── Entity（实体，聚合根或普通实体；V0.6 单实体）
 │    ├── Field（字段：业务语义 + 数据类型 + UI 语义）
 │    └── Relation（关系：与其它模块的结构化关联）
 └── Features / Enterprise / Frontend（不变）
```

### 3.2 关系视角（V0.7 新增维度）
- **Reference（引用）**：字段级关联 —— 某字段的值指向目标模块记录（MANY_TO_ONE 的字段面）
- **Composition（组合）**：实体级关联 —— 主子生命周期绑定（ONE_TO_MANY + cascade）
- 关系**必须结构化声明**，禁止 Generator 按字段名猜（`supplier_id` → 不自动假设）

### 3.3 设计原则
1. **向后兼容优先**：所有 V0.7 扩展均为 `business` 小节内**可选**新增（`relations`、字段 `type`/`semantic` 扩展、`frontend` metadata 扩展），V0.6 manifest 不加新字段仍原样生成
2. **单一事实源**：关系定义在 Contract，生成器只消费结构化解析结果
3. **不新增生成引擎**：仍然只有一个 GenericModuleGenerator + GenericFrontendTemplates（关系感知升级，不新建 PurchaseOrderGenerator）
4. **schemaVersion 不升**（保持 1）：schema 允许新增可选 property，旧解析器忽略未知键

---

## 4. Contract Design

### 4.1 模块级：`business.relations`（NEW，可选）
```yaml
business:
  table: purchase_order
  entity:
    name: PurchaseOrder
    fields:
      - name: orderNo
        type: string
        required: true
        unique: true
        length: 64
      - name: supplierId
        type: integer
        required: true
        semantic: reference        # V0.7：结构化引用
        reference:                 # V0.7：引用描述（见 §8）
          target: supplier
          valueField: id
          labelField: name
          searchFields: [name, code]
          dataScope: true
      - name: orderDate
        type: date
        required: true
      - name: status
        type: string
        semantic: enum             # V0.7：枚举语义
        enum: [DRAFT, SUBMITTED, APPROVED, REJECTED, CLOSED]
        default: DRAFT
      - name: totalAmount
        type: money                # V0.7：金额语义（decimal(14,2)）
        precision: 14
        scale: 2
      - name: departmentId
        type: integer
        semantic: department
      - name: remark
        type: text
  relations:                       # V0.7 NEW
    - name: items
      type: ONE_TO_MANY
      target: purchase-order-item
      mappedBy: purchaseOrderId    # 子表 FK 列
      composition: true            # 生命周期级联（create/update/delete）
  features: [list, search, create, edit, detail, disable]
  enterprise: { permissions: true, dataScope: true, menu: true, dictionary: true, operationLog: true }
  frontend: { route: /purchase-orders, label: Purchase Orders }
```

### 4.2 子模块（purchase-order-item）
```yaml
module:
  id: purchase-order-item
  name: PurchaseOrderItem
  version: 1.0.0
  type: business
business:
  table: purchase_order_item
  entity:
    name: PurchaseOrderItem
    fields:
      - name: productId
        type: integer
        required: true
        semantic: reference
        reference:
          target: product
          valueField: id
          labelField: name
          searchFields: [name, code]
      - name: quantity
        type: integer
        required: true
      - name: unitPrice
        type: money
        precision: 12
        scale: 2
        required: true
      - name: amount
        type: money
        precision: 14
        scale: 2
      - name: remark
        type: string
        length: 200
  relations:
    - name: purchaseOrder          # 反向 MANY_TO_ONE（配对声明）
      type: MANY_TO_ONE
      target: purchase-order
      localField: purchaseOrderId  # 本表 FK 列（含 FK 约束生成）
      targetField: id
      required: true
      composition: true
  features: [list, detail]         # 子表不独立 create/edit（由主表驱动）
  enterprise: { permissions: false, dataScope: false, menu: false, dictionary: false, operationLog: false }
```

### 4.3 关系配对规则（确定性）
- 主模块声明 `ONE_TO_MANY`（`mappedBy` = 子表 FK 列，`composition: true`）
- 子模块声明 `MANY_TO_ONE`（`localField` = 本表 FK 列，`targetField: id`，`required`）
- 校验器验证配对：主 O2M.target 的模块存在 且 该模块声明了指向主模块的 M2O，且 mappedBy == localField
- `composition: true` ⇒ 生成 FK 约束 + 级联行为；`false` 或省略 ⇒ 仅引用校验（软关联）

### 4.4 Schema 变更（module.schema.yaml，兼容扩展）
- `business.relations`：新增可选数组（name/type/target/localField/mappedBy/targetField/required/composition）
- `entity.fields[].type`：enum 扩展 `money/enum/status/reference/image/file/richtext`（保留全部 V0.6 类型）
- `entity.fields[].semantic`：enum 扩展 `reference/enum`（保留 none/dictionary/department/ownership/currentUser/system）
- `entity.fields[].reference`：新增可选对象（target/valueField/labelField/searchFields/dataScope/pagination/permission）
- `entity.fields[].enum`：新增可选字符串数组（semantic=enum 或 type=enum 时）
- `entity.fields[].frontend`：新增 `placeholder/sortable`；修正 `searchVisible` → `searchable`（与实现/Console 对齐；旧 key 不再声明，schema 移除）

---

## 5. Relationship Model

### 5.1 关系类型
| type | 表达 | V0.7 状态 |
|---|---|---|
| MANY_TO_ONE | 本实体多个记录指向目标一条记录（字段级 FK） | **实现** |
| ONE_TO_MANY | 目标实体多条记录归属本实体一条（mappedBy） | **实现** |
| ONE_TO_ONE | 本实体一条记录对应目标一条（FK + UNIQUE） | **实现**（作为 M2O + unique 特化） |
| MANY_TO_MANY | 中间表关联 | **仅预留**（枚举 + 文档，不实现） |

### 5.2 ResolvedRelation（generator-contracts 新增 record）
```java
public record ResolvedRelation(
        String name,          // 关系名（模块内唯一）
        String type,          // MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE
        String target,        // 目标模块 id
        String localField,    // 本表 FK 列（M2O / O2O）
        String mappedBy,      // 子表 FK 列（O2M）
        String targetField,   // 目标字段（默认 id）
        boolean required,
        boolean composition) {}
```
- `ResolvedBusinessModule` 增加 `List<ResolvedRelation> relations`（默认空列表，向后兼容）
- `BusinessEntityField` 增加 `reference`（Map）与 `enumValues`（List<String>）两个可选字段

### 5.3 约束（硬）
- 关系必须结构化声明；**禁止** Generator/Resolver 依据字段名（`*_id` / `*_status`）推断关系
- `MANY_TO_MANY` 不在 V0.7 实现；Contract schema 预留枚举值 + 文档说明（V0.8 候选）
- 循环/自引用关系：V0.7 允许自引用（如 category.parentId），但 **禁止跨模块循环 composition**（A 组合 B 且 B 组合 A）——校验器拒绝

---

## 6. Field Semantics V2

### 6.1 字段类型矩阵（V0.7 全量）
| type | 后端映射 | 前端组件（默认） | V0.6 | V0.7 |
|---|---|---|---|---|
| string | VARCHAR(length) | el-input | ✅ | ✅ |
| text | TEXT | el-input type=textarea | ✅ | ✅ |
| integer | BIGINT/INT | el-input-number | ✅ | ✅ |
| decimal | DECIMAL(p,s) | el-input-number | ✅ | ✅ |
| money | DECIMAL(14,2) 默认 | MoneyInput（千分位+两位小数） | ❌ | ✅ |
| boolean | TINYINT(1) | el-switch | ✅ | ✅ |
| date | DATE | el-date-picker(date) | ✅ | ✅ |
| datetime | DATETIME | el-date-picker(datetime) | ✅ | ✅ |
| enum | VARCHAR + CHECK/枚举校验 | StatusSelect / el-select | ❌ | ✅ |
| status | VARCHAR + 字典或枚举 | StatusTag + StatusSelect | 部分(dictionary) | ✅ |
| reference | BIGINT FK | ReferenceSelect（§8） | ❌ | ✅ |
| image | VARCHAR(url) | ImageUpload / el-image | ❌ | ✅ |
| file | VARCHAR(url) | FileUpload | ❌ | ✅ |
| richtext | TEXT | RichTextEditor | ❌ | ✅ |

### 6.2 semantic 扩展
`none / dictionary / department / ownership / currentUser / system`（V0.6）+ `reference / enum`（V0.7）
- `semantic: reference` 必须带 `reference:` 描述块（§8），否则校验失败
- `semantic: enum` 必须带 `enum:` 列表
- `semantic: status` 允许两种形态：`dictionary`（现有）或 `enum`（新增）；由 generator 统一渲染 StatusTag

### 6.3 frontend metadata V2（字段级）
```yaml
frontend:
  label: Supplier          # 显示名
  component: ReferenceSelect  # 显式组件覆盖（默认按 type/semantic 推断）
  placeholder: Select supplier
  listVisible: true
  searchable: true         # 统一命名（V0.7 修正 schema searchVisible 不一致）
  formVisible: true
  detailVisible: true
  sortable: false          # V0.7 NEW：列表排序
  order: 1
```
**原则**：Contract 描述业务语义，Frontend Generator 根据结构化 metadata 选择组件；**禁止**按字段名猜（status/department/image/file/dictionary 一律走 semantic/type）。

---

## 7. Master / Detail Model

### 7.1 Contract 表达（§4.2 已示）
- `composition: true` 的 ONE_TO_MANY（主侧）+ 配对的 MANY_TO_ONE（子侧）

### 7.2 数据库
- 主表 `purchase_order`：id + 业务字段 + department_id + 审计字段
- 子表 `purchase_order_item`：id + purchase_order_id（**FK → purchase_order.id**，INDEX）+ 业务字段 + 审计字段
- Migration：V10x 主表 → V10y 子表（确定性顺序：主模块先，子模块后；FK 依赖顺序由 relations 拓扑排序保证）

### 7.3 后端生成（关系感知）
- Entity：主 Entity 含 `List<PurchaseOrderItem> items`（transient，非表列）；子 Entity 独立
- CreateRequest：`List<PurchaseOrderItemCreateRequest> items`；UpdateRequest 同（**全量替换语义**）
- Service：
  - `create`：`@Transactional` 主记录插入 → items 逐个插入（FK 指向新主 id）
  - `update`：`@Transactional` 主记录更新 → 子表按主 id 全量 diff（delete 缺失行 → insert 新增行 → update 变化行）
  - `delete/disable`：composition ⇒ 先删子行再删/禁主行（disable 时子行一并 disable 或主 disable 即不可见）
  - `detail`：主记录 + `List<ItemResponse>`（子表按 purchaseOrderId 查询）
  - `list`：主记录分页（不嵌套子行；totalAmount 由列表汇总字段或子表 SUM 提供）
- 校验：items 非空、quantity > 0、unitPrice ≥ 0、productId 存在（Reference 校验）
- DataScope：主记录 scope 生效；子记录**不经独立 scope**（随主记录加载，天然继承）

### 7.4 目标 API
```
POST   /api/purchase-orders          { supplierId, orderDate, status, items: [{productId, quantity, unitPrice, amount, remark}] }
GET    /api/purchase-orders/{id}     → { …, items: […] }
PUT    /api/purchase-orders/{id}     { …, items: […] }   （全量替换）
GET    /api/purchase-orders          ?page&size&keyword&supplierId&status&dateFrom&dateTo
POST   /api/purchase-orders/{id}/disable
```
（最终路由名/方法签名按现有 GenericModuleGenerator controller 模式生成，此为目标形态）

### 7.5 OperationLog / Dictionary / Menu
- OperationLog：主记录 create/update/disable 记录（resourceType=PURCHASE_ORDER）；子行变更合并进主操作日志描述
- Dictionary/Enum：status 字段渲染与校验照常
- Menu：只注册主模块菜单；子模块不生成菜单/权限（enterprise 全 false）

---

## 8. Reference Selector

### 8.1 Contract（字段级）
```yaml
- name: supplierId
  type: integer
  semantic: reference
  reference:
    target: supplier
    valueField: id
    labelField: name
    searchFields: [name, code]   # 查询搜索字段
    dataScope: true              # 引用目标是否按目标模块 DataScope 过滤
    pagination: true
    permission: supplier:item:read   # 可选：查询所需权限（默认目标模块 read 权限码）
```

### 8.2 后端生成
- 生成 `ReferenceQueryPort`/`ReferenceQueryService`（或复用目标模块既有 list API）：
  - `search(targetModule, keyword, page, size, context)` → 返回 `[{value, label}]`
  - 经目标模块的 scope 过滤（dataScope=true 时）
- FK 存在性校验：create/update 时 `referenceExists(target, valueField, id)`（跨模块只读查询，无 JOIN 写入）
- Controller：主模块生成 `GET /api/purchase-orders/reference-options/supplier?keyword=&page=&size=`（或由前端直调目标模块 list API + label 映射）——**最终方案：前端复用目标模块既有 list API + 本地 label 缓存**，减少跨模块 API 面

### 8.3 前端生成
- 新组件 `ReferenceSelect`（enterprise-admin 资产内新增，通用）：
  - props: `api`（目标模块 list API）、`valueField`、`labelField`、`searchFields`、`dataScope`
  - 远程搜索（debounce）+ 分页 + 回显 label（detail/list 用 `{{ row.supplierName }}` 或 value→label map）
- List 列显示 label（`supplierName`）而非裸 id；Search 用 ReferenceSelect 过滤；Form 用 ReferenceSelect 选择
- **同一机制复用**：customerId / warehouseId / categoryId / employeeId 全部走 ReferenceSelect，零业务名分支

### 8.4 生成规则（确定性）
- 每个 `semantic: reference` 字段 ⇒ ReferenceSelect + 后端引用校验 + list 列 label 展示
- target 模块的 `labelField`/`searchFields` 从目标模块 Contract 读取（若目标未定义则回退 `name`）

---

## 9. Backend Generation Design

### 9.1 扩展点（全部在 GenericModuleGenerator 内，禁止新生成器）
| 生成物 | V0.6 | V0.7 变化 |
|---|---|---|
| entitySource | 单实体 | 主实体 + items transient List；子实体独立 |
| createRequestSource | 单字段 | + `List<ItemCreateRequest> items`（composition 主模块） |
| updateRequestSource | 单字段 | + items 全量替换 |
| responseSource | 单字段 | + items 列表（detail）；+ reference label 字段（supplierName） |
| portSource | 单实体 CRUD | + `findItemsByMasterId` / `replaceItems` / `deleteByMasterId` |
| serviceSource | 单实体 | + `@Transactional` 主从写入；+ reference 存在性校验；+ enum 校验 |
| mapperSource | 单 Mapper | 子表 Mapper 独立 |
| repositorySource | 单 Repository | + 子表 CRUD；FK 列索引 |
| controllerSource | 单实体 CRUD | + detail 带 items；list 带 reference 过滤参数 |
| migrationSource | 单表 | 主表 + 子表（FK + INDEX，拓扑顺序） |
| seedSource | 单模块 seed | 主模块权限/菜单；子模块不独立 seed |
| modelUnitTestSource | 单实体测试 | + 主从写入/替换/删除测试 |

### 9.2 事务与一致性
- create/update/delete 主从全在 `@Transactional`（Spring 声明式）
- 更新失败 ⇒ 全量回滚（RollbackManager 已在生成链级保证）
- 全量替换 diff 用子表主键集合对比（insert/update/delete 三集合）

### 9.3 生成文件拓扑（模块间）
- 依赖顺序：被引用模块（supplier/product）先于引用模块（purchase-order）生成
- relations 拓扑排序在 GenericModuleGenerator.generate 内做（按 target 依赖）

---

## 10. Frontend Generation Design

### 10.1 页面形态
- **PurchaseOrderList**：Order No / Supplier(label) / Date / Status(StatusTag) / Total(money) / Department / Actions
- **Search**：Order No(keyword) / Supplier(ReferenceSelect) / Status(StatusSelect) / Date Range(el-date-picker range)
- **Create/Edit**：Basic Information（supplier/orderDate/status/department/remark）+ **Editable Detail Table**（Product(ReferenceSelect) / Quantity / UnitPrice / Amount(自动计算) / Remark / Add Row / Remove Row）
- **Detail**：Order Summary + Supplier + Status + Items Table + Audit Information(createdBy/createdAt/updatedAt)

### 10.2 生成器扩展（GenericFrontendTemplates 内）
| 生成物 | V0.7 变化 |
|---|---|
| typesSource | + Item 类型；+ reference label 只读字段 |
| apiSource | create/update 带 items；detail 返回 items |
| listViewSource | + ReferenceSelect 搜索；+ money 格式化列；+ status 用 StatusTag |
| editViewSource | + Editable Detail Table（行增删改、金额自动汇总） |
| detailViewSource | + Items Table + Audit 信息 |
| 新增：referenceOptions 辅助 | 目标模块 list API 封装 |

### 10.3 组件（enterprise-admin 资产新增，通用非业务）
- `ReferenceSelect.vue`（§8.3）
- `EditableDetailTable.vue`（通用主子编辑表格：列 schema 驱动 + 增删行 + 金额联动）——**列 schema 由 Contract 生成，组件本身无业务名**
- `MoneyText.vue` / `MoneyInput.vue`
- `StatusSelect.vue`（enum 版本；dictionary 版本继续用 DictionarySelect）
- 继续 enterprise-admin Design System；**不建立 PurchaseOrder 专用 renderer**

---

## 11. Business Module Builder 2.0

### 11.1 Console UI 演进（BusinessModulesView.vue）
```
Module
 ↓ Fields（Field Designer V2）
 ↓ Relations（Relation Designer）   ← NEW Tab
 ↓ Features
 ↓ Frontend
 ↓ Enterprise
 ↓ Preview Contract
```

### 11.2 Field Designer V2（升级现有字段编辑器）
每字段配置：
- Business Type：reference/enum/money/status/image/file/richtext/dictionary/普通
- Data Type：string/text/integer/decimal/boolean/date/datetime（V0.6 已有）
- UI Component：ReferenceSelect/StatusSelect/MoneyInput/ImageUpload/FileUpload/el-input/…（默认按 type 推断，可覆盖）
- Validation：required/unique/length/precision/scale/enum 值列表
- Dictionary：dictionary code（semantic=dictionary）
- Reference：target/valueField/labelField/searchFields/dataScope（semantic=reference）
- Behavior：label/placeholder/listVisible/searchable/formVisible/detailVisible/sortable/order

### 11.3 Relation Designer（NEW）
- Relation Type：MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE（MANY_TO_MANY 置灰提示 V0.8）
- Target Module：下拉（现有 module registry）
- Local Field / Target Field / Mapped By：下拉（当前模块字段 / 目标模块字段）
- Composition：开关
- Required：开关
- 子模块自动创建引导：配置 O2M 时提示"创建目标子模块 + 反向 M2O"，一键生成子模块骨架（fields 编辑器可继续细化）

### 11.4 Preview Contract
- 实时 YAML 预览（现有）+ 关系配对校验提示（新增：未配对/循环组合/mappedBy 不存在）

---

## 12. MySQL Import Evolution

### 12.1 现状（V0.6）
- 读 `information_schema.columns`：column_name/data_type/is_nullable/column_default/column_comment/character_maximum_length/numeric_precision/numeric_scale/column_key
- 映射 PK/UNI；不读 FK；不推断关系

### 12.2 V0.7 扩展
- 新增读取 `information_schema.key_column_usage` + `referential_constraints`（或 `statistics`）：发现 FK 候选
- 映射规则（规则引擎，非 AI）：
  - FK 列 → `relation candidate: MANY_TO_ONE`，target = 被引用表（若被引用表也导入/已存在模块）
  - PK/UNI 照旧
  - 枚举型列（`*_status`/`*_type` 且值域有限）→ `enum candidate`（可扫描 distinct 值，≤N 个时建议 enum）
  - 金额列（`*_amount`/`*_price`/`total_*`）→ `money candidate`
- **所有推断进入 NEEDS_CONFIRMATION**：candidate 关系/语义必须人工确认后才写入正式 Contract（UI 逐个确认：接受/修改/拒绝）
- 输出：确认后的 module manifest（含 relations + reference/enum/money 语义）

### 12.3 边界
- 数据库可以发现 FK/type/nullable/length/precision，**不能擅自推断业务语义**
- `supplier_id` 只产生 relation candidate，绝不自动成为正式 relation
- 不写库、不执行任意 SQL（只读 information_schema）

---

## 13. Excel Import Evolution

### 13.1 现状（V0.6）
- header：column / field / type / label / required / primaryKey / unique
- 每行 = 一个字段

### 13.2 V0.7 扩展
新增可选列（缺省兼容旧模板）：
- `semantic`：none/dictionary/reference/enum/money/status/…
- `dictionary`：字典 code
- `reference_target` / `reference_label`：引用目标模块 / 显示字段
- `enum_values`：逗号分隔枚举值
- `component`：UI 组件覆盖
- `relation_type` / `relation_target` / `relation_mapped_by` / `composition`：**子表关系行**（导入主表 + 子表时，用关系列声明 O2M）
- 未知/不明确值 → NEEDS_CONFIRMATION（导入预览列表标注，人工确认后生成 Contract）

---

## 14. Resolver / EPM Changes

### 14.1 BusinessModuleResolver
- 解析 `business.relations` → `ResolvedRelation` 列表（校验 type 枚举/target 存在/mappedBy·localField 配对/无循环 composition）
- 解析字段 `reference`/`enum`/扩展 type → BusinessEntityField 新字段
- 模块拓扑排序（被引用先解析；用于生成顺序与校验）

### 14.2 EPM（EffectiveProjectModel）
- 结构不变（`List<ResolvedBusinessModule> businessModules` 已存在）
- `ResolvedBusinessModule` 增加 `relations`；`BusinessEntityField` 增加 `reference`/`enumValues`
- SCHEMA_VERSION 保持 1

### 14.3 校验器（新增校验，独立于生成）
- relation schema 校验（type/target/localField/mappedBy/required/composition）
- 配对校验（O2M ↔ M2O 一致）
- 循环 composition 拒绝
- reference target 存在性 + labelField/searchFields 存在性
- enum 非空 + 唯一；money 必须 precision/scale

### 14.4 GenerationPlanner / Execution Engine
- 不变（文件级生成仍走现有事务/staging/rollback）
- 唯一变化：GenericModuleGenerator 产出文件集合因 relations 扩大（主+子表文件）

---

## 15. Compatibility Strategy

### 15.1 保证
- V0.6 Contract（无 relations / 无新 semantic / 单实体）**生成结果逐文件不变**（relations 默认空 → 现有代码路径）
- 旧 fixture（customer-lite / warehouse-lite / supplier / sample-customer）回归：validate + resolve + generate 全过
- schemaVersion 不升；module.schema.yaml 仅新增可选 property
- `searchVisible` → `searchable` 修正：schema 移除旧 key（旧 manifest 不带该 key，无破坏）

### 15.2 回归策略
- 每个 WORK 后跑 affected 测试 + 旧 fixture 回归（FAST_DEV）
- FINAL 一次 Full Regression（V0.6 基线 33 类/451 用例 + V0.7 新增）

---

## 16. Security / DataScope Considerations

### 16.1 主从 DataScope
- 主记录 scope（ALL/DEPARTMENT/DEPARTMENT_AND_CHILDREN/SELF）不变
- 子记录**不独立过滤**：永远经主记录加载（detail 路径）或随主记录写入（FK 继承 department_id）
- 子表不生成独立 Controller 权限（enterprise.permissions=false）

### 16.2 Reference 查询
- ReferenceSelect 查询复用目标模块 list API → 自动受目标模块 DataScope + RBAC 约束（dataScope=true 时）
- 引用存在性校验走只读查询，不越权写

### 16.3 OperationLog
- 主记录 create/update/disable 记日志；items 变更摘要并入主日志（如 "2 items updated"）
- 无独立子表日志

### 16.4 安全边界（延续）
- 生成链安全护栏（FilesystemGuard/ShellGuard/GitGuard/PathSafety）不变
- Contract 仍禁止 secrets/runtimeValues/businessData
- 无 AI、无 Agent、无任意 SQL/页面设计器（§18）

---

## 17. Genericity Guard

### 17.1 硬规则
- **禁止**新建 `PurchaseOrderGenerator` / `CustomerGenerator` / `WarehouseGenerator` / `SupplierGenerator`
- **禁止** `moduleName == "purchase-order"` 之类业务名分支
- 所有关系/语义/主从逻辑只存在于：Contract schema 定义 + ResolvedBusinessModule/ResolvedRelation 数据 + GenericModuleGenerator/GenericFrontendTemplates 的**数据驱动**渲染
- Purchase Order 只是证明 Genericity 的 Reference Scenario（第三个业务域；前两个：customer-lite/warehouse-lite）

### 17.2 验收证明（每 WORK 强制）
- 至少两个不同业务域生成通过（如 customer-lite 无关系 + purchase-order 有主从）——证明同一生成器覆盖简单→复杂
- grep 断言：生成代码中**无**业务名硬编码分支

---

## 18. Explicit Non-Goals（V0.7 不做）

- BPM / Workflow Engine
- Approval Flow Designer
- Low-code Drag & Drop Page Builder
- BI / Report Designer
- Microservices
- AI generation
- AI Dev OS integration
- Corporate Portal
- E-commerce Storefront
- Arbitrary SQL designer
- Arbitrary frontend page designer

以上**不得进入实现 Scope**。

---

## 19. V0.7 Work Packages

| WP | 名称 | 核心内容 | 依赖 |
|---|---|---|---|
| WORK-001 | Business Modeling Contract V2 | module.schema 扩展（relations/reference/enum/money/frontend metadata）；ResolvedRelation/BusinessEntityField 扩展；BusinessModuleResolver 解析 relations + 拓扑排序；新增关系/引用/enum 校验器；fixture（purchase-order + purchase-order-item + product/supplier reference） | — |
| WORK-002 | Relationship-aware Generic Backend Generation | GenericModuleGenerator 主从扩展（entity/request/response/port/service/mapper/repository/controller/migration/seed/test）；@Transactional 主从写入 + 全量替换 diff；FK+INDEX migration；reference 存在性校验；enum/money 校验 | WORK-001 |
| WORK-003 | Relationship-aware Generic Frontend Generation | GenericFrontendTemplates 主从扩展；新组件 ReferenceSelect/EditableDetailTable/MoneyText/MoneyInput/StatusSelect（enterprise-admin 资产）；list/search/form/detail 关系渲染 | WORK-001 |
| WORK-004 | Business Module Builder 2.0 | BusinessModulesView Field Designer V2（business type/UI component/reference/enum/behavior）；Relation Designer（NEW Tab）；Preview Contract 配对校验 | WORK-001（可并行 WORK-002/003 前置） |
| WORK-005 | MySQL / Excel Relation Discovery | MySqlImportService 读 FK + enum/money candidate → NEEDS_CONFIRMATION UI；XlsxSupport 关系/语义列扩展 | WORK-004 |
| WORK-006 | Purchase Order Golden Path | Console 全流程：创建 PurchaseOrder + PurchaseOrderItem + Supplier/Product Reference + 主从 → Preview → Generate → Build → Run → Browser；真实 MySQL + Browser E2E；customer-lite 无关系回归 | WORK-002~005 |
| FINAL | V0.7 Release Acceptance | Full Regression + Golden Path + validators + git diff clean + README V0.7 章节 + Release Decision | 全部 |

**建议顺序**：WORK-001 → WORK-002 + WORK-003（可并行）→ WORK-004 → WORK-005 → WORK-006 → FINAL。

---

## 20. Golden Path（V0.7 验收主线）

```
Console
 ├─ Business Module Builder
 │   ├─ 创建 PurchaseOrder（fields: orderNo/supplierId(reference:supplier)/orderDate/status(enum)/totalAmount(money)/departmentId/remark）
 │   ├─ 创建 PurchaseOrderItem（fields: productId(reference:product)/quantity/unitPrice(money)/amount(money)/remark）
 │   ├─ Relation Designer：PurchaseOrder.items = ONE_TO_MANY(target=purchase-order-item, mappedBy=purchaseOrderId, composition)
 │   │                    PurchaseOrderItem.purchaseOrder = MANY_TO_ONE(target=purchase-order, localField=purchaseOrderId, required)
 │   └─ Preview Contract（配对校验通过）
 ├─ Generate（现有 GenerationService → resolver → EPM → GenericModuleGenerator/FrontendTemplates）
 ├─ Build（backend mvn test + frontend pnpm test/build）
 ├─ Run（Runtime Recipe dev-start）
 └─ Browser
     ├─ Login → Dashboard
     ├─ Supplier 管理（reference 数据源）
     ├─ Product 管理（reference 数据源）
     ├─ Purchase Order List（搜索：supplier/status/date range）
     ├─ Create：Basic Info + Editable Detail Table（add/remove rows，金额联动）
     ├─ Edit：全量替换 items
     ├─ Detail：summary + items table + audit
     └─ Disable
```

成功 = 完整跑通 + 无 console error + 数据持久化到真实 MySQL。

---

## 21. Acceptance Strategy

### 21.1 每 WP 验收（FAST_DEV）
- 只跑 affected 测试 + 旧 fixture 回归 + validators（不跑全量）
- 生成物抽查：文件存在 + 内容断言（沿用 V06Work002BGenericModuleTest 模式：resolve → generate → 断言文件）

### 21.2 关键断言（每 WP）
- V0.6 旧 manifest（无 relations）生成结果与基线一致（diff clean）
- 关系配对校验：错误关系被拒绝（未配对/循环/坏 target）
- 生成代码 grep 无业务名硬编码（PurchaseOrder 不出现在模板逻辑中，只作为数据）

### 21.3 FINAL
- Full Regression（V0.6 33 类/451 用例 + V0.7 新增类）
- Golden Path Browser E2E（真实 MySQL）
- Contract/validators 全过
- git diff --check clean
- README V0.7 章节 + Backlog V0.8

---

## 22. Risks / Open Questions

| # | 风险/问题 | 影响 | 决策/缓解 |
|---|---|---|---|
| 1 | 主从编辑语义：全量替换 vs 增量 | API 复杂度 | **V0.7 定全量替换**（简单、可预期）；增量 diff 作为 V0.8 优化 |
| 2 | 子表权限/DataScope 独立化需求 | 复杂主从 | V0.7 子表不独立（随主）；文档记录扩展点 |
| 3 | MANY_TO_MANY 预留 | 关系模型完整性 | Contract 枚举预留 + 文档；V0.8 实现中间表生成 |
| 4 | reference 查询性能（远程搜索频繁） | 体验 | ReferenceSelect debounce + 分页 + 前端 label 缓存 |
| 5 | 自引用关系（分类树） | 后端递归 | V0.7 允许自引用 M2O（parentId）；树形 UI 不实现 |
| 6 | money 精度统一 | 金额一致性 | 平台约定 DECIMAL(14,2) 默认；Contract 可覆盖 precision/scale |
| 7 | `searchVisible`/`searchable` 不一致 | schema 漂移 | V0.7 WORK-001 修正 schema；旧 manifest 无该键不受影响 |
| 8 | 关系目标模块未导入 | 校验 | 校验器拒绝 target 不在模块 registry 的 relation |
| 9 | Excel 关系列兼容 | 旧模板 | 新列全可选；旧 header 模板照常解析 |
| 10 | 生成规模增长（主+子表文件翻倍） | 生成时长 | 保持确定性模板拼接；无额外 IO |
| 11 | **CI 稳定性（遗留）**：V0.6 release 后远程 CI 双 FAIL，corrective 已 push（e46fe8b）但未确认修复 | release 质量门 | **独立跟踪**（V06_CI_CORRECTIVE_RESULT 待恢复），不阻塞 V0.7 架构 |
| 12 | Console Builder 2.0 与 Field Designer 复杂度 | 交付量 | WORK-004 单独 WP；Relation Designer 最小可用（无拖拽） |

---

# V0.7 Architecture Recommendation

1. **采用 Business Modeling 扩展方案**：在 V0.6 `business` 小节内做**兼容扩展**（`relations` + 字段 `reference/enum/money/...` + `frontend` metadata V2），schemaVersion 保持 1，V0.6 Contract 零破坏。
2. **关系 = 结构化 Contract**：MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE 实现，MANY_TO_MANY 仅预留；主从用 `composition: true` + 配对校验表达；禁止字段名推断。
3. **单一生成器数据驱动**：所有关系/语义逻辑只进 GenericModuleGenerator + GenericFrontendTemplates（消费 ResolvedRelation/BusinessEntityField 扩展），不新建任何业务名生成器。
4. **Master/Detail 采用全量替换语义 + @Transactional 主从写入**：API 带 items[]，detail 返回 items，disable 级联，子表随主表 DataScope。
5. **Reference Selector 通用化**：新 ReferenceSelect 组件 + 目标模块 list API 复用 + FK 存在性校验；customerId/warehouseId/categoryId/employeeId 复用同一机制。
6. **Builder 2.0 增加 Relation Designer**：Field Designer V2（business type/UI component/reference/enum/behavior）+ Relation Designer（type/target/localField/mappedBy/composition/required）+ Preview 配对校验。
7. **Import 演进为 candidate + NEEDS_CONFIRMATION**：MySQL 读 FK/enum/money candidate，Excel 加语义/关系列；一切推断人工确认后才进正式 Contract。
8. **Work Packages 边界清晰**：WORK-001 Contract → WORK-002/003 后端/前端 → WORK-004 Builder 2.0 → WORK-005 Import → WORK-006 Golden Path → FINAL。
9. **遗留跟踪**：V06 CI corrective（e46fe8b 已 push，远程结果待确认）与 V0.7 独立；V0.7 每个 WP 仍要求本地验证 + 影响面回归。
10. **Non-Goals 硬约束**：BPM/Workflow/Approval/BI/微服务/AI/Portal/E-commerce/任意 SQL/任意页面设计器全部不进入 V0.7 实现 Scope。

---

## READY_FOR_V07_WORK_001 = **YES**

依据：
- V0.6 架构完全支持兼容扩展（schema 可选 property + EPM 默认值 + 生成器数据驱动模式已证明：customer-lite/warehouse-lite/supplier 多域通用）
- 关系/引用/主从全部可落在现有 Contract 与生成链上，无需新引擎
- 校验器、fixture、测试模式（resolve→generate→assert）全部现成
- 唯一前置遗留：V06 CI corrective 最终确认（不阻塞 V0.7 架构，建议在 WORK-001 开工前或并行确认）

---

*本计划未修改任何文件；等待人工审核。*
