package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ToolRequest;

import java.util.List;

/**
 * Git Guard（EP-WORK-009 §十）。
 *
 * Git Read：status / diff / log / show（安全）。
 * Git Write：add / commit（受控）。
 * V1 默认禁止高风险操作：reset --hard / clean -fd / push --force / force push /
 * destructive rebase flows / checkout|restore 大范围覆盖工作树。
 *
 * 结构化 GitOperation（不靠字符串 contains）。
 */
public final class GitGuard {

    private GitGuard() {
    }

    /** 安全 Git Read 操作。 */
    public static final List<String> SAFE_READ_OPS = List.of("git.status", "git.diff", "git.log", "git.show");

    /** 受控 Git Write 操作。 */
    public static final List<String> CONTROLLED_WRITE_OPS = List.of("git.add", "git.commit");

    /** 高风险禁止操作（结构化匹配，含参数级检查）。 */
    public static final List<String> FORBIDDEN_OPS = List.of(
            "git.reset", "git.clean", "git.push", "git.rebase", "git.checkout", "git.restore");



    /**
     * 校验 Git 操作。
     *
     * @return null = 允许；非 null = 拒绝原因
     */
    public static String check(ToolRequest request) {
        String op = request.operation();
        if (op == null) {
            return "git operation is null";
        }
        if (SAFE_READ_OPS.contains(op)) {
            return null;
        }
        if (CONTROLLED_WRITE_OPS.contains(op)) {
            // add/commit 还需参数级检查（例如 git.add 不允许 --force 等）
            return checkControlledWrite(op, request);
        }
        if (FORBIDDEN_OPS.contains(op)) {
            return forbiddenDetail(op, request);
        }
        return "unknown git operation: " + op;
    }

    private static String checkControlledWrite(String op, ToolRequest request) {
        // git.add / git.commit 默认允许（无高风险 flag 时）
        if (request.arguments() != null) {
            Object force = request.arguments().get("force");
            if (Boolean.TRUE.equals(force)) {
                return op + " with force is forbidden";
            }
        }
        return null;
    }

    private static String forbiddenDetail(String op, ToolRequest request) {
        String target = request.target() == null ? "" : request.target();
        switch (op) {
            case "git.reset":
                if (target.contains("--hard")) {
                    return "git reset --hard is forbidden";
                }
                return "git reset is high risk (only --hard case explicitly forbidden in V1; reset denied)";
            case "git.clean":
                if (target.contains("-fd")) {
                    return "git clean -fd is forbidden";
                }
                return "git clean is forbidden";
            case "git.push":
                if (target.contains("--force") || target.contains("+")) {
                    return "force push is forbidden";
                }
                return "git push is forbidden in V1 (no remote mutation)";
            case "git.rebase":
                return "destructive rebase flows are forbidden";
            case "git.checkout":
            case "git.restore":
                if (".".equals(target) || target.isBlank()) {
                    return op + " whole-workspace restore is forbidden";
                }
                return op + " broad coverage restore is forbidden in V1";
            default:
                return op + " is forbidden";
        }
    }
}
