package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ToolRequest;
import com.engineeringplatform.generator.contracts.ToolResult;

import java.util.Map;
import java.util.function.Function;

/**
 * Fake Tool Executor（EP-WORK-009 §二十一）。
 *
 * 测试专用：绝不执行真实 sudo / 真实 destructive Git / 真实系统命令 / 真实浏览器操作。
 * 只返回脚本化 ToolResult。
 */
public final class FakeToolExecutor implements Function<ToolRequest, ToolResult> {

    @Override
    public ToolResult apply(ToolRequest request) {
        // 所有 fake 执行都成功；路径/安全校验已由 ToolPolicyEvaluator + Guards 完成
        return new ToolResult(request.requestId(), ToolResult.ToolResultStatus.SUCCESS,
                "fake-output-ref-" + request.requestId(),
                java.util.List.of("ev-fake-" + request.requestId()),
                null, 1, Map.of("fake", true));
    }
}
