package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.DryRunResult;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.Ownership;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dry Run（V0.7 §20：支持 Dry Run/Preview）。
 *
 * GenerationPlan + Current Project Snapshot → DryRunResult。
 * 不得修改任何真实文件；必须输出 planned changes / conflicts / blocked operations / warnings。
 * Dry Run 与真实 Apply 使用同一套 policy evaluation（PathSafety + Ownership + OverwritePolicy）。
 */
public final class DryRunner {

    private final WorkspacePort port;

    public DryRunner() {
        this(new WorkspacePort.Default());
    }

    public DryRunner(WorkspacePort port) {
        this.port = port;
    }

    public DryRunResult dryRun(GenerationPlan plan, Path root, Map<String, String> manifest) {
        List<DryRunResult.DryRunChange> changes = new ArrayList<>();
        List<DryRunResult.DryRunBlocked> blocked = new ArrayList<>();
        List<DryRunResult.DryRunConflict> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Path rootReal;
        try {
            rootReal = root.toRealPath();
        } catch (IOException e) {
            return new DryRunResult(plan.planId(), false, changes, blocked, conflicts,
                    List.of("workspace root cannot be resolved: " + e.getMessage()));
        }

        for (GenerationOperation op : plan.operations()) {
            try {
                PathSafety.resolveInsideRoot(port, rootReal, op.targetPath());
            } catch (PathSafety.PathSafetyException | IOException e) {
                blocked.add(new DryRunResult.DryRunBlocked(op.operationId(), op.targetPath(),
                        "path safety: " + e.getMessage()));
                continue;
            }
            Path target = rootReal.resolve(op.targetPath()).normalize();
            boolean exists = port.exists(target);
            boolean isDir = port.isDirectory(target);
            Ownership ownership = OwnershipPolicy.resolve(manifest, op.targetPath());
            if (ownership == null && exists && !isDir) {
                // 已存在文件但 Manifest 无归属证明 → unknown ownership fails safe（不得默认覆盖）
                // 已存在目录（非文件）不在此列：由 Apply 阶段按文件系统错误处理并触发 rollback
                blocked.add(new DryRunResult.DryRunBlocked(op.operationId(), op.targetPath(),
                        "unknown ownership on existing file (fails safe)"));
                continue;
            }
            if (ownership == null) {
                ownership = op.ownership();
            }
            if (!OverwritePolicyEvaluator.allows(ownership, op.type(), exists)) {
                blocked.add(new DryRunResult.DryRunBlocked(op.operationId(), op.targetPath(),
                        "ownership/overwrite policy: " + ownership + " on " + op.type()));
                continue;
            }
            if (op.expectedBeforeHash() != null && exists) {
                String current;
                try {
                    current = ContentHasher.sha256(port.readBytes(target));
                } catch (IOException e) {
                    current = null;
                }
                if (current != null && !current.equals(op.expectedBeforeHash())) {
                    conflicts.add(new DryRunResult.DryRunConflict(op.operationId(), op.targetPath(),
                            op.expectedBeforeHash(), current));
                    continue;
                }
            }
            changes.add(new DryRunResult.DryRunChange(op.operationId(), op.type(), op.targetPath(), ownership));
        }
        if (plan.risks() != null && !plan.risks().isEmpty()) {
            warnings.addAll(plan.risks());
        }
        boolean executable = blocked.isEmpty() && conflicts.isEmpty();
        return new DryRunResult(plan.planId(), executable, changes, blocked, conflicts, warnings);
    }
}
