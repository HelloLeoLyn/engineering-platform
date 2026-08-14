package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ExecutionRequest;
import com.engineeringplatform.generator.contracts.ToolRequest;

import java.util.List;

/**
 * Shell Guard（EP-WORK-009 §十一）。
 *
 * Shell 是最高风险能力之一。V1 不实现 任意 shell string → Runtime.exec()。
 * 建立结构化：command / arguments / workingDirectory。
 * Policy 检查：command class / working directory / scope / risk。
 *
 * 默认禁止：sudo / su / rm -rf / mkfs / shutdown / reboot / poweroff /
 * kill broad / curl|shell / wget|shell / chmod|chown broad recursive /
 * package manager install / service|systemctl mutation / docker destructive。
 *
 * 目标不是完美 Shell Sandbox，而是 V1 Policy Guard。
 */
public final class ShellGuard {

    private ShellGuard() {
    }

    /** 命令类别 → 允许的示例（V1 白名单；结构化解耦）。 */
    public static final List<String> ALLOWED_COMMANDS = List.of(
            "ls", "cat", "find", "grep", "echo", "mkdir", "touch", "pwd", "wc", "head", "tail");

    /** 高危命令（结构化匹配，含参数级检查）。 */
    public static final List<String> FORBIDDEN_COMMANDS = List.of(
            "sudo", "su", "mkfs", "shutdown", "reboot", "poweroff", "systemctl", "service",
            "apt", "apt-get", "yum", "dnf", "pip", "pip3", "npm", "docker", "chmod", "chown");

    /** 需要参数级检查的命令（rm / kill / curl / wget）。 */
    public static final List<String> PARAM_CHECK_COMMANDS = List.of("rm", "kill", "curl", "wget");



    /**
     * 校验 Shell 请求。
     *
     * @return null = 允许；非 null = 拒绝原因
     */
    public static String check(ExecutionRequest.Scope scope, ToolRequest request) {
        String command = request.operation(); // 结构化 command（不是任意字符串）
        if (command == null || command.isBlank()) {
            return "shell command is null/blank";
        }
        // 1. 白名单命令
        if (ALLOWED_COMMANDS.contains(command)) {
            return checkWorkingDirectory(scope, request);
        }
        // 2. 高危命令（任何形式都拒绝）
        if (FORBIDDEN_COMMANDS.contains(command)) {
            return command + " is forbidden";
        }
        // 3. 参数级检查
        if (PARAM_CHECK_COMMANDS.contains(command)) {
            return checkParamCommand(command, request);
        }
        // 4. 默认拒绝（Least Privilege）
        return "shell command not in V1 allowlist: " + command;
    }

    private static String checkParamCommand(String command, ToolRequest request) {
        String target = request.target() == null ? "" : request.target();
        switch (command) {
            case "rm":
                if (target.contains("-rf") || target.contains("-r")) {
                    return "rm -rf / recursive rm is forbidden";
                }
                return "rm is forbidden in V1";
            case "kill":
                return "kill is forbidden in V1";
            case "curl":
            case "wget":
                if (target.contains("|") || target.contains("sh")) {
                    return command + " pipe-to-shell is forbidden";
                }
                return command + " is forbidden in V1 (no network fetch into execution)";
            default:
                return command + " is forbidden";
        }
    }

    private static String checkWorkingDirectory(ExecutionRequest.Scope scope, ToolRequest request) {
        String workdir = null;
        if (request.arguments() != null) {
            Object wd = request.arguments().get("workingDirectory");
            if (wd instanceof String s) {
                workdir = s;
            }
        }
        if (workdir == null) {
            return null; // 无显式 workingDirectory，视为默认（不额外限制）
        }
        try {
            PathSafety.validateRelative(workdir, false);
        } catch (PathSafety.PathSafetyException e) {
            return "shell working directory unsafe: " + e.getMessage();
        }
        List<String> allowed = scope.allowedPaths() == null || scope.allowedPaths().isEmpty()
                ? List.of("**") : scope.allowedPaths();
        for (String a : allowed) {
            if (a.equals("**") || workdir.startsWith(a) || a.startsWith(workdir)) {
                return null;
            }
        }
        return "shell working directory out of scope: " + workdir;
    }
}
