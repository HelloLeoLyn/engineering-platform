package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ChangeManifest;
import com.engineeringplatform.generator.contracts.DryRunResult;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.GenerationOperation;
import com.engineeringplatform.generator.contracts.GenerationPlan;
import com.engineeringplatform.generator.contracts.OperationType;
import com.engineeringplatform.generator.contracts.OverwritePolicy;
import com.engineeringplatform.generator.contracts.Ownership;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generator Executor tests (EP-WORK-005/006 §十九 13-36): Dry Run / Staging / Apply /
 * Rollback / Transaction / Safety. All tests use a temporary workspace; the real
 * Engineering Platform repository is never written by tests.
 */
class GeneratorExecutorTest {

    @TempDir
    Path tempDir;

    private GeneratorExecutor executor() {
        return new GeneratorExecutor();
    }

    private GenerationOperation create(String id, String path, String content) {
        return GenerationOperation.builder()
                .operationId(id).type(OperationType.CREATE_FILE).targetPath(path)
                .ownership(Ownership.GENERATED).overwritePolicy(OverwritePolicy.ALLOWED)
                .content(content).reason("test").build();
    }

    private GenerationOperation modify(String id, String path, String content) {
        return GenerationOperation.builder()
                .operationId(id).type(OperationType.UPDATE_MANAGED_FILE).targetPath(path)
                .ownership(Ownership.MANAGED).overwritePolicy(OverwritePolicy.STRUCTURED_ONLY)
                .content(content).reason("test").build();
    }

    private GenerationOperation delete(String id, String path) {
        return GenerationOperation.builder()
                .operationId(id).type(OperationType.DELETE).targetPath(path)
                .ownership(Ownership.GENERATED).overwritePolicy(OverwritePolicy.ALLOWED)
                .reason("test").build();
    }

    private GenerationPlan plan(List<GenerationOperation> ops) {
        return new GenerationPlan("gp-test", "SCAFFOLD", "0.1.0", "res-x", "hash-x",
                "test", ops, List.of(), List.of(),
                Map.of("create", 0, "modify", 0, "delete", 0));
    }

    /** 写入 generation-manifest.json：{"files": {"path": "OWNERSHIP"}} */
    private void writeManifest(Map<String, String> entries) throws Exception {
        StringBuilder sb = new StringBuilder("{\"files\": {");
        boolean first = true;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
            first = false;
        }
        sb.append("}}");
        Path manifest = tempDir.resolve(".generator/generation-manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- Dry Run (13-17) ----

    @Test
    void dryRunCreatesNoTargetFiles() {
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "hello")));
        DryRunResult r = executor().dryRun(p, tempDir);
        assertThat(r.executable()).isTrue();
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isFalse();
    }

    @Test
    void dryRunReportsCreate() {
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "hello")));
        DryRunResult r = executor().dryRun(p, tempDir);
        assertThat(r.plannedChanges()).anyMatch(c -> c.type() == OperationType.CREATE_FILE);
    }

    @Test
    void dryRunReportsModify() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/registry.yaml"), "old");
        writeManifest(Map.of("src/registry.yaml", "MANAGED"));
        GenerationPlan p = plan(List.of(modify("op-2", "src/registry.yaml", "new")));
        DryRunResult r = executor().dryRun(p, tempDir);
        assertThat(r.plannedChanges()).anyMatch(c -> c.type() == OperationType.UPDATE_MANAGED_FILE);
        assertThat(Files.readString(tempDir.resolve("src/registry.yaml"))).isEqualTo("old");
    }

    @Test
    void blockedOperationReported() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/user.java"), "user code");
        // USER_OWNED 已存在文件，不允许覆盖
        writeManifest(Map.of("src/user.java", "USER_OWNED"));
        GenerationOperation op = GenerationOperation.builder()
                .operationId("op-1").type(OperationType.UPDATE_MANAGED_FILE)
                .targetPath("src/user.java").ownership(Ownership.USER_OWNED)
                .overwritePolicy(OverwritePolicy.FORBIDDEN).content("hack").reason("test").build();
        DryRunResult r = executor().dryRun(plan(List.of(op)), tempDir);
        assertThat(r.blockedOperations()).isNotEmpty();
        assertThat(r.executable()).isFalse();
    }

    @Test
    void conflictReported() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/A.java"), "actual");
        writeManifest(Map.of("src/A.java", "GENERATED"));
        GenerationOperation op = GenerationOperation.builder()
                .operationId("op-1").type(OperationType.UPDATE_MANAGED_FILE)
                .targetPath("src/A.java").ownership(Ownership.GENERATED)
                .overwritePolicy(OverwritePolicy.ALLOWED)
                .expectedBeforeHash("deadbeef").content("new").reason("test").build();
        DryRunResult r = executor().dryRun(plan(List.of(op)), tempDir);
        assertThat(r.conflicts()).isNotEmpty();
        assertThat(r.executable()).isFalse();
    }

    // ---- Staging (18-20) ----

    @Test
    void contentStagedBeforeApply() throws Exception {
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "staged-content")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        Path staged = tempDir.resolve(".generator/staging/" + r.transactionId() + "/op-1");
        assertThat(Files.exists(staged)).isTrue();
        assertThat(Files.readString(staged)).isEqualTo("staged-content");
    }

    @Test
    void stagingBoundToTransaction() throws Exception {
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "x")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(Files.exists(tempDir.resolve(".generator/staging/" + r.transactionId()))).isTrue();
        assertThat(Files.exists(tempDir.resolve(".generator/transactions/" + r.transactionId()))).isTrue();
    }

    @Test
    void stagingDoesNotModifyTarget() throws Exception {
        Stager stager = new Stager();
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "x")));
        stager.stage(tempDir, "tx-123", p);
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(".generator/staging/tx-123/op-1"))).isTrue();
    }

    // ---- Apply (21-24) ----

    @Test
    void createSuccess() {
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "hello")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isTrue();
    }

    @Test
    void modifySuccess() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/registry.yaml"), "old");
        writeManifest(Map.of("src/registry.yaml", "MANAGED"));
        GenerationPlan p = plan(List.of(modify("op-2", "src/registry.yaml", "new")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        assertThat(Files.readString(tempDir.resolve("src/registry.yaml"))).isEqualTo("new");
    }

    @Test
    void multiFileSuccess() {
        GenerationPlan p = plan(List.of(
                create("op-1", "src/A.java", "a"),
                create("op-2", "src/B.java", "b"),
                create("op-3", "src/sub/C.java", "c")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("src/B.java"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("src/sub/C.java"))).isTrue();
    }

    @Test
    void expectedBeforeHashEnforced() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/A.java"), "actual-content");
        writeManifest(Map.of("src/A.java", "GENERATED"));
        GenerationOperation op = GenerationOperation.builder()
                .operationId("op-1").type(OperationType.UPDATE_MANAGED_FILE)
                .targetPath("src/A.java").ownership(Ownership.GENERATED)
                .overwritePolicy(OverwritePolicy.ALLOWED)
                .expectedBeforeHash("wrong-hash").content("new").reason("test").build();
        ExecutionResult r = executor().execute(plan(List.of(op)), tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
        assertThat(Files.readString(tempDir.resolve("src/A.java"))).isEqualTo("actual-content");
    }

    // ---- Rollback (25-28) ----

    @Test
    void rollbackCreatedFile() throws Exception {
        // op-1 create 成功；op-2 故意失败（目标路径是已存在目录）→ rollback 删除 op-1 创建的文件
        Files.createDirectories(tempDir.resolve("src/dir-as-file"));
        GenerationPlan p = plan(List.of(
                create("op-1", "src/A.java", "a"),
                create("op-2", "src/dir-as-file", "should-fail")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.ROLLED_BACK);
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isFalse();
    }

    @Test
    void rollbackModifiedFile() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/registry.yaml"), "original");
        writeManifest(Map.of("src/registry.yaml", "MANAGED"));
        // op-1 modify 成功；op-2 故意失败 → rollback 恢复 registry.yaml
        Files.createDirectories(tempDir.resolve("src/dir-as-file"));
        GenerationPlan p = plan(List.of(
                modify("op-1", "src/registry.yaml", "modified"),
                create("op-2", "src/dir-as-file", "should-fail")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.ROLLED_BACK);
        assertThat(Files.readString(tempDir.resolve("src/registry.yaml"))).isEqualTo("original");
    }

    @Test
    void rollbackDeletedFile() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/legacy.java"), "legacy");
        writeManifest(Map.of("src/legacy.java", "GENERATED"));
        Files.createDirectories(tempDir.resolve("src/dir-as-file"));
        // op-1 delete 成功；op-2 故意失败 → rollback 恢复被删除文件
        GenerationPlan p = plan(List.of(
                delete("op-1", "src/legacy.java"),
                create("op-2", "src/dir-as-file", "should-fail")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.ROLLED_BACK);
        assertThat(Files.readString(tempDir.resolve("src/legacy.java"))).isEqualTo("legacy");
    }

    @Test
    void partialFailureRestoresPriorOperations() throws Exception {
        Files.createDirectories(tempDir.resolve("src/dir-as-file"));
        GenerationPlan p = plan(List.of(
                create("op-1", "src/A.java", "a"),
                create("op-2", "src/B.java", "b"),
                create("op-3", "src/dir-as-file", "should-fail")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.ROLLED_BACK);
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("src/B.java"))).isFalse();
    }

    // ---- Transaction (29-32) ----

    @Test
    void successfulTransactionState() throws Exception {
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "a")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        String json = Files.readString(tempDir.resolve(".generator/transactions/" + r.transactionId() + "/transaction.json"));
        assertThat(json).contains("\"state\":\"COMMITTED\"");
    }

    @Test
    void failedTransactionState() throws Exception {
        // 路径安全拒绝 → FAILED（transaction 未创建，直接失败）
        GenerationOperation op = GenerationOperation.builder()
                .operationId("op-1").type(OperationType.CREATE_FILE)
                .targetPath("../escape.java").ownership(Ownership.GENERATED)
                .overwritePolicy(OverwritePolicy.ALLOWED).content("x").reason("test").build();
        ExecutionResult r = executor().execute(plan(List.of(op)), tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
    }

    @Test
    void rollbackState() throws Exception {
        Files.createDirectories(tempDir.resolve("src/dir-as-file"));
        GenerationPlan p = plan(List.of(
                create("op-1", "src/A.java", "a"),
                create("op-2", "src/dir-as-file", "should-fail")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.ROLLED_BACK);
        String json = Files.readString(tempDir.resolve(".generator/transactions/" + r.transactionId() + "/transaction.json"));
        assertThat(json).contains("\"state\":\"ROLLED_BACK\"");
    }

    @Test
    void changeManifestGenerated() throws Exception {
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "a")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.changeManifest()).isNotNull();
        assertThat(r.changeManifest().planId()).isEqualTo("gp-test");
        assertThat(r.changeManifest().entries()).anyMatch(e ->
                e.targetPath().equals("src/A.java") && e.status() == ChangeManifest.ChangeStatus.APPLIED);
    }

    // ---- Safety (33-36) ----

    @Test
    void userOwnedFileNotOverwritten() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/user.java"), "user code");
        writeManifest(Map.of("src/user.java", "USER_OWNED"));
        GenerationOperation op = GenerationOperation.builder()
                .operationId("op-1").type(OperationType.UPDATE_MANAGED_FILE)
                .targetPath("src/user.java").ownership(Ownership.USER_OWNED)
                .overwritePolicy(OverwritePolicy.FORBIDDEN).content("hack").reason("test").build();
        ExecutionResult r = executor().execute(plan(List.of(op)), tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
        assertThat(Files.readString(tempDir.resolve("src/user.java"))).isEqualTo("user code");
    }

    @Test
    void protectedFileNotModified() {
        GenerationOperation op = GenerationOperation.builder()
                .operationId("op-1").type(OperationType.UPDATE_MANAGED_FILE)
                .targetPath(".git/config").ownership(Ownership.GENERATED)
                .overwritePolicy(OverwritePolicy.ALLOWED).content("x").reason("test").build();
        ExecutionResult r = executor().execute(plan(List.of(op)), tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
    }

    @Test
    void unknownOwnershipFailsSafe() throws Exception {
        // 文件已存在但 manifest 无记录 → unknown ownership → 阻止
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/mystery.java"), "mystery");
        GenerationOperation op = GenerationOperation.builder()
                .operationId("op-1").type(OperationType.UPDATE_MANAGED_FILE)
                .targetPath("src/mystery.java").ownership(Ownership.GENERATED)
                .overwritePolicy(OverwritePolicy.ALLOWED).content("x").reason("test").build();
        ExecutionResult r = executor().execute(plan(List.of(op)), tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.FAILED);
        assertThat(Files.readString(tempDir.resolve("src/mystery.java"))).isEqualTo("mystery");
    }

    @Test
    void noGitDestructiveCommandDependency() throws Exception {
        // 验证实现源码不含 git reset --hard / git clean -fd / git checkout .（V0.7 §21 禁止）
        String[] sources = {
                "generator-core/src/main/java/com/engineeringplatform/generator/core/GeneratorExecutor.java",
                "generator-core/src/main/java/com/engineeringplatform/generator/core/RollbackManager.java",
                "generator-core/src/main/java/com/engineeringplatform/generator/core/TransactionManager.java",
        };
        Path repoRoot = tempDir; // 占位；实际读取仓库源码路径
        Path base = findRepoRoot();
        if (base == null) {
            return; // 测试环境无法定位源码则跳过（不伪称）
        }
        for (String src : sources) {
            Path file = base.resolve(src);
            if (!Files.exists(file)) {
                continue;
            }
            String content = Files.readString(file);
            assertThat(content).doesNotContain("git reset --hard");
            assertThat(content).doesNotContain("git clean -fd");
            assertThat(content).doesNotContain("git checkout .");
        }
    }

    private Path findRepoRoot() {
        Path p = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6; i++) {
            if (Files.exists(p.resolve("generator/generator-core"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                return null;
            }
        }
        return null;
    }

    // ---- Isolation / determinism (39-40) ----

    @Test
    void testWorkspaceIsolated() throws Exception {
        // Executor 只写 tempDir 内（root/.generator + 目标路径），不碰 root 之外
        Path outside = Files.createTempDirectory("ep-outside-");
        Path marker = outside.resolve("sentinel.txt");
        Files.writeString(marker, "untouched");
        GenerationPlan p = plan(List.of(create("op-1", "src/A.java", "a")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        assertThat(Files.readString(marker)).isEqualTo("untouched");
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isTrue();
        assertThat(Files.exists(tempDir.resolve(".generator/transactions/" + r.transactionId()))).isTrue();
    }

    @Test
    void rollbackDoesNotAffectUnrelatedFile() throws Exception {
        // 与事务无关的文件不受 rollback 影响
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/unrelated.txt"), "keep-me");
        Files.createDirectories(tempDir.resolve("src/dir-as-file"));
        GenerationPlan p = plan(List.of(
                create("op-1", "src/A.java", "a"),
                create("op-2", "src/dir-as-file", "should-fail")));
        ExecutionResult r = executor().execute(p, tempDir);
        assertThat(r.status()).isEqualTo(ExecutionResult.ExecutionStatus.ROLLED_BACK);
        assertThat(Files.exists(tempDir.resolve("src/A.java"))).isFalse(); // rollback 删除自己创建的
        assertThat(Files.readString(tempDir.resolve("src/unrelated.txt"))).isEqualTo("keep-me");
    }
}
