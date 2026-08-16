# V05-WORK-004 Implementation Report — Enterprise Management UI

- **日期**: 2026-08-15 ｜ **基线**: V05-WORK-003 ACCEPTED ｜ **状态**: IMPLEMENTED（未 commit/push）
- **前置**: V05-WORK-001/002/003 ACCEPTED ｜ 模式: FAST-LANE DEVELOPMENT

## 1. Asset / Feature Structure

新增 **`capabilities/frontend-enterprise-management/`**（CAPABILITY v0.1.0，28 文件，已登记 registry/capabilities.yaml）。消费 frontend-auth + frontend-permission + rbac + organization + menu + dictionary + operation-log。

```
frontend/src/
├── types/enterprise.ts          # 稳定 TS 模型（零 any）
├── api/enterprise.ts            # 唯一 HTTP 入口：Platform Request Client
├── utils/{table,tree,feedback,permissionMatrix}.ts
├── components/                  # 8 个可复用 UI
├── router/enterprise.ts         # 受控 /system/* 路由注册
└── views/system/                # 7 个 Feature Views
```

无第二套 Framework/Registry/Router/Request Client/Conformance Engine。

## 2. UX Implementation

统一遵守 `docs/standards/frontend-ux-baseline.md`：
- **List Page 模式**：PageHeader → SearchForm → AppTable → Pagination
- **Workspace 模式**：Role（列表→详情 Workspace）、Department（Tree+Workspace）、Menu（Tree+Workspace）、Dictionary（Type+Items 主从）
- **Form 分级**：User/Dictionary 用 Drawer；Role 复杂配置用页面内 Workspace
- 禁 Card 套 Card；用 Spacing/Typography/Hierarchy 分层；页面级错误不白屏（StateViews/AppTable error + Retry）

## 3. Reusable UI

正式实现 8 个可复用组件（底层仍 Element Plus，不重造 Button/Input/Table/Select/Tree）：

| 组件 | 能力 |
|---|---|
| **AppTable** | loading/empty/error/分页/刷新；行操作：1 个高频 + "More" 下拉（禁止整排按钮） |
| **AppForm** | 分组字段/校验/submit loading/cancel/dirty/error feedback；无 validate 时兜底提交 |
| **SearchForm** | 常用过滤直显 + More Filters 折叠 + Search/Reset |
| **StatusTag** | Enabled/Disabled 统一状态（success/info） |
| **ConfirmAction** | 危险操作（disable）统一 Confirm（ElPopconfirm） |
| **FormDrawer** | 中型编辑面（Drawer + body + 稳定 footer） |
| **DepartmentTree** | loading/选中/禁用态/empty/refresh/stable key，业务 CRUD 不入组件 |
| **DictionarySelect** | 输入 dictionary code 自动加载/enabled 过滤/value-label 映射 |

## 4. User Management

- 列表：搜索 username + 过滤 enabled/department；分页
- 创建（Drawer）：username 校验 + 初始密码（留空自动生成）+ 部门 Selector + 角色 Selector；**创建成功后一次性展示初始密码**（Alert，Shown once）
- 编辑：基本信息 + 部门/角色（Selector，非裸 ID）
- enable/disable：disable 必须 Confirm（ElMessageBox）；行内 More 菜单
- 不展示 passwordHash/已有密码

## 5. Role / Permission

- 列表 + 搜索 + 分页；行内 Open 进 **Role Workspace**
- Workspace：Basic Info（code 创建后不可变提示）+ **Permission Matrix**（按 domain/resource/action 分组，可理解 label）+ **Data Scope**（4 种范围 + 人话说明）
- create/update/enable/disable/assign-permissions（替换式保存）

## 6. Department

- **左 Tree + 右 Detail/Edit Workspace**（规格 §12 布局）
- create/update/enable/disable + parent Selector（排除 self+descendants 防环）
- self-parent/cycle 错误后端校验 + 前端提示；disabled 态显示；稳定排序

## 7. Menu

- Tree + Workspace（同 Department 模式）
- 字段：code/name/type/path/permissionCode/parent/sort/enabled
- **Type 动态字段**：DIRECTORY（path/permission 可选）、MENU（path 合理必填提示）、ACTION（permissionCode 重点）
- permissionCode 用 **Selector**（从 Permission Registry 加载），不手输未知码

## 8. Dictionary

- **左 Types + 右 Items** 主从布局
- Type/Item create/update/enable/disable/sort
- 稳定 code 治理：type.code、item.value 创建后禁用编辑（UI + 后端双保险）
- 提供 DictionarySelect 供业务页复用

## 9. Operation Log

- 只读：Time/User/Operation/Resource/Result 为主列
- requestId/correlationId 放**展开行**（面向管理员，非日志控制台）
- 分页 + user/operation/resourceType/result/时间范围过滤（More Filters）
- 无 edit/delete；敏感字段不展示

## 10. Permission / Security

- 所有写按钮 PermissionButton 门控：Create（system:user:create 等）、Disable（system:user:disable）等，无权限不占位
- **安全边界证据**（规格 §25）：
  - 前端：`user-view.spec` / `feature-views.spec` 验证 read-only user 看不到 write button
  - 后端：ManagementE2ETest 直接调 write API → 403（前端隐藏 ≠ 安全）
- 无 localStorage/token 硬编码进视图；无 axios/fetch 直连（api/enterprise.ts 只 import Platform Request Client，平台测试逐行扫描验证）

## 11. Fast Gate Results（各 Slice 即时验证）

| Slice | Gate | 结果 |
|---|---|---|
| A User | user-view.spec + type check | PASS |
| B Role/Permission | feature-views.spec（Role）+ permission-matrix.spec | PASS |
| C Department | DepartmentTree 测试 + feature-views | PASS |
| D Menu | menu seed 对齐断言 + feature-views | PASS |
| E Dictionary | DictionarySelect 测试 + feature-views | PASS |
| F Operation Log | feature-views（只读断言） | PASS |

## 12. WORK Acceptance Gate

| 项 | 结果 |
|---|---|
| A. 正式 Frontend Asset | ✅ frontend-enterprise-management |
| B. Platform Request Client | ✅ enterprise.ts 唯一入口，逐行扫描 0 直连 |
| C. 统一 Permission UI | ✅ PermissionButton/v-permission 全页面 |
| D. UX Baseline | ✅ Page Pattern/Workspace/Form 分级 |
| E. Tree 用 Workspace 非硬 Table | ✅ Department/Menu/Dictionary 主从+Workspace |
| F. Permission Matrix 可理解 | ✅ domain/resource/action 分组 + label |
| G. Loading/Empty/Error 统一 | ✅ AppTable/StateViews/SearchForm 统一态 |
| H. destructive confirm | ✅ disable 全部 ConfirmAction/MessageBox |
| I. 后端最终 enforcement | ✅ ManagementE2E 403 证据 |
| J. test/build PASS | ✅ 见 §13 |

## 13. Frontend Build/Test

- **Vitest: 39 tests 全绿（9 个测试文件）**：reusable-ui(7) + tree-and-select(6) + permission-matrix(3) + user-view(3) + feature-views(6) + WORK-001/002 既有 14
- **pnpm build（vue-tsc + vite）PASS**
- 期间修复：FreeMarker `${}` 与 JS 模板字符串冲突（全量转字符串拼接）、stub slot 透传、el-tree node-click data 签名、Vue 模板内 TS 类型注解禁用、未用 import 清理、Element Plus 全局注册（去显式 import）

## 14. Backend Compatibility

- **generated backend mvn test: 99 tests 全绿**（含 ManagementE2ETest 9、MenuE2ETest、DictionaryE2ETest）
- HTTP contract 零变化（WORK-003 API 原样消费）
- **menu seed 对齐 /system/* 路由**：7 个管理菜单（user/role/permission/department/menu/dictionary/operation-log），MenuE2ETest.adminSeesFullMenuTree 断言 systemChildren=7；viewer（无权限）仍见空树 ✓

## 15. Changed Files

**Core Engine 修改: NO**（零改动 generator-core 引擎代码）

- 新增：`capabilities/frontend-enterprise-management/`（28 文件：asset.yaml + 8 组件 + 7 views + router + types/api + 4 utils + 6 tests）
- 修改：`registry/capabilities.yaml`（+frontend-enterprise-management 登记）
- 修改：`capabilities/frontend-auth/templates/src/router/index.ts`（引入 enterpriseRoutes 受控注册）
- 修改：`capabilities/menu/templates/seed-zz-menu.sql`（7 管理菜单 + 路径对齐 + 权限码引用 registry）
- 修改：`capabilities/menu/templates/MenuE2ETest.java`（admin 菜单数断言 2→7）
- 修改：`tests/fixtures/v05-reference/frontend-auth/project.yaml`（+frontend-enterprise-management）
- 新增：`generator/.../ManagementUiWork004Test`（9 平台专项测试）

## 16. Known Limitations

1. **Generated-app runtime recipe 缺失（infrastructure/tooling）**：generated project 未提供标准 run recipe（main jar manifest 缺失、test-classes seed 不在 runtime classpath、H2 test 配置仅存在于 test 资源）。Browser Smoke 无法在临时环境启动 —— **按指令记录为工具链限制，不扩大 Scope 解决**，留 WORK-006（Browser E2E + Final Gate）统一设计 generated-app 运行配方（如 bootJar 配置 + 独立 runtime profile）。
2. Browser smoke 未做（Playwright 全量在 WORK-006）；UI 验证以 Vitest 组件级 + 后端 HTTP E2E 为准。
3. 1366×768 / 窄窗口 Gate 仅通过布局设计保证（响应式 grid + collapse），未做真实视口回归。
4. Accessibility 为基线实现（label/aria/focus/dialog 关闭），未做 WCAG audit。
5. Role Workspace 权限保存为"update + assignPermissions 两次调用"（后端各自原子）。

## 17. Acceptance

- Management UI 全量来自正式 Frontend Asset ✅
- 7 个管理页面（User/Role+Permission/Department/Menu/Dictionary/Operation Log）真实可用 ✅
- Reusable UI（AppTable/AppForm/SearchForm/StatusTag/ConfirmAction/FormDrawer/DepartmentTree/DictionarySelect）✅
- 统一 UX Baseline / 权限 UI / Request Client / 类型安全 ✅
- 前端 39 tests + build PASS ｜ 后端 99 tests PASS ｜ 平台 394 tests PASS ｜ Conformance PASS ｜ validators PASS ✅
- Core Engine 零修改；未 commit/push

**V05-WORK-004 = PASS**

（不是 V0.5 COMPLETE）
