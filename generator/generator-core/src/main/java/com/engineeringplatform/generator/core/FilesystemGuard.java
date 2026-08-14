package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ExecutionRequest;
import com.engineeringplatform.generator.contracts.ToolRequest;

import java.nio.file.Path;
import java.util.List;

/**
 * Filesystem Guard（EP-WORK-009 §九）。
 *
 * READ / WRITE 分开。WRITE 必须验证：
 *  - target 在允许 workspace/scope 内
 *  - 不在 forbidden path
 *  - 不 path traversal
 *  - 不 symlink escape
 *  - 不 protected path
 *
 * 复用 006 PathSafety 语义/组件（不复制第二套实现）。
 * 纯校验；FakeToolExecutor 负责实际（fake）执行。
 */
public final class FilesystemGuard {

    private FilesystemGuard() {
    }

    /**
     * 校验 Filesystem WRITE 请求的 target。
     *
     * @return null = 允许；非 null = 拒绝原因
     */
    public static String checkWrite(ExecutionRequest.Scope scope, ToolRequest request, Path root) {
        String target = request.target();
        if (target == null || target.isBlank()) {
            return "fs write target is blank";
        }
        // 1. 路径安全（../、绝对、traversal、symlink escape、protected）
        try {
            PathSafety.validateRelative(target, false);
            PathSafety.resolveInsideRoot(new WorkspacePort.Default(), root, target);
        } catch (PathSafety.PathSafetyException | java.io.IOException e) {
            return "fs path safety: " + e.getMessage();
        }
        // 2. scope 校验
        return checkScope(scope, target);
    }

    /**
     * 校验 Filesystem READ 请求的 target（只做 path safety + scope；不要求存在性）。
     */
    public static String checkRead(ExecutionRequest.Scope scope, ToolRequest request, Path root) {
        String target = request.target();
        if (target == null || target.isBlank()) {
            return "fs read target is blank";
        }
        try {
            PathSafety.validateRelative(target, false);
            PathSafety.resolveInsideRoot(new WorkspacePort.Default(), root, target);
        } catch (PathSafety.PathSafetyException | java.io.IOException e) {
            return "fs path safety: " + e.getMessage();
        }
        return checkScope(scope, target);
    }

    private static String checkScope(ExecutionRequest.Scope scope, String target) {
        List<String> forbidden = scope.forbiddenPaths() == null ? List.of() : scope.forbiddenPaths();
        for (String f : forbidden) {
            if (matches(f, target)) {
                return "forbidden path: " + target;
            }
        }
        List<String> allowed = scope.allowedPaths() == null || scope.allowedPaths().isEmpty()
                ? List.of("**") : scope.allowedPaths();
        for (String a : allowed) {
            if (matches(a, target)) {
                return null;
            }
        }
        return "out of allowed scope: " + target;
    }

    private static boolean matches(String pattern, String path) {
        if (pattern.equals("**")) {
            return true;
        }
        if (pattern.endsWith("/")) {
            return path.startsWith(pattern);
        }
        if (pattern.endsWith("/**")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 3));
        }
        return path.equals(pattern) || path.startsWith(pattern + "/");
    }
}
