# Engineering Platform 快速开发模式 v1（FAST DEVELOPMENT MODE）

- **状态**: 正式规范（2026-08-15 定稿）
- **适用范围**: 所有 Agent（OpenClaw / Codex / Hermes / 未来其他 Agent）执行 Engineering Platform（仓库根目录 `engineering-platform`）任务时，统一遵守本规范。
- **默认 Verification Mode**: `FAST_DEV`，除非任务明确指定 `WP_ACCEPTANCE` 或 `RELEASE_GATE`。

---

## 1. Verification Modes

### FAST_DEV（默认）
- 每个 Feature Slice 只跑：
  - 相关单元/组件测试（affected tests）
  - 相关类型检查 / build（必要时）
  - 相关后端 API integration（必要时）
- **禁止**：platform full Maven regression、历史版本 full regression、全量 Browser E2E、无关测试。
- 质量标准不降低，只减少重复验证。

### WP_ACCEPTANCE（工作包验收）
- 任务明确指定时启用（如 V05-WORK-003/004/005）。
- 开发阶段默认禁止 Full Regression。
- 包结束时执行一次该 WP 的 Acceptance Gate（相关测试 + 专项测试 + conformance + validators）。
- 如确有必要执行 Full Regression，必须先输出 `VERIFICATION_ESCALATION_REQUIRED`，未经批准不得执行。

### RELEASE_GATE（发布门禁）
- 唯一允许 Full Regression 的模式（如 V05-WORK-006）。
- 执行：platform full regression + 生成项目全量 + Browser E2E + validators + 历史兼容 + CI。
- 结束后输出 `V0.x_RELEASE_READY = YES / NO`。

---

## 2. Verification Budget

| 活动 | FAST_DEV | WP_ACCEPTANCE | RELEASE_GATE |
|---|---|---|---|
| affected tests | ✅ | ✅ | ✅ |
| 相关 type/build | ✅ | ✅ | ✅ |
| 相关 API integration | ✅ | ✅ | ✅ |
| WP 专项平台测试 | — | ✅ 一次 | ✅ |
| conformance + validators | 资产/核心有改动时 | ✅ | ✅ |
| platform full regression | ❌ | ❌（除非 Escalation 获批） | ✅ 一次 |
| 历史版本 full regression | ❌ | ❌ | ✅ |
| 全量 Browser E2E | ❌ | ❌ | ✅（Golden Path） |

## 3. Verification Escalation

修改以下核心区域时，**禁止自行开始 Full Regression**，必须先输出：

```
VERIFICATION_ESCALATION_REQUIRED
- changed core component
- reason
- possible blast radius
- recommended additional tests
```

核心区域：Resolver / EPM / GenerationPlanner / Executor / AssetRepository / Ownership / PathSafety / Conformance Engine / Schema core semantics / 生成器模板渲染层（buildPom 等）。

如果 Core Engine 未修改：禁止升级 Full Gate。

## 4. Scope Lock

- 只做任务明确要求的工作。不添加未要求的业务能力。
- 想到新功能/优化 → 先问，不私自加菜。
- 每包完成后停止，等用户指令；不自动跳到下一包。

## 5. Stable Zone

- 已通过验收的功能区（如已 ACCEPTED 的 WORK 资产）视为 Stable Zone。
- 默认不触碰；确需演进必须说明理由并保持既有契约不破坏。

## 6. Working Context / Repository Read Budget

- 开发前先读必要上下文（规格 + 相关资产 + 相关测试），不无脑全仓扫描。
- 避免重复读同一批大文件；需要时用 grep/定向读取。
- 禁止 `find /` 大搜、禁止猜测性 trial-and-error 排查。

## 7. Minimum Implementation

- 最小实现满足规格，不超前设计。
- 不为"将来可能"加抽象；不为测试打补丁式的产品改动。

## 8. No Speculative Abstraction

- 禁止在无第二处真实使用前引入抽象层/框架/通用机制。
- 例如：不做 Generic CRUD Generator / 第二套 Engine / 平行组件体系。

## 9. Asset Maturity

- 新能力必须以 Asset 形式落地（Asset → Registry → Resolver/EPM → GenerationPlan → Executor）。
- 禁止 shell copy、禁止绕过生成管线。

## 10. Product First UI

- UI 默认实现顺序：Agent implementation → affected tests → build → Runtime Recipe → human acceptance → targeted fix。
- 复杂产品交互以人工验收为主，不默认用大规模 Playwright 替代。

## 11. Human Acceptance

- 功能完成后提供：frontend URL / backend URL / test accounts / manual acceptance checklist，供人工验收。
- 人工验收完成前不 tag 正式版本。

## 12. Golden Path Browser E2E

- Playwright 定位为 **Golden Path Browser E2E**（核心路径冒烟：login + shell + dynamic menu + 核心业务 create/edit + read-only UI + backend 403 bypass + DataScope 行集 + Operation Log 可见）。
- **不是** Full CRUD Browser Regression；详细 CRUD 由 Vitest + HTTP E2E 覆盖。
- 一个 Browser case 修复后只重跑 affected spec，不重新执行 Full Gate。

## 13. Runtime Recipe

- 生成应用的标准启动方式由 Runtime Recipe 提供（scripts/dev-start.sh / dev-stop.sh / dev-status.sh）。
- 禁止 Agent 手工猜测：java -jar / spring-boot:run / classpath / 临时 datasource / 临时 profile / 手工找进程。
- 端口冲突由 Recipe 自动处理（自动分配 + .runtime/*.port state），不 kill 无关进程。
- Browser E2E / 人工验收从 runtime state（.runtime/*.url）读取实际 URL，不硬编码。

## 14. Test Responsibility Layers

| 层 | 负责内容 |
|---|---|
| Vitest 组件/单元 | UI 行为、状态、权限可见性 |
| 生成项目 HTTP E2E | API 契约、RBAC、DataScope、Operation Log |
| 平台专项测试 | 资产/生成器/一致性/确定性 |
| Golden Path Browser E2E | 核心路径冒烟（人工验收补充） |
| Conformance | 资产与生成物契约 |

## 15. No Opportunistic Refactor

- 不在任务中顺手重构无关代码。
- 重构只发生在任务明确要求或修复阻塞问题所需。

## 16. Short Reports

- 实现报告精简：结构 / 实现要点 / 验证证据 / 改动文件 / 已知限制 / Acceptance。
- 报告文件 UTF-8 BOM，通过 MEDIA 交付，不进入 git（除非用户要求）。

## 17. Git Strategy

- 默认**不 commit / push / tag**，等用户指令。
- 用户明确说"提交/推送"时才执行；RELEASE 轮用户明确授权时例外。
- 代码由用户决定提交时机；报告文件不进 git。

## 18. FAST_DEV_BLOCKED（two-attempt rule）

同一问题**连续两次修复失败**后，停止继续尝试，输出：

```
FAST_DEV_BLOCKED
- blocker
- attempts（已尝试的方案）
- evidence（失败输出/日志要点）
- recommended next action（建议：换方案 / 问用户 / 标记 limitation）
```

不得无限 trial-and-error。

## 19. Tool/Agent Cost Awareness

- 优先用轻量验证（单测 > 组件测 > HTTP E2E > Browser > Full regression）。
- 避免重复全量跑；避免无关测试。
- 长等待用后台 + 轮询，不空转。

## 20. Recommended Development Loop

```
1. 读规格 + 相关资产 + 相关测试（定向）
2. 设计最小实现（Asset-first，无投机抽象）
3. 实现 → affected tests → build
4. 必要时用 Runtime Recipe 启动验证
5. 输出报告（短）+ 停止等指令
6. 复杂 UI → 人工验收 → targeted fix
```

---

## 附：规则优先级

1. 用户当前明确指令 > 2. 本规范 > 3. 仓库既有规则（AGENTS.md 等）。
本规范只约束验证范围与工作方式，不降低质量标准，不替代安全红线。
