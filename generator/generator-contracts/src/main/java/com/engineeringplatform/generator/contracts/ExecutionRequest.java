package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * Agent Execution Request（EP-WORK-009 §三/§四/§十三/§十四）。
 *
 * 必须来自平台控制面。Agent 不得自行扩大：scope / tool permission / timeout / retry count。
 * SUCCEEDED 只表示 Agent execution 完成，不等于 WorkItem ACCEPTED / Verification ACCEPT。
 */
public record ExecutionRequest(
        String executionId,
        String workItemId,
        String implementationTaskId,
        Scope scope,
        List<ToolCapability> allowedCapabilities,
        List<String> allowedOperations,
        String policy,
        List<String> inputs,
        List<String> expectedArtifacts,
        List<String> evidenceRequirements,
        long timeoutSeconds,
        int maxAttempts,
        ExecutionState status,
        int attempts,
        List<String> errors,
        String summary) {

    public ExecutionRequest {
        allowedCapabilities = allowedCapabilities == null ? List.of() : List.copyOf(allowedCapabilities);
        allowedOperations = allowedOperations == null ? List.of() : List.copyOf(allowedOperations);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        expectedArtifacts = expectedArtifacts == null ? List.of() : List.copyOf(expectedArtifacts);
        evidenceRequirements = evidenceRequirements == null ? List.of() : List.copyOf(evidenceRequirements);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public enum ExecutionState {
        PLANNED, RUNNING, SUCCEEDED, FAILED, BLOCKED, TIMED_OUT, CANCELLED
    }

    /**
     * 执行范围（复用 WorkItem Scope 语义；结构化 paths/modules）。
     */
    public record Scope(
            List<String> allowedPaths,
            List<String> forbiddenPaths,
            List<String> allowedModules,
            List<String> forbiddenModules) {

        public Scope {
            allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
            forbiddenPaths = forbiddenPaths == null ? List.of() : List.copyOf(forbiddenPaths);
            allowedModules = allowedModules == null ? List.of() : List.copyOf(allowedModules);
            forbiddenModules = forbiddenModules == null ? List.of() : List.copyOf(forbiddenModules);
        }

        public static Scope any() {
            return new Scope(List.of("**"), List.of(), List.of(), List.of());
        }
    }

    /**
     * Execution Log 事件（EP-WORK-009 §十五）。日志属于 execution artifact，允许 timestamp。
     */
    public record ExecutionLogEvent(
            String eventId,
            String executionId,
            long timestampMs,
            EventType type,
            String reference,
            Map<String, Object> metadata) {

        public ExecutionLogEvent {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public enum EventType {
            EXECUTION_STARTED,
            TOOL_REQUESTED,
            TOOL_ALLOWED,
            TOOL_DENIED,
            APPROVAL_REQUIRED,
            TOOL_COMPLETED,
            TOOL_FAILED,
            EXECUTION_COMPLETED,
            EXECUTION_FAILED,
            EXECUTION_TIMED_OUT
        }
    }
}
