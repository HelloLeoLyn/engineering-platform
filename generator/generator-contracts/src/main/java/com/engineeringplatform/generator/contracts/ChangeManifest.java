package com.engineeringplatform.generator.contracts;

/**
 * ChangeManifest（V0.7 §22 AI Engineering Artifact Schema V1 [DECIDED]）。
 * 记录 Executor 实际准备/执行的工程变化：target path / operation / before / after / ownership / status。
 * 不塞完整大文件内容——使用 hash / size / artifact ref 等最小方式。
 */
public record ChangeManifest(
        String changeId,
        String planId,
        String transactionId,
        java.util.List<ChangeEntry> entries,
        ChangeResult result,
        String createdAt) {

    public ChangeManifest {
        entries = entries == null ? java.util.List.of() : java.util.List.copyOf(entries);
    }

    public record ChangeEntry(
            String targetPath,
            OperationType operationType,
            Ownership ownership,
            ChangeStatus status,
            FileState before,
            FileState after,
            String reason) {
    }

    public enum ChangeStatus {
        STAGED,
        APPLIED,
        SKIPPED,
        BLOCKED,
        ROLLED_BACK
    }

    public record FileState(boolean exists, String hash, long size, String ref) {
    }

    public record ChangeResult(
            String status,
            int appliedCount,
            int failedCount,
            int skippedCount,
            String message) {
    }
}
