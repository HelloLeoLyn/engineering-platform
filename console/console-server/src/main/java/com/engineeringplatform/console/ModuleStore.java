package com.engineeringplatform.console;

import com.engineeringplatform.generator.core.AssetYamlReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Filesystem-backed Business Module store (V06-WORK-005).
 * Modules are saved as existing Business Module Contract (module manifest
 * YAML with a `business:` section) — the SAME contract the CLI/generator
 * consume. No Console-private module schema.
 */
public final class ModuleStore {

    private final Path dir;
    private final AtomicLong seq = new AtomicLong(0);

    public ModuleStore(Path dir) {
        this.dir = dir;
    }

    private Path fileFor(String id) {
        return dir.resolve(id + ".yaml");
    }

    public synchronized List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path f : stream.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                out.add(read(f));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(Path f) throws IOException {
        Map<String, Object> manifest =
                (Map<String, Object>) AssetYamlReader.parse(Files.readString(f, StandardCharsets.UTF_8));
        Map<String, Object> module = (Map<String, Object>) manifest.get("module");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", module.get("id"));
        out.put("name", module.get("name"));
        out.put("status", "GENERATED");
        out.put("yaml", Files.readString(f, StandardCharsets.UTF_8));
        return out;
    }

    public synchronized Map<String, Object> save(Map<String, Object> moduleManifest) throws IOException {
        Files.createDirectories(dir);
        String id = String.valueOf(((Map<?, ?>) moduleManifest.get("module")).get("id"));
        String yaml = YamlDumper.dump(moduleManifest);
        Files.writeString(fileFor(id), yaml, StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "READY");
        out.put("yaml", yaml);
        return out;
    }

    public synchronized Map<String, Object> get(String id) throws IOException {
        Path f = fileFor(id);
        if (!Files.isRegularFile(f)) throw new IllegalArgumentException("module not found: " + id);
        return read(f);
    }

    public synchronized void delete(String id) throws IOException {
        Files.deleteIfExists(fileFor(id));
    }
}
