package com.engineeringplatform.generator.contracts;

import java.util.Map;

/**
 * Evidence（V0.7 §22：Evidence 通过受控 FileResource/ArtifactRef 引用；
 * EP-WORK-007/008 指令 §八）。
 *
 * Evidence 保存引用和 metadata，不无条件把完整日志/二进制/大文件内容塞进 Contract。
 */
public record Evidence(
        String evidenceId,
        EvidenceType type,
        String reference,
        Map<String, Object> metadata) {

    public Evidence {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum EvidenceType {
        BUILD_RESULT,
        TEST_RESULT,
        FILE_DIFF,
        ARTIFACT,
        LOG,
        SCREENSHOT,
        COMMAND_RESULT,
        CHANGE_MANIFEST,
        GENERATION_PLAN,
        VERIFICATION_ARTIFACT
    }
}
