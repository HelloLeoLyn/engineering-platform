# Conformance Guide（V0.2）

## Engineering Conformance V1

验证生成项目是否符合 Platform / Asset 工程标准（独立于 AI Dev OS 的 Development Task Verification，见 ADR-001）。

```java
ConformanceResult result = new ConformanceValidator(assetRepository)
        .validate(effectiveProjectModel, generatedProjectRoot);
// result.status(): PASS / FAIL（任一 ERROR finding → FAIL）
// result.findings(): ruleId / severity(ERROR|WARNING) / message / assetId / path
```

## 六类规则

| 类别 | ruleId | 说明 |
|---|---|---|
| Technology | technology.java-version | pom java.version == EPM 基线（25） |
| Technology | technology.spring-boot-version | pom parent 版本匹配平台/资产约束（3.x） |
| Structure | structure.required-file | pom.xml / src/main/java / src/main/resources / src/test/java |
| Dependency | dependency.required / dependency.forbidden | 资产 requiredDependencies 缺失 FAIL；forbiddenDependencies 存在 FAIL |
| Asset | asset.required-file | 资产 conformance.requiredFiles（{package} 替换后）存在 |
| Configuration | config.required | 资产 requiredConfig key 在 application.yml / application-mybatis.yaml |
| Provider | provider.mismatch | EPM resolved provider 的 dependencies.yaml GAV 在 pom 中 |
| 附加 | asset.tests-reference | 资产 tests 引用缺失 → WARNING（不 FAIL） |

## 使用建议

- 规则事实源 = EPM / 资产元数据（不在 validator 硬编码技术细节）
- Conformance PASS ≠ Build PASS，两者独立验证：
  - Conformance：工程标准合规
  - `mvn test`：真实可编译可测试
- 失败必须有明确 Finding（ruleId 稳定，非自然语言）
