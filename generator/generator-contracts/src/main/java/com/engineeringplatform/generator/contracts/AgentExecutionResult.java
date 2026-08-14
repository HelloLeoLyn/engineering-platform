package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * AgentExecutionResult（EP-WORK-009 §十七）。
 *
 * 明确：SUCCEEDED ≠ Verification ACCEPT ≠ WorkItem ACCEPTED。
 * Agent 不得修改 VerificationReport.decision / WorkItem ACCEPTED/DONE。
 */
public record AgentExecutionResult(
        String executionId,
        ExecutionRequest.ExecutionState status,
        int attempts,
        List<ToolResult> toolResults,
        List<String> artifacts,
        List<Evidence> evidence,
        List<String> errors,
        String summary) {

    public AgentExecutionResult {
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
