package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.OperationType;
import com.engineeringplatform.generator.contracts.OverwritePolicy;
import com.engineeringplatform.generator.contracts.Ownership;

/**
 * Overwrite Policy 决策（V0.7 §21 + EP-WORK-005/006 指令 §六）。
 *
 * Ownership → 覆盖策略映射：
 *  - GENERATED  → ALLOWED（仅 Manifest 证明归属时可重生成）
 *  - MANAGED    → STRUCTURED_ONLY（仅结构化修改）
 *  - USER_OWNED → FORBIDDEN（默认绝不覆盖）
 *  - IMMUTABLE  → FORBIDDEN（禁止修改/删除）
 *  - UNKNOWN    → FORBIDDEN（安全失败，不默认覆盖）
 *
 * 不得因为文件存在就直接覆盖。
 */
public final class OverwritePolicyEvaluator {

    private OverwritePolicyEvaluator() {
    }

    /** Ownership → OverwritePolicy（不依赖文件是否存在）。 */
    public static OverwritePolicy policyFor(Ownership ownership) {
        if (ownership == null) {
            return OverwritePolicy.FORBIDDEN;
        }
        return switch (ownership) {
            case GENERATED -> OverwritePolicy.ALLOWED;
            case MANAGED -> OverwritePolicy.STRUCTURED_ONLY;
            case USER_OWNED, IMMUTABLE -> OverwritePolicy.FORBIDDEN;
        };
    }

    /**
     * 完整决策：给定 ownership + operation 类型 + 目标是否已存在，
     * 判断是否允许执行（V0.7 §21 语义）。
     *
     * @return true = 允许；false = 阻止
     */
    public static boolean allows(Ownership ownership, OperationType type, boolean targetExists) {
        if (ownership == null) {
            return false; // unknown ownership fails safe
        }
        return switch (ownership) {
            case GENERATED -> switch (type) {
                case CREATE_DIRECTORY, CREATE_FILE, UPDATE_MANAGED_FILE, DELETE -> true;
                default -> false; // REGISTER_*/ADD_* 由 V1 Executor 不支持，阻止
            };
            case MANAGED -> switch (type) {
                case CREATE_DIRECTORY, CREATE_FILE -> true;
                case UPDATE_MANAGED_FILE -> targetExists; // 结构化修改已有文件
                case DELETE -> false; // 删除默认高风险，V1 MANAGED 不自动删除
                default -> false;
            };
            case USER_OWNED -> switch (type) {
                case CREATE_DIRECTORY, CREATE_FILE -> !targetExists; // 首次生成 skeleton 允许
                default -> false; // 已存在 / 修改 / 删除 → 禁止
            };
            case IMMUTABLE -> false; // 禁止修改/删除
        };
    }
}
