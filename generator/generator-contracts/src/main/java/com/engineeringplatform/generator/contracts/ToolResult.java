package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * Agent-neutral ToolResult（EP-WORK-009 §五）。
 * 不要把任意大 stdout/stderr 无条件塞进 Contract —— 使用 outputReference / evidence 引用。
 */
public record ToolResult(
        String requestId,
        ToolResultStatus status,
        String outputReference,
        List<String> evidence,
        String error,
        long durationMs,
        Map<String, Object> metadata) {

    public ToolResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum ToolResultStatus {
        SUCCESS, FAILED, BLOCKED
    }
}
