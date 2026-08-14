package com.engineeringplatform.generator.core;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Path Safety 硬安全边界（EP-WORK-005/006 指令 §七/§十五）。
 *
 * 任何 target path：
 *  - 必须是 workspace/project root 内的相对路径；
 *  - 拒绝 ../、absolute path、path traversal、workspace escape；
 *  - 拒绝保护路径：.git/ .git/** .gitignore（除非 Plan 明确允许且 V0.7 支持——V1 默认拒绝）；
 *  - 拒绝 Engineering Platform 保护路径（.generator/ 内部由 Executor 管理）。
 *
 * Symlink Safety：即使逻辑路径在 root 内，如果 symlink 最终指向 root 外，必须拒绝。
 * 不得通过 symlink 绕过 workspace boundary。
 */
public final class PathSafety {

    /** 保护路径前缀（V1 默认拒绝；Plan 显式 allowGitignore 时仍拒绝 .git/）。 */
    public static final String[] PROTECTED_PREFIXES = {
            ".git/",
            ".git",
    };

    public static final String[] PROTECTED_EXACT = {
            ".gitignore",
    };

    private PathSafety() {
    }

    /**
     * 校验相对路径合法性（不触碰文件系统）。
     *
     * @throws PathSafetyException 非法路径
     */
    public static void validateRelative(String relativePath, boolean allowGitignore) throws PathSafetyException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new PathSafetyException("target path is blank");
        }
        String normalized = relativePath.replace('\\', '/');
        // 拒绝绝对路径（POSIX 与 Windows 盘符）
        if (normalized.startsWith("/")) {
            throw new PathSafetyException("absolute path rejected: " + relativePath);
        }
        if (normalized.matches("^[a-zA-Z]:.*")) {
            throw new PathSafetyException("absolute path rejected: " + relativePath);
        }
        // 拒绝路径穿越
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new PathSafetyException("path traversal rejected: " + relativePath);
            }
        }
        if (normalized.contains("../") || normalized.endsWith("/..")) {
            throw new PathSafetyException("path traversal rejected: " + relativePath);
        }
        // 保护路径
        for (String p : PROTECTED_EXACT) {
            if (normalized.equals(p)) {
                if (p.equals(".gitignore") && allowGitignore) {
                    return;
                }
                throw new PathSafetyException("protected path rejected: " + relativePath);
            }
        }
        for (String p : PROTECTED_PREFIXES) {
            if (normalized.equals(p) || normalized.startsWith(p + "/")) {
                throw new PathSafetyException("protected path rejected: " + relativePath);
            }
        }
    }

    /**
     * 校验路径 + symlink 不逃逸 root（触碰文件系统，用于真实写前检查）。
     *
     * @throws PathSafetyException 非法路径或 symlink 逃逸
     */
    public static Path resolveInsideRoot(WorkspacePort port, Path root, String relativePath)
            throws PathSafetyException, IOException {
        validateRelative(relativePath, false);
        Path rootReal = root.toRealPath();
        Path target = port.resolve(rootReal, relativePath);
        Path targetReal;
        if (port.exists(target)) {
            targetReal = port.realPath(target);
        } else {
            // 目标不存在：逐级解析最近的父级真实路径，防止通过存在的 symlink 父目录逃逸
            Path cursor = target.getParent();
            Path realParent = rootReal;
            while (cursor != null && !cursor.equals(rootReal)) {
                if (port.exists(cursor)) {
                    realParent = port.realPath(cursor);
                    break;
                }
                cursor = cursor.getParent();
            }
            targetReal = realParent.resolve(rootReal.relativize(cursor == null ? target : target));
        }
        if (!targetReal.startsWith(rootReal)) {
            throw new PathSafetyException("symlink escape rejected: " + relativePath);
        }
        return target;
    }

    public static final class PathSafetyException extends Exception {
        public PathSafetyException(String message) {
            super(message);
        }
    }
}
