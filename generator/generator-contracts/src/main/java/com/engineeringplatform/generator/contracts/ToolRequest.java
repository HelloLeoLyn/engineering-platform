package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Agent-neutral ToolRequest（EP-WORK-009 §五）。
 * Agent 可以提出操作请求，但 Agent 本身不能决定自己拥有什么权限。
 * 所有 ToolRequest 必须经过 ToolPolicyEvaluator（ALLOW / DENY / REQUIRE_APPROVAL）。
 */
public record ToolRequest(
        String requestId,
        String executionId,
        ToolCapability capability,
        String toolType,
        String operation,
        java.util.Map<String, Object> arguments,
        String target,
        String reason) {

    public ToolRequest {
        arguments = arguments == null ? java.util.Map.of() : java.util.Map.copyOf(arguments);
    }

    public enum ToolType {
        FILESYSTEM, GIT, SHELL, BROWSER
    }

    /**
     * Policy 决策（EP-WORK-009 §七/§十二）。
     */
    public enum PolicyDecision {
        ALLOW,
        DENY,
        REQUIRE_APPROVAL
    }

    /**
     * Policy 评估结果：决策 + 原因（供 Execution Log / Approval 使用）。
     */
    public record PolicyResult(PolicyDecision decision, String reason, String risk) {
    }

    /**
     * Approval（EP-WORK-009 §十二）。Agent 不得自己批准。
     */
    public record Approval(
            String approvalId,
            String executionId,
            String toolRequestId,
            String risk,
            String reason,
            ApprovalStatus status) {

        public enum ApprovalStatus {
            PENDING, APPROVED, REJECTED, EXPIRED
        }
    }
}
