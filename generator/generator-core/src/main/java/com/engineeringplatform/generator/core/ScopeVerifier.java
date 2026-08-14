package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ChangeManifest;
import com.engineeringplatform.generator.contracts.WorkItem;

import java.util.ArrayList;
import java.util.List;

/**
 * ScopeVerifier（EP-WORK-007/008 指令 §十二）。
 *
 * 输入：WorkItem Scope + ChangeManifest / changed paths。
 * 输出：PASS / FAIL + violations。
 *
 * 必须验证：所有 changed path 都在 allowed scope 内，且不在 forbidden scope 内。
 * 纯计算，不碰文件系统（路径匹配基于相对路径字符串前缀/glob）。
 */
public final class ScopeVerifier {

    private ScopeVerifier() {
    }

    /**
     * 基于 ChangeManifest entries 验证 scope。
     */
    public static WorkItem.ScopeResult verify(WorkItem.ScopeContract scope, ChangeManifest manifest) {
        List<String> changedPaths = new ArrayList<>();
        if (manifest != null && manifest.entries() != null) {
            for (ChangeManifest.ChangeEntry e : manifest.entries()) {
                changedPaths.add(e.targetPath());
            }
        }
        return verifyPaths(scope, changedPaths);
    }

    /**
     * 基于 changed paths 列表验证 scope。
     */
    public static WorkItem.ScopeResult verifyPaths(WorkItem.ScopeContract scope, List<String> changedPaths) {
        List<String> violations = new ArrayList<>();
        boolean pass = true;

        List<String> allowed = scope.allowedPaths() == null || scope.allowedPaths().isEmpty()
                ? List.of("**") : scope.allowedPaths();
        List<String> forbidden = scope.forbiddenPaths() == null ? List.of() : scope.forbiddenPaths();
        List<String> forbiddenModules = scope.forbiddenModules() == null ? List.of() : scope.forbiddenModules();

        for (String path : changedPaths) {
            // forbidden path 优先级最高
            if (matchesAny(path, forbidden)) {
                violations.add("forbidden path touched: " + path);
                pass = false;
                continue;
            }
            for (String module : forbiddenModules) {
                if (path.startsWith(module + "/") || path.equals(module)) {
                    violations.add("forbidden module touched: " + path);
                    pass = false;
                    break;
                }
            }
            if (!matchesAny(path, allowed)) {
                violations.add("out of allowed scope: " + path);
                pass = false;
            }
        }
        return new WorkItem.ScopeResult(pass, violations);
    }

    private static boolean matchesAny(String path, List<String> patterns) {
        for (String p : patterns) {
            if (p.equals("**")) {
                return true;
            }
            if (p.endsWith("/")) {
                if (path.startsWith(p)) {
                    return true;
                }
            } else if (p.endsWith("/**")) {
                String prefix = p.substring(0, p.length() - 3);
                if (path.startsWith(prefix)) {
                    return true;
                }
            } else if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }
}
