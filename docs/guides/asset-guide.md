# Asset Guide（V0.2）

## 什么是 Engineering Asset

机器可读的可复用工程资产，契约见 `generator/schemas/engineering-asset.schema.yaml`。

四种类型：**MODULE** / **CAPABILITY** / **PROVIDER** / **TEMPLATE**。
语义红线：Capability ≠ Module；Provider ≠ Maven Dependency；Template ≠ Agent Prompt。

## 资产目录结构

```
capabilities/<id>/asset.yaml     # CAPABILITY 资产
capabilities/<id>/templates/     # 资产携带的模板（render/copy）
capabilities/<id>/docs/          # 使用说明
providers/<id>/asset.yaml        # PROVIDER 资产
providers/<id>/dependencies.yaml # Provider 的 Maven 依赖元数据（生产事实源，非 tests/fixtures）
providers/<id>/templates/
```

## asset.yaml 最小结构

```yaml
schemaVersion: 1
id: web                     # kebab-case，必须等于目录名
type: CAPABILITY            # MODULE/CAPABILITY/PROVIDER/TEMPLATE
version: 0.1.0              # MAJOR.MINOR.PATCH
description: ...
dependencies:               # 资产依赖（type/id/version/required）
  - type: CAPABILITY
    id: persistence
    required: true
compatibility:              # java/springBoot/requiredCapabilities/compatibleProviders
  java: "25"
  springBoot: "3.x"
files:                      # 生成内容（source/target/ownership/mode: render|copy）
  - source: templates/x.java.ftl
    target: src/main/java/{package}/x/X.java
    ownership: GENERATED
    mode: render
configuration:              # 声明式配置（type 含 secretRef/configRef；禁止 secret 明文）
  - key: spring.datasource.url
    type: secretRef
    required: true
conformance:                # 最小合规规则（requiredFiles/requiredDependencies/requiredConfig/forbiddenDependencies）
  requiredFiles:
    - src/main/java/{package}/common/error/ApiError.java
```

## Provider 依赖元数据

`providers/<id>/dependencies.yaml` 是**生产事实源**（V02-WORK-006 收口）；`tests/fixtures/*.gav.yaml` 仅测试数据，生产代码不读取。

```yaml
dependencies:
  - groupId: com.baomidou
    artifactId: mybatis-plus-spring-boot3-starter
    version: 3.5.17
    scope: compile
```

## 校验

```bash
python3 generator/scripts/validate-assets.py --all   # 契约 + fixtures + registry↔asset 一致性 + provider deps
```
