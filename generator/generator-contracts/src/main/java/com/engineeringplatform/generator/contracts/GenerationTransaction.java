package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * GenerationTransaction（V0.7 §21 Generation Executor / Rollback Standard V1 [DECIDED]）。
 * 增量修改使用 GenerationTransaction，执行前生成 Backup、Operation Log、RollbackManifest。
 * 只备份 Plan 实际会修改/删除的资产，避免整仓复制。
 * Executor 是执行层，允许记录执行时间（Resolver pure computation 无时间戳规则不适用）。
 */
public record GenerationTransaction(
        String transactionId,
        String planId,
        TransactionState state,
        List<String> operations,
        Map<String, Object> timestamps,
        String result) {

    public GenerationTransaction {
        operations = operations == null ? List.of() : List.copyOf(operations);
        timestamps = timestamps == null ? Map.of() : Map.copyOf(timestamps);
    }

    public enum TransactionState {
        STAGED,
        APPLIED,
        COMMITTED,
        FAILED,
        ROLLED_BACK
    }
}
