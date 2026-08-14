# persistence — Capability Asset

持久化 capability contract（Capability ≠ Module；不绑定具体技术）。

- 定义 datasource 配置契约（secretRef/configRef，不落明文）
- 技术实现由 provider 提供：mybatis-plus（见 providers/mybatis-plus）
- compatibleProviders: [mybatis-plus]
