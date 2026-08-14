# Getting Started — Engineering Platform V0.3

面向第一次使用 Engineering Platform 的开发者。你只需要一份 `project.yaml` 和一个 `ep` 命令，不需要写任何 Java 集成代码。

## 1. Prerequisites

- **Java 25**（`java -version` 确认）
- **Maven**（`mvn -version` 确认）
- **Engineering Platform 仓库**（包含 `ep` 命令、`platform.yaml`、`capabilities/`、`providers/`）

```bash
cd engineering-platform
./ep --version        # 确认 CLI 可用
```

## 2. 创建 project.yaml

最小可复制示例（完整真实字段，复制即用）：

```yaml
schemaVersion: 1
project:
  id: my-service          # 项目 id（也是默认 artifactId）
  name: My Service        # 应用名（spring.application.name）
  version: 1.0.0          # 项目版本
  basePackage: com.acme.myservice   # Java 包根（生成代码位置）
  groupId: com.acme       # 可选；缺省由 basePackage 推导
  # artifactId: my-service  # 可选；缺省 = project.id
platform:
  id: engineering-platform
capabilities:
  - id: web
  - id: validation
  - id: exception-handling
  - id: audit
quality:
  minimum: Q3
```

> capabilities 可选的工程能力：`web`、`validation`、`exception-handling`、`logging`、`persistence`、`audit`。依赖会自动补齐（例如 `audit` 会自动引入 `persistence` + `logging`）。

## 3. 标准流程（5 步）

```bash
# 1) 校验 manifest
./ep validate project.yaml

# 2) 解析（查看将得到什么：capabilities / providers / quality）
./ep resolve project.yaml

# 3) 生成 Spring Boot 项目
./ep generate project.yaml --output ./my-project

# 4) 工程合规检查
./ep conformance project.yaml ./my-project

# 5) 构建（生成项目本身是完整 Maven 项目）
cd my-project
mvn test
```

全部成功即得到一个真实可编译的 Spring Boot 项目（Java 25，含错误处理/日志/审计/持久化基线）。

## 4. 配置与 secretRef 说明

- 生成的项目在 `src/main/resources/application.yml` 含基础配置（`spring.application.name`、`server.port` 等）。
- 需要项目级配置时，在 project.yaml 声明：

```yaml
project:
  ...
  configuration:
    server.port: 9090
```

- **数据库等敏感配置**（`spring.datasource.url` / `username`）是引用类型：生成的是占位符，不会出现明文。运行前设置环境变量即可：

```bash
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/mydb"
export SPRING_DATASOURCE_USERNAME="app"
```

生成成功时 CLI 会提示需要哪些环境变量（`key -> env KEY`）。

## 5. 常见错误与处理

| 现象 | 原因 | 处理 |
|---|---|---|
| `ERROR: manifest file not found` | 路径写错 | 使用正确的相对/绝对路径 |
| `[FAIL] Manifest invalid: missing required field: project` | manifest 缺字段 | 对照第 2 节模板补全 |
| `ERROR: output directory is not empty` | 输出目录已有文件 | 换一个全新目录（安全设计：不覆盖） |
| `ASSET_MISSING: Engineering asset not found: xxx` | capabilities 引用了不存在的资产 | 检查能力名拼写 |
| 生成成功但 mvn test 失败 | 环境变量未设置 | 按 generate 的提示设置占位符环境变量 |

## 6. CLI Reference（简要）

| 命令 | 作用 |
|---|---|
| `./ep validate <project.yaml>` | 校验 manifest |
| `./ep resolve <project.yaml>` | 解析出 EPM 摘要（能力/提供方/质量） |
| `./ep generate <project.yaml> --output <dir>` | 生成 Spring Boot 项目 |
| `./ep conformance <project.yaml> <project-dir>` | 工程合规检查（PASS/FAIL + findings） |
| `./ep --help` / `./ep --version` | 帮助 / 版本 |

**Exit codes**：`0` = 成功 ｜ `1` = 校验/解析/生成/合规失败 ｜ `2` = 命令用法错误

**任何目录可用**：`ep` 不依赖你在哪个目录运行（仓库内、子目录、仓库外均可；manifest 和 output 路径相对你当前目录）。
