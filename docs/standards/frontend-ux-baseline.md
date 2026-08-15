# Frontend UX Baseline — Engineering Platform V0.5

- **日期**: 2026-08-15 ｜ **基线**: V05-WORK-002 ｜ **性质**: generated frontend 统一约束（后续管理 UI 必须遵守）
- **目标**: 生成的企业应用不是"Element Plus 默认组件拼装"；本文件是**最小约束**，不是 100 页 Design System。

## 1. Page Patterns

统一层级：`Application → Layout → Page → Section → Component`

- 页面必须有：title、可选 description、primary action 区域、content 区域
- 禁止页面内容直接顶到浏览器边缘

基础组件（`frontend/src/components/` + `frontend/src/permission/`）：

| 组件 | 用途 |
|---|---|
| PageContainer | 内容容器（max-width + spacing；fullWidth 用于 Table 页） |
| PageHeader | 标题 + 描述 + actions 区 |
| StateViews | Loading / Empty / Error / Permission Denied 统一状态（禁止页面空白） |
| PermissionGuard / PermissionButton / v-permission | 权限可见性（UX only） |

## 2. Spacing

统一 scale（`src/styles/tokens.css`）：

```
4 / 8 / 12 / 16 / 24 / 32   →  --ep-space-1..6
```

业务页面**禁止**随意 `margin: 13px` / `padding: 17px`，除非确有视觉理由并注释。

## 3. Page Width

- 企业后台主内容：`max-width: var(--ep-content-max-width)`（1200px）fluid layout
- Table 类页面：可使用全宽（PageContainer fullWidth）
- Form/Detail：不无限拉伸到超宽屏

## 4. Visual Hierarchy

- 优先用 spacing / typography / section grouping 表达层级
- 少用：Card 套 Card、满屏 border、Table 外再套重边框

## 5. Loading / Empty / Error / Permission Denied

- 平台组件必须预留统一状态（StateViews）
- 不能页面空白；Error 可带 Retry

## 6. Destructive Action

- Delete/Disable 等危险操作**必须有 Confirm**（Element Plus ElMessageBox.confirm 或统一 ConfirmAction）
- 颜色/文案统一（danger 语义色 `--ep-color-danger`）
- 禁止一次点击直接执行高风险操作

## 7. Feedback

- 成功操作：统一 success feedback（ElMessage.success 等）
- 失败：统一由 Request Client / Page Error 展示
- **禁止** `alert()`、`console.error` 作为用户错误反馈

## 8. Responsive Baseline

- V0.5 是 desktop enterprise application
- 至少保证 1366×768 与 1920×1080 主要功能可操作
- 较窄窗口 layout 不彻底破坏（Sidebar 可折叠）
- 不做 Mobile Management App

## 9. Accessibility Baseline

至少考虑（不要求完整 WCAG certification）：

- button semantic（el-button 原生 button）
- form label（el-form-item label）
- keyboard tab / focus state
- dialog focus（Element Plus 内置）
- aria/title（适用处）

## 10. Permission UX

- `v-permission` / PermissionGuard / PermissionButton 控制可见性
- **前端权限不是安全边界**：后端 `@RequirePermission` 是最终 enforcement
- 测试必须证明：绕过前端隐藏直接调后端 → 403

## 11. Direct HTTP Rule

- 业务 Feature（views/features/components）**禁止直接 axios/fetch**
- 统一走 `src/api/request.ts`（Platform Request Client）

## 12. Token / Security

- token 存 localStorage（V1 策略，风险：XSS 可读 → 后端 token 有效期控制 + 生产可切换 httpOnly cookie 方案）
- 禁止：token 进 URL、console、operation logs
- Request Client 自动注入 `Authorization: Bearer`
