package com.engineeringplatform.generator.contracts;

/**
 * Dry Run 结果（V0.7 §20：支持 Dry Run/Preview）。
 * Dry Run 不得修改任何真实文件；与真实 Apply 使用同一套 policy evaluation。
 */
public record DryRunResult(
        String planId,
        boolean executable,
        java.util.List<DryRunChange> plannedChanges,
        java.util.List<DryRunBlocked> blockedOperations,
        java.util.List<DryRunConflict> conflicts,
        java.util.List<String> warnings) {

    public DryRunResult {
        plannedChanges = plannedChanges == null ? java.util.List.of() : java.util.List.copyOf(plannedChanges);
        blockedOperations = blockedOperations == null ? java.util.List.of() : java.util.List.copyOf(blockedOperations);
        conflicts = conflicts == null ? java.util.List.of() : java.util.List.copyOf(conflicts);
        warnings = warnings == null ? java.util.List.of() : java.util.List.copyOf(warnings);
    }

    public record DryRunChange(String operationId, OperationType type, String targetPath, Ownership ownership) {
    }

    public record DryRunBlocked(String operationId, String targetPath, String reason) {
    }

    public record DryRunConflict(String operationId, String targetPath, String expectedHash, String actualHash) {
    }
}
