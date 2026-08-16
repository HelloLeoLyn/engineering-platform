# Engineering Platform Agent Rules

## Repository Purpose

This repository contains the Engineering Platform.
It is separate from AI Dev OS.

AI Dev OS orchestrates engineering work.
Engineering Platform provides reusable application architecture, capabilities,
providers, modules, generator assets, schemas, and engineering standards.

## Core Principles

1. Stable Core
2. Default does not mean fixed
3. Depend on capability contracts, not concrete providers
4. Business modules must not depend on provider implementations
5. Generator output must be deterministic
6. Runtime secrets must never be stored in manifests
7. User-owned files must not be overwritten by generators
8. Released database migrations are immutable
9. Scope expansion requires explicit planning
10. Fix generator defects in the generator, not repeatedly in generated projects

## Dependency Direction

Application -> Module -> Capability Contract -> Platform Core
Provider -> Capability Contract

Forbidden:
- Business Module -> Concrete Provider
- Platform Core -> Business Module
- Capability Contract -> Concrete Provider

## Generator Ownership

- GENERATED: generator fully owns the file
- MANAGED: controlled structured updates only
- USER_OWNED: initial skeleton only, never overwrite user implementation
- IMMUTABLE: released assets/migrations must not be modified

## Safety

Forbidden by default:
- git reset --hard
- git clean -fd
- force push
- destructive migration
- deleting user-owned files
- overwriting released migrations

## Backend Quality Commands

Run from `backend/`.

```bash
./mvnw spotless:apply
./mvnw spotless:check
./mvnw clean verify
```

Do not bypass Maven Enforcer, Checkstyle, Spotless, ArchUnit, or tests.
If generated code breaks these checks, treat it as a generator/template defect.

---

## Fast Development Mode（强制规则）

完整规范：`docs/standards/FAST-DEVELOPMENT-MODE.md`（所有 Agent 必须遵守）。
本仓库默认 **Verification Mode = FAST_DEV**，除非任务明确指定 `WP_ACCEPTANCE` 或 `RELEASE_GATE`。

硬规则（违反即违规）：

1. **Verification Mode 默认 FAST_DEV**：每个 Feature Slice 只跑 affected tests + 相关 type/build + 相关 API integration。禁止 platform full regression / 历史版本 full regression / 全量 Browser E2E / 无关测试。
2. **Full Regression 只允许 RELEASE_GATE**。`WP_ACCEPTANCE` 默认禁止；如确有必要必须先输出 `VERIFICATION_ESCALATION_REQUIRED`（含 changed core component / reason / blast radius / recommended tests），未经批准不得执行。
3. **Scope Lock**：只做任务要求的工作，不私自加菜；每包完成停止等指令。
4. **Stable Zone**：已验收功能区默认不触碰；确需演进必须说明理由且不破坏既有契约。
5. **Minimum Implementation + No Speculative Abstraction**：最小实现；禁止投机抽象（第二套 Engine / Generic Generator / 平行组件体系）。
6. **Runtime Recipe**：生成应用启动必须走 `scripts/dev-start.sh / dev-stop.sh / dev-status.sh`（端口自动分配，state 在 `.runtime/`）。禁止猜测 java -jar / spring-boot:run / classpath / 临时 datasource / 手工找进程。
7. **Golden Path Browser E2E**：Playwright 只覆盖核心路径冒烟（login/shell/menu/核心业务/read-only UI/403 bypass/DataScope/OpLog），不做 Full CRUD Browser Regression；详细 CRUD 由 Vitest + HTTP E2E 覆盖。一个 case 修复只重跑 affected spec。
8. **Human Acceptance**：复杂 UI 提供 frontend/backend URL + test accounts + manual checklist 供人工验收；验收前不 tag 正式版本。
9. **FAST_DEV_BLOCKED（two-attempt rule）**：同一问题连续两次修复失败即停止，输出 blocker / attempts / evidence / recommended next action，不得无限 trial-and-error。
10. **No Opportunistic Refactor**：不顺手重构无关代码。
11. **Git Strategy**：默认不 commit/push/tag，等用户指令；报告文件不进 git（UTF-8 BOM，MEDIA 交付）。
12. **Tool/Agent Cost Awareness**：优先轻量验证（单测 > 组件测 > HTTP E2E > Browser > Full regression），避免重复全量跑与无关测试。
