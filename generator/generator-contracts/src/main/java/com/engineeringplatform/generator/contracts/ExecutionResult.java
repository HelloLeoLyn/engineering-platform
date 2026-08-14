package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Executor 执行结果（V0.7 §21：执行输出 Generation Execution Report、Change Manifest、
 * workspace diff 与 output hash）。
 * SUCCESS / FAILED / ROLLED_BACK 三态；任何 operation 失败不得假装整体 SUCCESS。
 */
public record ExecutionResult(
        String transactionId,
        String planId,
        ExecutionStatus status,
        ChangeManifest changeManifest,
        List<String> messages) {

    public ExecutionResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public enum ExecutionStatus {
        SUCCESS,
        FAILED,
        ROLLED_BACK
    }
}
