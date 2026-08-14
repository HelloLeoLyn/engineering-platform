# Guides — Engineering Platform V0.1

## Manifest & Registry Guide

- **Manifest 四件套**（V0.1 已 ACCEPTED，EP-WORK-001）：`platform.schema.yaml` / `project.schema.yaml` / `module.schema.yaml` / `provider.schema.yaml`
- **Registry**（EP-WORK-003）：`registry.schema.yaml` + 8 个 registry（capabilities / errors / events / guides / modules / permissions / providers / tasks）
- **校验**：`python3 generator/scripts/validate-manifest.py --all`（9/9）、`validate-registry.py --all`（21/21）
- 规则：Manifest 仅承载声明式 Contract/Metadata；禁止保存 Secret / 运行时值 / 任意脚本（V0.7 §17）

## Resolver Guide

- **Contract**（EP-WORK-004A）：effective-project / resolution-report / resolver-error 三 Schema
- **Pipeline**（EP-WORK-004B+C+D）：13 步（Schema Validation → Reference → Profile → Defaults → Overrides → Constraints → Dependency → Capability → Provider → Compatibility → Security → Quality → Environment）
- 优先级：Platform Default < Profile Default < Project Preference < Customer Constraint < Platform Guardrail（V0.7 §18）
- 校验：`validate-resolver-contracts.py`（11/11）；一次 Resolve 产生 resolutionId / resolverVersion / inputHash

## Generator / Executor Guide

- **GenerationPlan**（EP-WORK-005）：声明式计划；创建 Plan 不改文件
- **Executor**（EP-WORK-006）：Validate → DryRun → Stage → Pre-Apply Check → Apply → Verify → Commit；失败自动 Rollback
- Path Safety 硬边界：拒绝 `../`、绝对路径、`.git/`、symlink escape；未知 Ownership fails safe
- 校验：`validate-generator-contracts.py`（9/9）

## Engineering Work Model Guide

- **WorkItem**（EP-WORK-007）：身份/边界/状态机（NEW→…→DONE + BLOCKED/FAILED/REJECTED/CANCELLED/REOPENED + ACCEPTED）
- **EngineeringPlan** / **ImplementationTasks**（DAG：unknown dependency + cycle 检测）
- **TestPlan**（声明式）/ **TestRun**（执行事实；SKIPPED 不自动=PASS）
- **VerificationReport**：VerificationEngine 机械计算 ACCEPT/REJECT/BLOCKED；MANUAL → PENDING_MANUAL
- 校验：`validate-engineering-work-contracts.py`（15/15）

## Agent Execution / Tool Guard Guide

- **ExecutionRequest**：scope/capabilities/timeout/maxAttempts 由平台控制面授予，Agent 不可自扩
- **Tool Guards**：Filesystem（复用 PathSafety）/ Git（结构化，禁 reset --hard/clean -fd/force push）/ Shell（白名单+高危黑名单，无任意 shell string）
- **Least Privilege**：默认 DENY；Approval：REQUIRE_APPROVAL → PENDING → APPROVED/REJECTED
- 校验：`validate-agent-execution-contracts.py`（9/9）

## Development Guide

```bash
# Python Contract 校验（统一入口）
./scripts/validate.sh --python

# Java 静态编译检查（本机 JDK 21 辅助；正式测试需 JDK 25）
./scripts/validate.sh --java

# 正式构建（CI 使用 Java 25）
mvn -f generator/pom.xml test
```

分支纪律：代码由 HelloLeoLyn 自行提交；本仓库 git 提交需用户明确指令。

## Testing Guide

- 测试全部使用临时目录 / in-memory fixture；禁止对真实仓库执行测试写入
- 不执行真实 sudo / destructive Git / 系统命令 / Browser
- E2E fixture：`tests/fixtures/e2e/minimal-project/`（E2EMinimalProjectTest，13 场景）
- 当前 JDK25_BUILD_GATE = PENDING：TESTS_EXECUTED=NO / TESTS_PASSED=NOT_VERIFIED（不得伪称通过）
