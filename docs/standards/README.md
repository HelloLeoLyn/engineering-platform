# Standards — Engineering Platform V0.1

## 技术基线（V0.7 §6 [DECIDED]）

- **Java 25 LTS**（正式基线，禁止降级；本机 JDK 21 为已知环境 blocker）
- Maven Multi-Module（generator/ 父 → generator-contracts / generator-core）
- JUnit 6 / AssertJ（测试栈）
- Python validators（build-time Contract 校验）：jsonschema + pyyaml

## Contract 标准

- 所有 Schema：`schemaVersion: 1`（properties.const）、`additionalProperties: false`、稳定 identity
- Schema 变化必须通过 Migration（V0.7 §17）；禁止 Generator 静默破坏旧项目
- Manifest 仅承载声明式 Contract/Metadata；禁止 Secret / 运行时值 / 任意脚本
- Artifact 使用统一 Header（schemaVersion / id / type / version / workItemId / provenance）（V0.7 §22）

## 依赖方向标准（V0.7 §9）

- `generator-contracts` ← `generator-core`（单向）；禁止 contracts 依赖 core
- Provider 只能依赖 Capability Contract；禁止反向依赖
- 共享能力必须通过 Promote-to-Platform 流程

## 安全标准

- **Path Safety 硬边界**：拒绝 `../`、绝对路径、`.git/`、symlink escape、workspace escape
- **Ownership 四级**：GENERATED / MANAGED / USER_OWNED / IMMUTABLE；未知 ownership fails safe
- **Least Privilege**：Tool 默认 DENY；只有 ExecutionRequest 明确授权才 ALLOW
- **Agent 边界**：Agent 可报告 DONE/FAILED/BLOCKED + Evidence，但不得自设 WorkItem=ACCEPTED
- **禁止破坏性命令**：git reset --hard / git clean -fd / force push / sudo / rm -rf / 系统关闭 / 包管理器变更

## 测试标准

- 测试全部使用临时目录 / in-memory fixture；禁止对真实仓库执行测试写入
- 不执行真实 sudo / destructive Git / 系统命令 / Browser
- 诚实报告：TESTS_EXECUTED / TESTS_PASSED / TEST_SOURCES_STATIC_COMPILE 不得伪称
- Policy Guard ≠ OS Sandbox（ShellGuard 是 V1 Policy Guard，不是完整 sandbox）

## 命名/风格（V0.7 §10）

- 类名直接表达职责；统一职责后缀（Controller/Service/Resolver/Validator/Guard 等）
- Error Code 使用 UPPER_SNAKE_CASE；Event Code lowercase dotted；Permission Code domain:resource:action
