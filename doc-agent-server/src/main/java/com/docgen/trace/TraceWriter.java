package com.docgen.trace;

import com.docgen.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class TraceWriter {

    private static final Logger log = LoggerFactory.getLogger(TraceWriter.class);
    private static final DateTimeFormatter FOLDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Path traceRoot;
    private final boolean enabled;

    public TraceWriter(AppProperties properties) throws IOException {
        Path configured = Path.of(properties.traceDir());
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir")).resolve(configured);
        }
        this.traceRoot = configured.toAbsolutePath().normalize();
        this.enabled = properties.traceEnabled();
        if (enabled) {
            Files.createDirectories(traceRoot);
            log.info("Operation trace root: {}", traceRoot);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public Path traceRoot() {
        return traceRoot;
    }

    public OperationTraceSession openSession(String kind, Map<String, Object> meta) throws IOException {
        if (!enabled) {
            return OperationTraceSession.noop();
        }
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String folderName = FOLDER_TIME.format(Instant.now()) + "_" + kind + "_" + traceId;
        Path dir = traceRoot.resolve(folderName);
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("snapshots"));
        Files.createDirectories(dir.resolve("llm"));

        Map<String, Object> initialMeta = new LinkedHashMap<>();
        initialMeta.put("trace_id", traceId);
        initialMeta.put("kind", kind);
        initialMeta.put("started_at", Instant.now().toString());
        initialMeta.putAll(meta);
        TraceFiles.writeJson(dir.resolve("meta.json"), initialMeta);

        return new OperationTraceSession(traceId, dir, enabled);
    }

    static void copySnapshot(Path sourceDoc, Path traceDir, String label) throws IOException {
        Path dest = traceDir.resolve("snapshots").resolve(label + ".docx");
        Files.copy(sourceDoc, dest, StandardCopyOption.REPLACE_EXISTING);
    }
}
