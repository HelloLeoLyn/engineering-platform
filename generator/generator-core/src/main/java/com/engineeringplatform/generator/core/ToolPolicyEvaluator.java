package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ExecutionRequest;
import com.engineeringplatform.generator.contracts.ToolCapability;
import com.engineeringplatform.generator.contracts.ToolRequest;

import java.nio.file.Path;
import java.util.List;

/**
 * Tool Policy Evaluator（EP-WORK-009 §七/§八）。
 *
 * 所有 ToolRequest 必须经过本 Evaluator，结果：ALLOW / DENY / REQUIRE_APPROVAL。
 * Agent 不得绕过。
 *
 * Policy 评估考虑：WorkItem scope / ExecutionRequest scope / tool capability /
 * operation / target / risk classification / protected paths / forbidden operations。
 *
 * Least Privilege（§八）：默认 DENY；只有 ExecutionRequest 明确授权的
 * capability + operation + scope 才能 ALLOW。不得"Agent 需要所以自动开放"。
 *
 * 纯计算；不执行任何真实工具。
 */
public final class ToolPolicyEvaluator {

    private ToolPolicyEvaluator() {
    }

    /**
     * 评估 ToolRequest。
     *
     * @param request ExecutionRequest（控制面授权事实）
     * @param tool    Agent 提出的 ToolRequest
     * @param root    workspace root（filesystem guard 用）
     */
    public static ToolRequest.PolicyResult evaluate(ExecutionRequest request, ToolRequest tool, Path root) {
        // 0. 默认 DENY（Least Privilege）
        // 1. capability 必须被明确授权
        List<ToolCapability> allowed = request.allowedCapabilities() == null
                ? List.of() : request.allowedCapabilities();
        if (!allowed.contains(tool.capability())) {
            return new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY,
                    "capability not granted: " + tool.capability(), "LOW");
        }
        // 2. operation 若 request 显式列出 allowedOperations，必须匹配
        List<String> allowedOps = request.allowedOperations() == null
                ? List.of() : request.allowedOperations();
        if (!allowedOps.isEmpty() && !allowedOps.contains(tool.operation())) {
            return new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY,
                    "operation not granted: " + tool.operation(), "LOW");
        }

        ExecutionRequest.Scope scope = request.scope() == null
                ? ExecutionRequest.Scope.any() : request.scope();

        // 3. 按 capability 分派 Guard
        return switch (tool.capability()) {
            case FILESYSTEM_READ -> {
                String err = FilesystemGuard.checkRead(scope, tool, root);
                yield err == null
                        ? new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.ALLOW, null, "LOW")
                        : new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY, err, "MEDIUM");
            }
            case FILESYSTEM_WRITE -> {
                String err = FilesystemGuard.checkWrite(scope, tool, root);
                yield err == null
                        ? new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.ALLOW, null, "MEDIUM")
                        : new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY, err, "HIGH");
            }
            case GIT_READ -> {
                String err = GitGuard.check(tool);
                yield err == null
                        ? new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.ALLOW, null, "LOW")
                        : new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY, err, "MEDIUM");
            }
            case GIT_WRITE -> {
                String err = GitGuard.check(tool);
                yield err == null
                        ? new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.ALLOW, null, "MEDIUM")
                        : new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY, err, "HIGH");
            }
            case SHELL_READ -> {
                String err = ShellGuard.check(scope, tool);
                yield err == null
                        ? new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.ALLOW, null, "HIGH")
                        : new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY, err, "HIGH");
            }
            case SHELL_WRITE -> {
                // Shell Write 是最高风险：即使命令安全也要求人工批准（V1 保守）
                String err = ShellGuard.check(scope, tool);
                yield err != null
                        ? new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY, err, "CRITICAL")
                        : new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.REQUIRE_APPROVAL,
                                "shell write requires human approval", "CRITICAL");
            }
            case BROWSER_READ, BROWSER_WRITE -> {
                // 定义 capability ≠ 本轮实现 Browser；V1 一律拒绝
                yield new ToolRequest.PolicyResult(ToolRequest.PolicyDecision.DENY,
                        "browser capability not implemented in V1", "HIGH");
            }
        };
    }
}
