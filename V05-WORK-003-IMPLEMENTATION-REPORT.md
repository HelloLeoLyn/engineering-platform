# V05-WORK-003 Implementation Report — Enterprise Management APIs（Acceptance Fix Round 1）

- **日期**: 2026-08-15 ｜ **基线**: V05-WORK-002 ACCEPTED ｜ **状态**: IMPLEMENTED（未 commit/push）
- **前置**: V05-WORK-001/002 ACCEPTED ｜ V0.4 Enterprise Backend Foundation

## 1. API Scope

补齐 6 类管理 API + Operation Log 查询，全部复用 Platform Core（ApiResponse/PageQuery/PageResult/PlatformException/ErrorCode）+ RBAC + Operation Log：

| 域 | 端点 | 管理语义 |
|---|---|---|
| User | /api/users | list(分页+过滤)/detail/create(初始密码)/update/enable/disable/assign-roles/assign-department |
| Role | /api/roles | list(分页+过滤)/detail/create/update/enable/disable/assign-permissions + DataScope |
| Permission | /api/permissions | list(按 code 过滤)/detail —— **READ-ONLY registry 治理**（无 create/delete） |
| Department | /api/departments | tree/detail/create/update/enable/disable |
| Menu | /api/menus | tree/detail/create/update/enable/disable（+保留公开 /me） |
| Dictionary | /api/dictionaries | types/type CRUD/items CRUD/enable/disable + 保留公开 {code}/items |
| Operation Log | /api/operation-logs | 分页查询 + 过滤（user/operation/resourceType/result/time）—— 只读 |

**无第二套 CRUD framework / BaseController / 通用 CRUD Generator**。

## 2. User Management

- GET 分页 + username/enabled/department 过滤；GET detail
- POST create：username 必填/格式/唯一校验、department 存在且 enabled、role 存在且 enabled、**BCrypt 初始密码**（自定义或系统生成 12 位随机，创建响应只返回一次）
- PUT update / enable / disable；POST roles（replace 语义）；POST department
- **密码安全**：不返回 passwordHash、不回显已有密码、不进 operation log（测试验证）
- disabled user 继续不能登录（E2E 验证 disable→login 403→re-enable→login 200）

## 3. Role / Permission Management

- Role：分页 + code/name 过滤；create（code 格式校验/唯一）、update（**code 不可变**——稳定 role code 治理）、enable/disable、assign-permissions（replace + permission 存在校验）、DataScope 设置（枚举校验）
- Permission：**READ-ONLY registry 治理**——权限码来自系统/资产声明，UI 不得创建无 enforcement 的权限
- 权限码体系（seed 新增 24 项，id 7-30）：system:user:create/update/disable/assign-role/assign-department、system:role:create/update/disable/assign-permission、system:permission:read、system:department:read/create/update/disable、system:menu:read/create/update/disable、system:dictionary:read/create/update/disable、system:operation-log:read；admin（role 1）全量授权（role_permission id 100+，避开其他资产 seed）

## 4. Department

- tree（稳定排序 + 结构校验）/detail/create/update/enable/disable
- 结构安全复用 DepartmentService.validateStructure：self-parent 拒绝、cycle 拒绝、invalid parent 拒绝
- disabled 部门不得分配给新用户（requireEnabledDepartment）

## 5. Menu

- tree（管理视图带权限）/detail/create/update/enable/disable
- 结构安全复用 MenuService：self-parent/cycle/invalid parent 拒绝
- **permissionCode 非空必须对应已存在权限**（MENU_PERMISSION_NOT_FOUND）——Menu ≠ Authorization

## 6. Dictionary

- Type：list/detail/create（code 唯一）/update（**code 不可变**——稳定 code 治理）/enable/disable
- Item：list/create（(typeId,value) 唯一）/update/enable/disable/sort
- **product_status 等业务引用不受影响**（E2E 验证创建/禁用后 product_status 仍返回 2 items）

## 7. Operation Log Query

- GET /api/operation-logs 分页 + 过滤（userId/operation/resourceType/result/from/to）+ PageQuery/PageResult
- 只读（无 create/update/delete）；保留 /latest（WORK-004 兼容）
- 管理写操作全部 @OperationLog：USER_CREATE/UPDATE/ENABLE/DISABLE/ASSIGN_ROLE/ASSIGN_DEPARTMENT、ROLE_*、DEPARTMENT_*、MENU_*、DICTIONARY_*

## 8. RBAC

- 所有管理端点 @RequirePermission（稳定权限码，非临时字符串）
- E2E 验证：admin 全操作、sales-user 写 403、anonymous 401
- 前端按钮隐藏不是安全边界（后端 403 真实）

## 9. Data Permission Decisions

管理 API 属 **platform/system management**，不套业务 DataScope（规格 §18）：
- User/Department/Role/Menu/Dictionary/OperationLog = 系统管理面（仅 RBAC）
- Product 业务面保持 DataScope（V0.4 不变）
- 不机械给系统表套 Product DataScope

## 10. Transactions

- User create + role assignment、role permission replace、user role replace、user department assignment 均为原子（单事务内 Mapper 操作，失败不留下半完成 relation；复用 Spring 默认事务，无新 framework）

## 11. Error Model

稳定业务错误（非 raw SQL 泄漏）：USERNAME_DUPLICATE/USER_NOT_FOUND/DEPARTMENT_NOT_FOUND/DEPARTMENT_DISABLED/ROLE_NOT_FOUND/ROLE_DISABLED/ROLE_CODE_DUPLICATE/PERMISSION_NOT_FOUND/DEPARTMENT_SELF_PARENT/DEPARTMENT_CYCLE/MENU_CODE_DUPLICATE/MENU_PERMISSION_NOT_FOUND/DICTIONARY_TYPE_CODE_DUPLICATE/DICTIONARY_ITEM_DUPLICATE 等。全部 ApiResponse + ErrorCode。

## 12. Conformance

- rbac/organization/menu/dictionary asset.yaml 扩展 requiredFiles（管理 Controller/Service/测试）
- 删除权限 enforcement → conformance FAIL（asset.required-file）
- MyBatis 泄漏扫描：**api/application/domain 零 MyBatis 技术类型**（QueryWrapper/BaseMapper/IPage/com.baomidou 全部为 0），MyBatis 仅存在于 infrastructure Repository（10 个文件）；平台测试 `mybatisLeakageScan` 全绿

## 12b. Layer Correction (Acceptance Fix Round 1)

**修复前**：管理 Service（User/Role/Permission/Department/Menu/Dictionary ManagementService）误放 infrastructure/persistence，直接持有 Mapper + QueryWrapper —— 违反 Backend/Domain 分层。

**修复后**（与 V0.4 既有 Port 模式一致）：

```
Controller / API
 ↓
Application Service（编排/事务/业务校验，@Transactional）
 ↓
Port / Repository Contract（application 层接口，零 MyBatis）
 ↓
Infrastructure Repository（MyBatis 实现）
 ↓
Mapper / QueryWrapper / BaseMapper
```

- **Application Services**（7 个，全部零 MyBatis）：UserManagementService、RoleManagementService、PermissionManagementService、DepartmentManagementService、MenuManagementService、DictionaryManagementService、OperationLogQueryService
- **Infrastructure implementations**（7 个，MyBatis 全部收口于此）：MybatisUserManagementRepository、MybatisRoleManagementRepository、MybatisPermissionManagementRepository、MybatisDepartmentManagementRepository、MybatisMenuManagementRepository、MybatisDictionaryManagementRepository、MybatisOperationLogRepository（既有）
- **Ports**（6 个新增接口）：UserManagementPort、RoleManagementPort、PermissionManagementPort、DepartmentManagementPort、MenuManagementPort、DictionaryManagementPort（OperationLogPort 既有）
- **事务边界**：create+role 分配 / role 权限替换 / Department/Menu/Dictionary 写 全部 @Transactional 于 Application Service；E2E 新增真实回滚断言（无效 role → 400 → 列表 0 残留）
- HTTP API contract 不变（/api/users、/api/roles、/api/permissions、/api/departments、/api/menus、/api/dictionaries、/api/operation-logs 原样）；WORK-002 前端消费接口（/api/users/me 等）未动

## 13. Real HTTP E2E

生成项目新增 `ManagementE2ETest`（9 项真实 HTTP）：
- User CRUD 生命周期（create+dept+roles→login→duplicate→bad dept→disable→login 403→enable→login 200）
- User 列表分页过滤
- 受限用户写 403
- Role 生命周期（create+dataScope+permissions→duplicate→assign→disable）
- Department CRUD + self-parent 拒绝 + tree 反映 + disable
- Menu create + invalid permission 拒绝
- Dictionary create type/item + duplicate 拒绝 + disable + **product_status 不受影响**
- Operation Log 查询（写操作后真实落库 + 过滤 + 无敏感字段）
- anonymous 401

## 14. Tests / Regression

- 生成项目：**99 tests 全绿**（90 基线 + ManagementE2E 9）
- 平台全量：**385 tests 全绿**（342 基线 + WORK-002 16 + WORK-003 12，资产演进断言同步：rbac 22→35、menu 12→14、dictionary 14→15、organization 8→9）
- validators 7/7 PASS
- **前端兼容**：pnpm test 12/12 + build ✓（/api/users/me 未破坏）
- V0.2/V0.3/V0.4 回归不破坏（backend-only 无 frontend，V008 等保留）

## 15. Changed Files

新增（模板）：
- rbac：UserManagementService/UserManagementController/RoleManagementService/RoleManagementController/PermissionController/PermissionManagementService/UserCreateRequest/UserUpdateRequest/UserResponse/RoleCreateRequest/RoleUpdateRequest/RoleResponse/ManagementE2ETest（13）
- organization：DepartmentManagementService/DepartmentManagementController（2）
- menu：MenuManagementService/MenuManagementController（2）
- dictionary：DictionaryManagementService/DictionaryManagementController（2）
- 平台测试：ManagementApiWork003Test（12 tests）

修改：
- rbac/organization/menu/dictionary asset.yaml（登记 + conformance + 文件数）
- rbac seed-test-data.sql（权限码 7-30 + admin 授权 100-123）
- rbac UserController（保留 /me；users 列表移交管理 Controller）
- menu MenuController（tree 移交管理 Controller，保留 /me）
- organization（删除旧 DepartmentController，由管理 Controller 取代）
- dictionary（删除旧 DictionaryController，items 查询并入管理 Controller）
- operation-log：OperationLogPort/MybatisOperationLogRepository（+page 方法）、OperationLogReferenceController（+分页查询）、OperationLogModelUnitTest（CapturingPort 适配）
- 既有平台测试断言同步（rbac/menu/dictionary/organization 文件数）
- product-reference DataScopeE2ETest（部门树断言 >=3，容忍管理测试数据）

**Core Engine 零修改**。

## 16. Known Limitations

- 密码重置工作流/注册/MFA 未实现（规格明确 Non-Scope）
- Role code / Dictionary type code / Dictionary item value 不可变（稳定 code 治理，规格 §7/§14 设计）
- Permission 无 create/delete（registry 治理，规格 §8）
- 管理 API 不套业务 DataScope（系统管理面，规格 §18 决策）
- 无 UI（WORK-004）；无批量操作/软删除

## 17. Acceptance

- 生成 Enterprise Backend 通过稳定 API 真实管理 User/Role/Permission(read)/Department/Menu/Dictionary/OperationLog(query) ✅
- Authentication ✅ ｜ RBAC（真实 enforcement）✅ ｜ Data Permission boundary（系统面不套用）✅ ｜ Operation Log（写操作落库）✅ ｜ Transactions ✅ ｜ Error Model ✅ ｜ Repository Boundary（零泄漏）✅
- 99 生成项目 tests + 385 平台 tests + 前端 12 tests 全绿

**V05-WORK-003 = PASS**

（不是 V0.5 COMPLETE）
