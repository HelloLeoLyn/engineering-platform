package com.engineeringplatform.generator.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 最小文件系统抽象（WorkspacePort）。
 * 目的：测试可使用临时 workspace；不引入复杂 Virtual File System Framework。
 * 所有路径操作都基于 project root 解析后的绝对路径。
 */
public interface WorkspacePort {

    /** 将相对路径解析为 root 下的绝对路径（不触碰文件系统）。 */
    Path resolve(Path root, String relativePath);

    boolean exists(Path path);

    boolean isDirectory(Path path);

    boolean isSymlink(Path path);

    /** 读取真实路径（解析 symlink）。 */
    Path realPath(Path path) throws IOException;

    String readString(Path path) throws IOException;

    byte[] readBytes(Path path) throws IOException;

    void writeString(Path path, String content) throws IOException;

    void writeBytes(Path path, byte[] content) throws IOException;

    void createDirectories(Path path) throws IOException;

    void delete(Path path) throws IOException;

    long size(Path path) throws IOException;

    /** 基于 WorkspacePort 的默认实现（java.nio）。 */
    final class Default implements WorkspacePort {
        @Override
        public Path resolve(Path root, String relativePath) {
            return root.resolve(relativePath).normalize();
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean isDirectory(Path path) {
            return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean isSymlink(Path path) {
            return Files.isSymbolicLink(path);
        }

        @Override
        public Path realPath(Path path) throws IOException {
            return path.toRealPath();
        }

        @Override
        public String readString(Path path) throws IOException {
            return Files.readString(path, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] readBytes(Path path) throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public void writeString(Path path, String content) throws IOException {
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        @Override
        public void writeBytes(Path path, byte[] content) throws IOException {
            Files.write(path, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        @Override
        public void createDirectories(Path path) throws IOException {
            Files.createDirectories(path);
        }

        @Override
        public void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }

        @Override
        public long size(Path path) throws IOException {
            return Files.size(path);
        }
    }
}
