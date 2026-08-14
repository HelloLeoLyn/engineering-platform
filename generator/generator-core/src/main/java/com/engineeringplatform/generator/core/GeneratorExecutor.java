package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ChangeManifest;
import com.engineeringplatform.generator.contracts.DryRunResult;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.GenerationTransaction;
import com.engineeringplatform.generator.contracts.OperationType;
import com.engineeringplatform.generator.contracts.Ownership;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generator Executor（V0.7 §21 Generation Executor / Rollback Standard V1 [DECIDED]）。
 *
 * 流程：GenerationPlan → Validate → Dry Run → Stage → Pre-Apply Check → Apply → Verify → Commit Transaction。
 * 中途失败 → rollback。不得操作一个文件失败后继续假装整体 SUCCESS。
 *
 * 概念 API：dryRun(...) / execute(...) / rollback(...)（plan 由 GenerationPlanner 提供）。
 * 所有写文件测试必须使用临时目录，禁止对真实 Engineering Platform repository 执行测试写入。
 */
public final class GeneratorExecutor {

    private final WorkspacePort port;
    private final DryRunner dryRunner;
    private final Stager stager;
    private final TransactionManager txManager;
    private final RollbackManager rollbackManager;

    public GeneratorExecutor() {
        this(new WorkspacePort.Default());
    }

    public GeneratorExecutor(WorkspacePort port) {
        this.port = port;
        this.dryRunner = new DryRunner(port);
        this.stager = new Stager(port);
        this.txManager = new TransactionManager(port);
        this.rollbackManager = new RollbackManager(port);
    }

    /** Dry Run：不修改任何真实文件，与 Apply 使用同一套 policy evaluation。 */
    public DryRunResult dryRun(GenerationPlan plan, Path root) {
        Map<String, String> manifest = loadManifest(root);
        return dryRunner.dryRun(plan, root, manifest);
    }

    /**
     * 执行 Plan（Atomic Apply + 失败回滚）。
     */
    public ExecutionResult execute(GenerationPlan plan, Path root) {
        String txnId = "tx-" + ContentHasher.sha256(plan.planId() + ":" + System.nanoTime()).substring(0, 12);
        List<String> messages = new ArrayList<>();
        List<ChangeManifest.ChangeEntry> entries = new ArrayList<>();
        Map<String, String> manifest = loadManifest(root);

        try {
            // 1. Validate（全部路径 Path Safety）
            Path rootReal = root.toRealPath();
            for (GenerationOperation op : plan.operations()) {
                try {
                    PathSafety.resolveInsideRoot(port, rootReal, op.targetPath());
                } catch (PathSafety.PathSafetyException e) {
                    return failed(txnId, plan, entries, "path safety rejected: " + op.targetPath());
                }
            }

            // 2. Dry Run（同一套 policy）
            DryRunResult dry = dryRunner.dryRun(plan, root, manifest);
            if (!dry.executable()) {
                List<String> reasons = new ArrayList<>();
                dry.blockedOperations().forEach(b -> reasons.add("blocked: " + b.reason()));
                dry.conflicts().forEach(c -> reasons.add("conflict: " + c.targetPath()));
                return failed(txnId, plan, entries, String.join("; ", reasons));
            }

            // 3. Transaction begin（STAGED）
            txManager.begin(root, txnId, plan.planId(),
                    plan.operations().stream().map(GenerationOperation::operationId).toList());

            // 4. Stage（所有待写内容先进入 staging）
            stager.stage(root, txnId, plan);

            // 5. Pre-Apply Check（expectedBeforeHash 冲突检测）+ Ownership 复核
            for (GenerationOperation op : plan.operations()) {
                Path target = rootReal.resolve(op.targetPath()).normalize();
                boolean exists = port.exists(target);
                boolean isDir = port.isDirectory(target);
                Ownership ownership = OwnershipPolicy.resolve(manifest, op.targetPath());
                if (ownership == null && exists && !isDir) {
                    // 已存在文件但 Manifest 无归属证明 → unknown ownership fails safe
                    // 已存在目录（非文件）不在此列：由 Apply 阶段按文件系统错误处理并触发 rollback
                    return rollbackAndFail(txnId, plan, entries, rootReal,
                            "unknown ownership on existing file (fails safe): " + op.targetPath());
                }
                if (ownership == null) {
                    ownership = op.ownership();
                }
                if (!OverwritePolicyEvaluator.allows(ownership, op.type(), exists)) {
                    return rollbackAndFail(txnId, plan, entries, rootReal,
                            "ownership/overwrite policy blocked: " + op.targetPath());
                }
                if (op.expectedBeforeHash() != null && exists) {
                    String current = ContentHasher.sha256(port.readBytes(target));
                    if (!current.equals(op.expectedBeforeHash())) {
                        return rollbackAndFail(txnId, plan, entries, rootReal,
                                "precondition conflict (expectedBeforeHash): " + op.targetPath());
                    }
                }
            }

            // 6. Apply（每操作前 backup，写后 verify）
            for (GenerationOperation op : plan.operations()) {
                Path target = rootReal.resolve(op.targetPath()).normalize();
                boolean existed = port.exists(target);
                try {
                    switch (op.type()) {
                        case CREATE_DIRECTORY -> {
                            port.createDirectories(target);
                            entries.add(entry(op, existed, target, true, ChangeManifest.ChangeStatus.APPLIED));
                        }
                        case CREATE_FILE -> {
                            port.createDirectories(target.getParent());
                            String content = op.content() != null ? op.content()
                                    : stager.readStaged(root, txnId, op.operationId());
                            port.writeString(target, content);
                            entries.add(entry(op, existed, target, true, ChangeManifest.ChangeStatus.APPLIED));
                        }
                        case UPDATE_MANAGED_FILE -> {
                            rollbackManager.backup(root, txnId, op, target);
                            port.createDirectories(target.getParent());
                            String content = op.content() != null ? op.content()
                                    : stager.readStaged(root, txnId, op.operationId());
                            port.writeString(target, content);
                            entries.add(entry(op, existed, target, true, ChangeManifest.ChangeStatus.APPLIED));
                        }
                        case DELETE -> {
                            rollbackManager.backup(root, txnId, op, target);
                            port.delete(target);
                            entries.add(entry(op, existed, target, false, ChangeManifest.ChangeStatus.APPLIED));
                        }
                        default -> {
                            // REGISTER_*/ADD_*：V1 不执行，记录为 SKIPPED（不假装 SUCCESS）
                            entries.add(entry(op, existed, target, existed,
                                    ChangeManifest.ChangeStatus.SKIPPED));
                        }
                    }
                } catch (IOException e) {
                    messages.add("apply failed at " + op.targetPath() + ": " + e.getMessage());
                    // 7. Verify 失败 → rollback
                    try {
                        rollbackApplied(root, txnId, plan, entries, rootReal, messages);
                    } catch (IOException re) {
                        messages.add("rollback failed: " + re.getMessage());
                    }
                    txManager.updateState(root, txOf(txnId, plan),
                            GenerationTransaction.TransactionState.ROLLED_BACK,
                            String.join("; ", messages));
                    return new ExecutionResult(txnId, plan.planId(),
                            ExecutionResult.ExecutionStatus.ROLLED_BACK,
                            manifest(txnId, plan, entries, "ROLLED_BACK", messages), messages);
                }
            }

            // 8. Commit Transaction
            txManager.updateState(root, txOf(txnId, plan),
                    GenerationTransaction.TransactionState.COMMITTED, "SUCCESS");
            return new ExecutionResult(txnId, plan.planId(),
                    ExecutionResult.ExecutionStatus.SUCCESS,
                    manifest(txnId, plan, entries, "SUCCESS", messages), messages);

        } catch (IOException e) {
            messages.add("executor io error: " + e.getMessage());
            try {
                txManager.updateState(root, txOf(txnId, plan),
                        GenerationTransaction.TransactionState.FAILED, String.join("; ", messages));
            } catch (IOException ignored) {
            }
            return failed(txnId, plan, entries, String.join("; ", messages));
        }
    }

    /** 回滚并返回 FAILED/ROLLED_BACK 结果（Pre-Apply 阶段失败——尚未写任何文件）。 */
    private ExecutionResult rollbackAndFail(String txnId, GenerationPlan plan,
                                            List<ChangeManifest.ChangeEntry> entries,
                                            Path rootReal, String reason) throws IOException {
        List<String> messages = new ArrayList<>(List.of(reason));
        txManager.updateState(rootReal, txOf(txnId, plan),
                GenerationTransaction.TransactionState.FAILED, reason);
        return failed(txnId, plan, entries, reason);
    }

    /** 回滚已 apply 的 entries（Create 删除 / Modify+Delete 恢复备份）。 */
    private void rollbackApplied(Path root, String txnId, GenerationPlan plan,
                                 List<ChangeManifest.ChangeEntry> entries,
                                 Path rootReal, List<String> messages) throws IOException {
        for (int i = 0; i < entries.size(); i++) {
            ChangeManifest.ChangeEntry e = entries.get(i);
            if (e.status() != ChangeManifest.ChangeStatus.APPLIED) {
                continue;
            }
            GenerationOperation op = plan.operations().stream()
                    .filter(o -> o.targetPath().equals(e.targetPath())).findFirst().orElse(null);
            if (op == null) {
                continue;
            }
            Path target = rootReal.resolve(e.targetPath()).normalize();
            rollbackManager.rollback(root, txnId, op, target);
            entries.set(i, new ChangeManifest.ChangeEntry(e.targetPath(), e.operationType(), e.ownership(),
                    ChangeManifest.ChangeStatus.ROLLED_BACK, e.before(), e.after(), e.reason()));
        }
        messages.add("rolled back " + entries.size() + " applied entries");
    }

    private ChangeManifest.ChangeEntry entry(GenerationOperation op, boolean existed,
                                             Path target, boolean afterExists,
                                             ChangeManifest.ChangeStatus status) {
        String beforeHash = existed ? hashOf(target) : null;
        String afterHash = afterExists ? hashOf(target) : null;
        long beforeSize = existed ? sizeOf(target) : 0;
        long afterSize = afterExists ? sizeOf(target) : 0;
        return new ChangeManifest.ChangeEntry(op.targetPath(), op.type(), op.ownership(), status,
                new ChangeManifest.FileState(existed, beforeHash, beforeSize, null),
                new ChangeManifest.FileState(afterExists, afterHash, afterSize, null),
                op.reason());
    }

    private String hashOf(Path p) {
        try {
            return ContentHasher.sha256(port.readBytes(p));
        } catch (IOException e) {
            return null;
        }
    }

    private long sizeOf(Path p) {
        try {
            return port.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private GenerationTransaction txOf(String txnId, GenerationPlan plan) {
        return new GenerationTransaction(txnId, plan.planId(),
                GenerationTransaction.TransactionState.STAGED,
                plan.operations().stream().map(GenerationOperation::operationId).toList(),
                Map.of(), null);
    }

    private ExecutionResult failed(String txnId, GenerationPlan plan,
                                   List<ChangeManifest.ChangeEntry> entries, String reason) {
        return new ExecutionResult(txnId, plan.planId(),
                ExecutionResult.ExecutionStatus.FAILED,
                manifest(txnId, plan, entries, "FAILED", List.of(reason)),
                List.of(reason));
    }

    private ChangeManifest manifest(String txnId, GenerationPlan plan,
                                    List<ChangeManifest.ChangeEntry> entries,
                                    String status, List<String> messages) {
        int applied = (int) entries.stream().filter(e -> e.status() == ChangeManifest.ChangeStatus.APPLIED).count();
        int skipped = (int) entries.stream().filter(e -> e.status() == ChangeManifest.ChangeStatus.SKIPPED).count();
        int rolled = (int) entries.stream().filter(e -> e.status() == ChangeManifest.ChangeStatus.ROLLED_BACK).count();
        return new ChangeManifest(
                "ch-" + txnId.substring(3, 15),
                plan.planId(),
                txnId,
                entries,
                new ChangeManifest.ChangeResult(status, applied, rolled, skipped,
                        messages.isEmpty() ? null : String.join("; ", messages)),
                String.valueOf(System.currentTimeMillis()));
    }

    private Map<String, String> loadManifest(Path root) {
        try {
            return OwnershipPolicy.loadManifest(port, root);
        } catch (IOException e) {
            return Map.of();
        }
    }
}
