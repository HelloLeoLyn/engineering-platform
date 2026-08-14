package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.OperationType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generation Planner（V0.7 §20：Generator 不直接写 Workspace，必须先转换为版本化 GenerationPlan）。
 *
 * 输入：EffectiveProjectModel + 请求的 Operation 列表。
 * 输出：确定性 GenerationPlan（planId 绑定 generatorVersion + resolutionId + inputHash）。
 * 创建 Plan 时不得修改任何项目文件（PLAN BEFORE WRITE）。
 */
public final class GenerationPlanner {

    private final WorkspacePort port;

    public GenerationPlanner() {
        this(new WorkspacePort.Default());
    }

    public GenerationPlanner(WorkspacePort port) {
        this.port = port;
    }

    /**
     * 从 EPM + 请求操作生成 GenerationPlan。
     * 相同 EPM + 相同操作 + 相同 generatorVersion → 相同 planId（deterministic）。
     */
    public GenerationPlan plan(EffectiveProjectModel epm, String generatorVersion,
                               String type, List<GenerationOperation> requestedOperations) {
        String resolutionId = epm.resolution().resolutionId();
        String inputHash = epm.resolution().inputHash();
        List<GenerationOperation> operations = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        for (GenerationOperation op : requestedOperations) {
            try {
                PathSafety.validateRelative(op.targetPath(), false);
            } catch (PathSafety.PathSafetyException e) {
                risks.add("Operation " + op.operationId() + " rejected: " + e.getMessage());
                continue;
            }
            operations.add(op);
            if (op.type() == OperationType.DELETE) {
                risks.add("DELETE operation is high risk: " + op.targetPath());
            }
        }
        String canonicalOps = canonicalOperations(operations);
        String planId = "gp-" + ContentHasher.sha256(
                generatorVersion + ":" + resolutionId + ":" + inputHash + ":" + canonicalOps).substring(0, 12);

        Map<String, Integer> summary = new LinkedHashMap<>();
        int create = 0, modify = 0, delete = 0;
        Map<String, Integer> ownerships = new LinkedHashMap<>();
        for (GenerationOperation op : operations) {
            ownerships.merge(op.ownership().name(), 1, Integer::sum);
            switch (op.type()) {
                case CREATE_DIRECTORY, CREATE_FILE -> create++;
                case UPDATE_MANAGED_FILE -> modify++;
                case DELETE -> delete++;
                default -> { /* registry/add ops counted as modify */ modify++; }
            }
        }
        summary.put("create", create);
        summary.put("modify", modify);
        summary.put("delete", delete);
        summary.put("skip", 0);
        ownerships.forEach((k, v) -> summary.put("ownerships." + k, v));

        return new GenerationPlan(planId, type, generatorVersion, resolutionId, inputHash,
                epm.identity() == null ? null : epm.identity().getOrDefault("id", "").toString(),
                operations, risks, List.of(), summary);
    }

    private static String canonicalOperations(List<GenerationOperation> ops) {
        StringBuilder sb = new StringBuilder();
        for (GenerationOperation op : ops) {
            sb.append(op.type()).append(':').append(op.targetPath()).append(':')
                    .append(op.ownership()).append(':').append(op.overwritePolicy()).append(';');
        }
        return sb.toString();
    }
}
