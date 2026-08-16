# Browser E2E (Playwright)

正式 Browser Acceptance 套件。URL 从 Runtime Recipe state 读取，不硬编码。

## Run

```bash
# 1. 确保 runtime 已启动（backend + frontend）
./scripts/dev-start.sh
./scripts/dev-status.sh   # 确认 READY

# 2. 安装依赖并执行
cd e2e
pnpm install
pnpm e2e                 # 或 npx playwright test
```

## Coverage

- login / logout / wrong password
- dynamic menu by role（admin 全量 / viewer 过滤）
- user management（search/filter/create/edit/disable+confirm）
- role workspace（permission matrix + data scope）
- department（tree + workspace + self-parent 拒绝）
- menu management（permission selector）
- dictionary（master-detail + create + disable）
- operation log（写操作落库 + 展开详情）
- product full-stack（list/search/create/detail/disable）
- security boundary（read-only UI 隐藏 + 后端 403 bypass gate）
- data scope（ALL / DEPARTMENT / SELF 行集 + 越权 detail 404）

截图失败时自动保留在 `test-results/`。
