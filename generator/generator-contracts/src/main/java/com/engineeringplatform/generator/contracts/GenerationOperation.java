package com.engineeringplatform.generator.contracts;

import java.util.List;
import java.util.Map;

/**
 * 单个 Generation Operation（V0.7 §20）。
 * 每个 Operation 明确 Ownership、Template Source、Preconditions、Structured Mutation 与 Dependency DAG。
 * Operation 是声明式的：创建 Plan 时不得修改项目文件。
 */
public record GenerationOperation(
        String operationId,
        OperationType type,
        String targetPath,
        Ownership ownership,
        OverwritePolicy overwritePolicy,
        String templateSource,
        String content,
        String contentRef,
        String expectedBeforeHash,
        String reason,
        List<String> dependencies,
        Map<String, Object> metadata) {

    public GenerationOperation {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String operationId;
        private OperationType type;
        private String targetPath;
        private Ownership ownership;
        private OverwritePolicy overwritePolicy;
        private String templateSource;
        private String content;
        private String contentRef;
        private String expectedBeforeHash;
        private String reason;
        private List<String> dependencies = List.of();
        private Map<String, Object> metadata = Map.of();

        public Builder operationId(String v) { this.operationId = v; return this; }
        public Builder type(OperationType v) { this.type = v; return this; }
        public Builder targetPath(String v) { this.targetPath = v; return this; }
        public Builder ownership(Ownership v) { this.ownership = v; return this; }
        public Builder overwritePolicy(OverwritePolicy v) { this.overwritePolicy = v; return this; }
        public Builder templateSource(String v) { this.templateSource = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder contentRef(String v) { this.contentRef = v; return this; }
        public Builder expectedBeforeHash(String v) { this.expectedBeforeHash = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder dependencies(List<String> v) { this.dependencies = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }

        public GenerationOperation build() {
            return new GenerationOperation(operationId, type, targetPath, ownership,
                    overwritePolicy, templateSource, content, contentRef,
                    expectedBeforeHash, reason, dependencies, metadata);
        }
    }
}
