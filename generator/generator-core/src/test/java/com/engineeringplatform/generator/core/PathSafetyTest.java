package com.engineeringplatform.generator.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Path Safety tests (EP-WORK-005/006 §十九 7-12; §七/§十五).
 */
class PathSafetyTest {

    @TempDir
    Path tempDir;

    // 7. normal relative path
    @Test
    void normalRelativePathAccepted() throws Exception {
        PathSafety.validateRelative("src/main/java/A.java", false);
        Path target = PathSafety.resolveInsideRoot(new WorkspacePort.Default(), tempDir, "src/A.java");
        assertThat(target).isEqualTo(tempDir.resolve("src/A.java").normalize());
    }

    // 8. ../ rejected
    @Test
    void pathTraversalRejected() {
        assertThatThrownBy(() -> PathSafety.validateRelative("../escape.txt", false))
                .isInstanceOf(PathSafety.PathSafetyException.class);
    }

    // 9. absolute path rejected
    @Test
    void absolutePathRejected() {
        assertThatThrownBy(() -> PathSafety.validateRelative("/etc/passwd", false))
                .isInstanceOf(PathSafety.PathSafetyException.class);
        assertThatThrownBy(() -> PathSafety.validateRelative("C:\\windows\\x", false))
                .isInstanceOf(PathSafety.PathSafetyException.class);
    }

    // 10. workspace escape rejected
    @Test
    void workspaceEscapeRejected() {
        assertThatThrownBy(() -> PathSafety.validateRelative("a/../../b", false))
                .isInstanceOf(PathSafety.PathSafetyException.class);
    }

    // 11. symlink escape rejected
    @Test
    void symlinkEscapeRejected() throws Exception {
        Path outside = Files.createTempDirectory("ep-outside-");
        Path link = tempDir.resolve("evil-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 环境不支持 symlink，跳过
        }
        WorkspacePort port = new WorkspacePort.Default();
        assertThatThrownBy(() -> PathSafety.resolveInsideRoot(port, tempDir, "evil-link/secret.txt"))
                .isInstanceOf(PathSafety.PathSafetyException.class);
    }

    // 12. protected path rejected
    @Test
    void protectedPathRejected() {
        assertThatThrownBy(() -> PathSafety.validateRelative(".git/config", false))
                .isInstanceOf(PathSafety.PathSafetyException.class);
        assertThatThrownBy(() -> PathSafety.validateRelative(".gitignore", false))
                .isInstanceOf(PathSafety.PathSafetyException.class);
        assertThatThrownBy(() -> PathSafety.validateRelative(".git/objects/aa", false))
                .isInstanceOf(PathSafety.PathSafetyException.class);
    }
}
