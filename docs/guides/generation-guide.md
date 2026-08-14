# Generation Guide（V0.2）

## 从 project.yaml 生成 Reference Spring Boot 项目

完整链路（全部由测试验证，V02ReleaseGateE2ETest）：

```bash
# 1. 声明项目（tests/fixtures/v02-reference/project.yaml 为真实示例）
#    - project.id / name / version / basePackage
#    - capabilities: [web, validation, exception-handling, audit]（依赖自动闭包）
#    - quality.minimum: Q3

# 2. Resolver（真实资产 → EPM）
#    AssetAwareResolver：capabilities/ + providers/ 资产依赖闭包 + 兼容校验 → EffectiveProjectModel

# 3. Generation（EPM → 文件集 → GenerationPlan → Executor）
#    AssetProjectGenerator：
#      - 依赖装配（资产 conformance.requiredDependencies + provider dependencies.yaml，去重/冲突检测）
#      - base 文件（pom.xml / application.yml / Application.java / ApiErrorTest）
#      - 资产模板（exception-handling/logging/audit/mybatis-plus 内容来自资产，非硬编码）
#      - GenerationPlanner → GeneratorExecutor（DryRun/Staging/Precondition/Transaction/Rollback 全启用）
```

## 关键行为

- **渲染**：render 模式做 `${key}` / `${key!'默认值'}` 替换；copy 模式原样复制
- **目标占位**：资产 target 的 `{package}` 解析为 basePackage 路径
- **配置合并**：资产 configuration 进入 application.yml（默认值 / provided 值 / secretRef·configRef 环境变量占位，绝不明文）
- **安全**：非法 target（path traversal）计划阶段拒绝；USER_OWNED 文件不被覆盖；只写指定输出目录
- **确定性**：同 project.yaml + 同资产集 → 同 planId / 同文件集

## 生成后

```bash
mvn -f <generated>/pom.xml test          # BUILD SUCCESS
python3 generator/scripts/validate-assets.py --all   # 资产契约校验
```
