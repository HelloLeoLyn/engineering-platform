package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ChangeManifest;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.OperationType;
import com.engineeringplatform.generator.contracts.Ownership;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rollback Manager（V0.7 §21 + EP-WORK-005/006 指令 §十三）。
 *
 * - MODIFY/DELETE：Apply 前保存恢复所需状态（backup 到 .generator/transactions/{txnId}/backup/）。
 * - CREATE：Rollback 时删除本 transaction 创建的文件。
 * - 只影响当前 transaction 的操作。
 * - 禁止 git reset --hard / git clean -fd / git checkout . 等 Git 破坏性命令。
 */
public final class RollbackManager {

    private final WorkspacePort port;

    public RollbackManager() {
        this(new WorkspacePort.Default());
    }

    public RollbackManager(WorkspacePort port) {
        this.port = port;
    }

    public Path backupDir(Path root, String transactionId) {
        return port.resolve(root, ".generator/transactions/" + transactionId + "/backup");
    }

    /** Apply 前备份（MODIFY/DELETE）。返回备份文件路径（未存在则 null）。 */
    public Path backup(Path root, String transactionId, GenerationOperation op, Path target) throws IOException {
        if (!port.exists(target)) {
            return null;
        }
        Path backupDir = backupDir(root, transactionId);
        port.createDirectories(backupDir);
        Path backupFile = port.resolve(backupDir, op.operationId());
        port.writeBytes(backupFile, port.readBytes(target));
        return backupFile;
    }

    /** 回滚单个操作。 */
    public void rollback(Path root, String transactionId, GenerationOperation op, Path target) throws IOException {
        switch (op.type()) {
            case CREATE_DIRECTORY -> {
                // 仅当目录为空时删除（避免删除用户后续放入的文件）
                // V1：删除目录由 Executor 跟踪 createdDirs；此处保守：目录非空则保留
            }
            case CREATE_FILE -> {
                // 删除本 transaction 创建的文件
                port.delete(target);
            }
            case UPDATE_MANAGED_FILE, DELETE -> {
                // 恢复备份
                Path backupFile = port.resolve(backupDir(root, transactionId), op.operationId());
                if (port.exists(backupFile)) {
                    port.writeBytes(target, port.readBytes(backupFile));
                } else {
                    // 无备份说明原文件不存在（例如 CREATE 后失败被当 DELETE 处理）——按不存在处理
                    port.delete(target);
                }
            }
            default -> {
                // REGISTER_*/ADD_* 不执行，无需回滚
            }
        }
    }
}
