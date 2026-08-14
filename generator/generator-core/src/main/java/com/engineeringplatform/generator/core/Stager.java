package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.OperationType;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Stager（V0.7 §21：CREATE_PROJECT 默认 Staging Generation；EP-WORK-005/006 指令 §十）。
 *
 * 所有待写内容先进入 .generator/staging/{transactionId}/；
 * 禁止 Planner 直接写目标文件；禁止 Executor 绕过 staging 直接生成最终文件。
 * staging 绑定 plan / transaction identity。
 */
public final class Stager {

    private final WorkspacePort port;

    public Stager() {
        this(new WorkspacePort.Default());
    }

    public Stager(WorkspacePort port) {
        this.port = port;
    }

    /** Staging 根：root/.generator/staging/{transactionId}/ */
    public Path stagingRoot(Path root, String transactionId) {
        return port.resolve(root, ".generator/staging/" + transactionId);
    }

    /**
     * 将 plan 中所有带内容的 Operation 写入 staging。
     *
     * @return staging 目录
     */
    public Path stage(Path root, String transactionId, GenerationPlan plan) throws IOException {
        Path stagingRoot = stagingRoot(root, transactionId);
        port.createDirectories(stagingRoot);
        for (GenerationOperation op : plan.operations()) {
            if (op.content() == null) {
                continue;
            }
            // 只 stage 文件型操作
            if (op.type() == OperationType.CREATE_FILE || op.type() == OperationType.UPDATE_MANAGED_FILE) {
                Path staged = port.resolve(stagingRoot, op.operationId());
                port.writeString(staged, op.content());
            }
        }
        return stagingRoot;
    }

    /** 读取 staging 中某 operation 的内容。 */
    public String readStaged(Path root, String transactionId, String operationId) throws IOException {
        return port.readString(port.resolve(stagingRoot(root, transactionId), operationId));
    }
}
