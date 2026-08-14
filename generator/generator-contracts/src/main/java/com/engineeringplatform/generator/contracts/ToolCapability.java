package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Tool Capability V1（EP-WORK-009 §六）。
 * 定义 capability ≠ 本轮实现 Browser。V0.7 无正式分类 → 最小枚举。
 */
public enum ToolCapability {
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    GIT_READ,
    GIT_WRITE,
    SHELL_READ,
    SHELL_WRITE,
    BROWSER_READ,
    BROWSER_WRITE
}
