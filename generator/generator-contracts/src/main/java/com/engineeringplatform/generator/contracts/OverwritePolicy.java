package com.engineeringplatform.generator.contracts;

/**
 * Overwrite Policy（覆盖策略）。
 *
 * ALLOWED          — Generator 完全拥有文件（GENERATED，Manifest 证明归属时可重生成）。
 * STRUCTURED_ONLY  — Generator 管理区域（MANAGED）：仅结构化修改。
 * FORBIDDEN        — User-owned / protected / forbidden：不得覆盖。
 *
 * 不得因为文件存在就直接覆盖；不得默认覆盖未知 Ownership 文件。
 */
public enum OverwritePolicy {
    ALLOWED,
    STRUCTURED_ONLY,
    FORBIDDEN
}
