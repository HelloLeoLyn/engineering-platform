﻿# V06-WORK-006 RESULT — Generate / Build / Run Developer Loop

- **日期**: 2026-08-16 ｜ **Mode**: WP_ACCEPTANCE
- **仓库**: /home/administrator/workspace/engineering-platform
- **状态**: 验收 A-M 通过，**未 commit / push**

---

## Implemented

- **Environment Preflight**（console-server `EnvironmentPreflight`）：
  - Java：required major（从 project.yaml stack 推断，enterprise-java25 → 25）vs effective java；扫描 /usr/lib/jvm、~/.sdkman、~/.java 多 JDK；命中 required 版本 → 项目级 projectJdk（如 /usr/lib/jvm/java-25-openjdk-amd64）
  - Maven / Node / pnpm / python3 轻量检测
  - Database：e2e profile 用 in-memory H2 → READY（无需外部 MySQL）；SPRING_DATASOURCE_URL env → WARNING（可行动）
  - Runtime Recipe：scripts 存在 + 可执行位；.runtime 进程状态；端口
  - 输出 READY / WARNING / BLOCKED + overall
- **Build**（console-server `RuntimeService.build`）：Build All / Backend / Frontend 异步执行；状态 QUEUED → RUNNING → PASS/FAIL；startedAt / durationMs / exitCode；日志流式写入 .runtime/build-*.log；后端 mvn package（项目级 JAVA_HOME），前端 pnpm build
- **Runtime**：Start / Stop / Restart / Status 全部委托 Runtime Recipe（scripts/dev-start.sh / dev-stop.sh / dev-status.sh 等价语义），Console 零散 java -jar / mvn spring-boot:run / pnpm dev
- **Logs**：backend / frontend / build 日志 tail N 行；Refresh / Auto refresh / Clear UI view；password/token/secret 脱敏
- **Console Projects 集成**：Projects 页 Runtime 列（RUNNING/STOPPED/UNKNOWN，实时读 .runtime）；Build & Run 入口 → 新 BuildRunView
- **Console UI**：Build & Run 页（Environment / Build / Runtime / Logs 四面板 + Actions + Open Application），延续 EP 视觉体系

## Toolchain Isolation

- **Java**：Preflight 探测多 JDK；Build/Start 通过 ProcessBuilder env 注入项目级 JAVA_HOME + PATH，**不修改** /etc/environment、~/.bashrc、系统 alternatives
- **Node / pnpm / Maven**：使用系统可用工具链（本机 node 24 / pnpm 11 / mvn 3.9.12），检测并报告 BLOCKED 若缺失
- 验证：B. 不修改系统全局 JDK（代码路径无任何全局写入；Preflight 只读探测）

## Runtime Recipe Reuse

- Console `/api/runtime/*` 全部走生成项目 Runtime Recipe：
  - start → `bash scripts/dev-start.sh [--backend|--frontend|all]`
  - stop → `bash scripts/dev-stop.sh`
  - status → 解析 .runtime state（backend/frontend pid/port/url + 健康探测）
  - open → 读 .runtime/backend.url、frontend.url（动态端口，不猜 5173）
- 未修改 Runtime Recipe 核心模板（V05-WORK-006 产物零改动），`RuntimeRecipeWork006Test` 9/9 回归通过

## Process Safety

- stop 只杀 .runtime/*.pid 记录的进程（dev-stop.sh 进程组 kill），验证：stop 后 console-server 8099 / mysql 存活
- PID 验证：status 用 ProcessHandle.isAlive 判活；stale pid → STALE
- 不按端口 kill 任意进程（Recipe 分配端口只做 bind probe，从不 kill 占用者）
- duplicate start：dev-start.sh is_pid_alive 检查 → 二次 start 返回 already running，不创建第二份实例
- stop 清理 .runtime state 文件
- Console 关闭不杀项目进程（setsid nohup 由 Recipe 保证）

## Real Lifecycle Proof

项目：`console/console-data/generated/v06-proof`（Supplier + CustomerLite，251 files，scripts/ 可执行）

- **Generate**: Console API → SUCCESS 251 files（modules: supplier, customer-lite）
- **Preflight**: overall READY（Java required 25 / detected 25 / projectJdk 命中；Maven/Node/pnpm/python3 READY；Database READY(H2 e2e)；Runtime Recipe READY）
- **Build Backend**: PASS（duration 4426ms，exit 0）
- **Build Frontend**: PASS（duration 9012ms，exit 0）
- **Start**: dev-start.sh → backend :8080 READY + frontend :5176 READY（动态端口分配）
- **Status**: backend RUNNING-READY http://localhost:8080 / frontend RUNNING-READY http://localhost:5176 / overall RUNNING
- **Duplicate**: 二次 start → "already running"，无第二实例
- **Open**: 实际 URL（backend http://localhost:8080、frontend http://localhost:5176）
- **Stop**: dev-stop.sh → 只杀 .runtime PID（backend/frontend 均 stopped）；console-server/mysql 存活；Status STOPPED
- **Restart**: stop → start → RUNNING-READY（新 PID）

## Verified

| 验收 | 结果 |
|---|---|
| A. Preflight 识别 Java 25 | PASS（required 25 / detected 25 / projectJdk） |
| B. 不修改系统全局 JDK | PASS（仅 ProcessBuilder env 注入） |
| C. Backend Build 从 Console 执行 | PASS（mvn package，PASS + duration + exitCode） |
| D. Frontend Build 从 Console 执行 | PASS（pnpm build，PASS） |
| E. Start/Stop/Restart/Status 全走 Recipe | PASS（dev-start/dev-stop，无散落命令） |
| F. Dynamic URL 正确读取 | PASS（8080 / 5176 来自 .runtime） |
| G. Open Application 用实际 URL | PASS（open 返回实际 URL） |
| H. Logs 可查看 | PASS（backend log tail + 脱敏；totalLines 正确） |
| I. Duplicate Start 安全 | PASS（is_pid_alive → already running） |
| J. Stop 不误杀其他进程 | PASS（console-server/mysql 存活） |
| K. Project 页面可见 Build/Runtime 状态 | PASS（Runtime 列 + Build & Run 入口） |
| L. 完整闭环 | PASS（Generate→Preflight→Build→Start→Open→Stop→Restart→READY） |
| M. 无 AI Dev OS / Agent | PASS |
| console-server 测试 | ConsoleBackendTest 10/10 + ModuleBuilderTest 4/4 + RuntimeServiceWork006Test 13/13 |
| console-web 测试/build | 6/6 + build 通过 |
| Runtime Recipe 回归 | RuntimeRecipeWork006Test 9/9 |
| git diff --check | clean |

## Visual Artifacts

- `docs/visual-smoke-v06/v06-shots-01-build-run.png` — Build & Run 页面（Environment READY + 四面板）
- `docs/visual-smoke-v06/v06-shots-02-runtime.png` — Running State（backend/frontend RUNNING-READY + URLs）
- `docs/visual-smoke-v06/v06-shots-03-logs.png` — Logs 视图（tail + 脱敏）

## Boundary Check

无 CI/CD、Docker、K8s、Deployment、Remote/SSH、Cloud Runtime、Agent、AI Dev OS、自动修复、可观测平台。Console 只做本地/项目级确定性 developer runtime（Generate→Preflight→Build→Start→Status→Logs→Open→Stop/Restart）。

## Escalation

**NO** —— 未修改 Runtime Recipe 核心安全语义；未引入第二套启动逻辑；未建复杂 job system。仅 Console 侧新增 Preflight 探测 + Recipe 委托 + UI。

## Known Limitations

- Build 状态为进程内内存态（Console 重启后 build 历史清空；.runtime 日志文件保留）
- 日志查看为简单 tail（无检索/过滤平台）
- MySQL 检查只针对 e2e(H2) 与 env 配置的轻量探测，不做数据库管理
- Preflight 的 Database 检查未尝试真实 JDBC 连接（避免引入密码/依赖），以配置语义为准

## Changed Files

- `console/console-server`：EnvironmentPreflight.java（新）、RuntimeService.java（新）、RuntimeServiceWork006Test.java（新）、ConsoleServer.java（/api/runtime 路由）、GenerationService.java（generic fixture 路径 + scripts 可执行位）
- `console/console-web`：BuildRunView.vue（新）、ProjectsView.vue（Runtime 列 + Build & Run 入口）、api/console.ts（runtime API）、router/index.ts（build-run → BuildRunView）、utils/contract.ts（默认含 runtime-recipe）
- 生成项目示例：console/console-data/generated/v06-proof（Supplier + CustomerLite + scripts/ + RUNTIME.md）

## git diff --stat

```
87 files changed, 2266 insertions(+), 370 deletions(-)
```

---

**V06-WORK-006 = PASS**（A-M 全部通过；未 commit、未 push；等待人工验收）
