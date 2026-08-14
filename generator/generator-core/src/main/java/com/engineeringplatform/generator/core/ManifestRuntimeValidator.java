package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ManifestValidationPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manifest Runtime Validator（EP-WORK-010A — EP-WORK-002 Resolution）。
 *
 * 背景：EP-WORK-002（Manifest Validation）原判 DEFERRED。004B 建立了
 * ManifestValidationPort（Validation Boundary）接口，但正式实现缺位
 * （测试使用 ALWAYS_VALID stub；Python validator 只做 build-time contract
 * validation）。
 *
 * 本类 = 最小 Java Runtime Manifest Validator，接入现有 Validation Boundary：
 *  - 不复制第二套 Manifest Schema Contract（schema 事实源仍是
 *    generator/schemas/*.schema.yaml + Python validator）
 *  - 不修改 Manifest Schema 语义
 *  - 不重写 Python Validator
 *  - 只做 runtime 最小结构校验：schemaVersion const、必填字段存在性、
 *    已知 enum 合法性（轻量、纯计算、无副作用）
 *
 * 完整 JSON Schema engine binding（例如 networknt json-schema-validator）
 * 因离线环境无法引入新库，标记为：
 *   JDK25/DEPENDENCY GATE — 解锁后可将本类替换/包装为真实 schema engine。
 *
 * 分层：Python validator = contract/build-time validation；
 *       本类 = runtime boundary 最小校验。
 */
public final class ManifestRuntimeValidator implements ManifestValidationPort {

    private static final Map<String, Set<String>> REQUIRED_FIELDS = Map.of(
            "platform", Set.of("schemaVersion", "platform", "technology", "profiles", "registries"),
            "project", Set.of("schemaVersion", "project", "platform", "modules"),
            "module", Set.of("schemaVersion", "module"),
            "provider", Set.of("schemaVersion", "provider"));

    private static final Map<String, Set<String>> KNOWN_ENUMS = Map.of(
            // WorkItem status（V0.7 §23 状态机）
            "workItemStatus", Set.of("NEW", "ANALYZING", "PLANNED", "APPROVED", "IMPLEMENTING",
                    "IMPLEMENTED", "VERIFYING", "READY", "DONE", "ACCEPTED", "REJECTED",
                    "BLOCKED", "FAILED", "CANCELLED", "REOPENED"),
            // WorkItem type
            "workItemType", Set.of("REQUIREMENT", "BUG", "REFACTOR", "MIGRATION", "UPGRADE",
                    "OPERATIONS", "SECURITY", "TECH_DEBT"));

    @Override
    public boolean isValid(String manifestType, Map<String, Object> manifest) {
        return validationErrors(manifestType, manifest).isEmpty();
    }

    @Override
    public List<String> validationErrors(String manifestType, Map<String, Object> manifest) {
        List<String> errors = new ArrayList<>();
        if (manifest == null) {
            errors.add("manifest is null");
            return errors;
        }
        // schemaVersion const 1
        Object sv = manifest.get("schemaVersion");
        if (sv == null) {
            errors.add("missing schemaVersion");
        } else if (!(sv instanceof Number n) || n.intValue() != 1) {
            errors.add("schemaVersion must be 1, got: " + sv);
        }
        // 必填字段
        Set<String> required = REQUIRED_FIELDS.get(manifestType);
        if (required != null) {
            for (String field : required) {
                if (!manifest.containsKey(field)) {
                    errors.add("missing required field: " + field);
                }
            }
        }
        // 已知 enum 合法性（仅当存在且为字符串时检查；不做完整 schema 语义）
        checkEnum(manifest, errors, "workItemStatus", "status");
        checkEnum(manifest, errors, "workItemType", "type");
        return errors;
    }

    private static void checkEnum(Map<String, Object> manifest, List<String> errors,
                                  String enumName, String field) {
        Object value = manifest.get(field);
        if (value == null) {
            return; // 必填性由 REQUIRED_FIELDS 处理
        }
        Set<String> allowed = KNOWN_ENUMS.get(enumName);
        if (allowed != null && !allowed.contains(value.toString())) {
            errors.add("invalid " + field + " for " + enumName + ": " + value);
        }
    }
}
