package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * GenerationPlan（V0.7 §20 Generation Plan V1 [DECIDED]）。
 * 声明式计划：planId/type/generatorVersion/source resolutionId+hash/scope/operations/
 * dependencies/validation/risks/summary。
 * 创建 Plan 时不得修改项目文件（PLAN BEFORE WRITE）。
 */
public record GenerationPlan(
        String planId,
        String type,
        String generatorVersion,
        String resolutionId,
        String inputHash,
        String scope,
        List<GenerationOperation> operations,
        List<String> risks,
        List<Map<String, String>> warnings,
        Map<String, Integer> summary) {

    public GenerationPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
        risks = risks == null ? List.of() : List.copyOf(risks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        summary = summary == null ? Map.of() : Map.copyOf(summary);
    }

    public int createCount() {
        return summary.getOrDefault("create", 0);
    }

    public int modifyCount() {
        return summary.getOrDefault("modify", 0);
    }

    public int deleteCount() {
        return summary.getOrDefault("delete", 0);
    }
}
