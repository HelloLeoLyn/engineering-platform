# ADR — Engineering Platform V0.1

本目录记录 Engineering Platform 的架构决策记录（ADR）。

## 决策来源

设计基线文档：`Engineering Platform 设计决策记录 V0.7`（累计设计基线）。
本仓库的实现严格遵循 V0.7 已 DECIDED 内容；实现层面的补充决策记录如下。

## 已记录的实现决策（Implementation Choices）

| 决策 | 依据 | 记录位置 |
|---|---|---|
| Resolver 归属 generator 子系统（不进入 backend/platform-core） | V0.7 §12 执行链 / §11 最小核心 | EP-WORK-004B 报告 |
| profile 同层冲突 = deterministic declaration order | V0.7 未定义 → Implementation Choice | EP-WORK-004B 报告 |
| provider 选择 = explicit preference + declaration order | V0.7 未定义 → Implementation Choice | EP-WORK-004CD 报告 |
| resolutionId = res- + sha256(resolverVersion:inputHash)[0..12] | V0.7 §19 | EP-WORK-004 Fix R1 报告 |
| overwritePolicy = ALLOWED/STRUCTURED_ONLY/FORBIDDEN（由 Ownership 推导） | V0.7 §21 语义 | EP-WORK-005006 报告 |
| Acceptance Criterion 类型 = 最小枚举（BUILD/TEST/FILE_SCOPE/ARTIFACT/MANUAL/CUSTOM） | V0.7 无正式枚举 → 最小实现 | EP-WORK-007008 报告 |
| Tool Capability 8 类（V0.7 无正式分类） | V0.7 §15 机器可读规则 | EP-WORK-009 报告 |
| EP-WORK-002：ManifestValidationPort + ManifestRuntimeValidator（最小结构校验）；完整 JSON Schema engine → JDK25/DEPENDENCY GATE | V0.7 §17 | EP-WORK-010A 报告 |

## 占位说明

后续正式 ADR 文档（adr-0001-xxx.md 格式）将在相关决策需要独立追溯时补充。
