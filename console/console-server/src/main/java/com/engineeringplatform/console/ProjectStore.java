package com.engineeringplatform.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Filesystem-backed project metadata store.
 * Lightweight: single JSON file under the console data directory. No database.
 */
public final class ProjectStore {

    private final Path file;
    private final List<Map<String, Object>> projects = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);

    public ProjectStore(Path file) {
        this.file = file;
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            Object list = root.get("projects");
            if (list instanceof List<?> l) {
                projects.clear();
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) projects.add((Map<String, Object>) m);
                }
            }
            Object s = root.get("seq");
            if (s instanceof Number n) seq.set(n.longValue());
        } catch (Exception ignored) {
            // corrupt/missing metadata: start fresh
        }
    }

    private synchronized void persist() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("seq", seq.get());
            root.put("projects", projects);
            Files.writeString(file, Json.write(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist project metadata: " + e.getMessage(), e);
        }
    }

    public synchronized Map<String, Object> add(Map<String, Object> entry) {
        Map<String, Object> copy = new LinkedHashMap<>(entry);
        if (!copy.containsKey("id")) {
            copy.put("id", "project-" + seq.incrementAndGet());
        }
        if (!copy.containsKey("createdAt")) {
            copy.put("createdAt", Instant.now().toString());
        }
        projects.add(0, copy);
        persist();
        return copy;
    }

    public synchronized List<Map<String, Object>> list() {
        return new ArrayList<>(projects);
    }
}
