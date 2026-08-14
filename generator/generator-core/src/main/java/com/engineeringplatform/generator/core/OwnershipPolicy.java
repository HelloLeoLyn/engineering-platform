package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.Ownership;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Ownership Policy（V0.7 §13/§21 + AGENTS.md）。
 *
 * 每个 Generation Operation 必须知道目标文件 Ownership；
 * Ownership 必须影响 CREATE/MODIFY/DELETE 是否允许。
 *
 * 未知 Ownership → 安全失败（FORBIDDEN 决策），不得默认覆盖。
 */
public final class OwnershipPolicy {

    private OwnershipPolicy() {
    }

    /**
     * 确定目标文件的 Ownership。
     *
     * @param manifest 已加载的 generation-manifest（path → ownership 字符串），可为 null/空
     * @param relativePath 目标相对路径
     * @return 确定的 Ownership；无法确定时返回 null（调用方按 UNKNOWN 处理）
     */
    public static Ownership resolve(Map<String, String> manifest, String relativePath) {
        if (manifest == null) {
            return null;
        }
        String raw = manifest.get(relativePath);
        if (raw == null) {
            return null;
        }
        try {
            return Ownership.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 读取 generation-manifest.json（若存在）。manifest 是 Executor 写入的
     * .generator/generation-manifest.json（V0.7 §13 集中记录 ownership）。
     */
    public static Map<String, String> loadManifest(WorkspacePort port, Path root) throws IOException {
        Path manifestPath = port.resolve(root, ".generator/generation-manifest.json");
        if (!port.exists(manifestPath)) {
            return Map.of();
        }
        String json = port.readString(manifestPath);
        // 最小 JSON 解析：{"files": {"path": "GENERATED", ...}}
        Map<String, String> result = new java.util.HashMap<>();
        int filesIdx = json.indexOf("\"files\"");
        if (filesIdx < 0) {
            return result;
        }
        int braceStart = json.indexOf('{', filesIdx);
        // 取 files value 的结束大括号（不是整个 JSON 的最外层）
        int braceEnd = json.indexOf('}', braceStart);
        if (braceStart < 0 || braceEnd < 0 || braceEnd <= braceStart) {
            return result;
        }
        String inner = json.substring(braceStart + 1, braceEnd);
        for (String pair : inner.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().replace("\"", "");
                String v = kv[1].trim().replace("\"", "");
                if (!k.isEmpty() && !v.isEmpty()) {
                    result.put(k, v);
                }
            }
        }
        return result;
    }
}
