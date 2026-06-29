package com.docgen.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One trace folder per logical run (HTTP request, batch job, version commit, etc.).
 * Append-only {@code operations.jsonl} is the source of truth for replay / rollback planning.
 */
public class OperationTraceSession implements AutoCloseable {

    private static final Logger opLog = LoggerFactory.getLogger("com.docgen.trace.OPERATION");

    private final String traceId;
    private final Path dir;
    private final boolean active;
    private final Instant startedAt = Instant.now();
    private int sequence = 0;
    private String status = "running";
    private String errorMessage;

    private OperationTraceSession() {
        this.traceId = "";
        this.dir = null;
        this.active = false;
    }

    OperationTraceSession(String traceId, Path dir, boolean active) {
        this.traceId = traceId;
        this.dir = dir;
        this.active = active;
    }

    public static OperationTraceSession noop() {
        return new OperationTraceSession();
    }

    public boolean active() {
        return active;
    }

    public String traceId() {
        return traceId;
    }

    public Path directory() {
        return dir;
    }

    public void record(OperationType type, String summary, Map<String, Object> detail) {
        if (!active) {
            return;
        }
        sequence++;
        TraceEvent event = new TraceEvent(sequence, Instant.now(), type, summary,
                detail == null ? Map.of() : detail);
        opLog.info("[trace:{}] #{} {} | {}", traceId, sequence, type, summary);
        try {
            TraceFiles.appendJsonLine(dir.resolve("operations.jsonl"), event);
        } catch (IOException ex) {
            opLog.warn("[trace:{}] failed to append operation: {}", traceId, ex.getMessage());
        }
    }

    public void record(OperationType type, String summary) {
        record(type, summary, Map.of());
    }

    public void snapshotDocument(Path sourceDoc, String label) {
        if (!active) {
            return;
        }
        try {
            TraceWriter.copySnapshot(sourceDoc, dir, label);
            Path dest = dir.resolve("snapshots").resolve(label + ".docx");
            record(OperationType.DOCUMENT_SNAPSHOT, "Snapshot " + label,
                    Map.of("label", label, "file", dest.getFileName().toString(),
                            "bytes", Files.size(dest)));
        } catch (IOException ex) {
            record(OperationType.ERROR, "Snapshot failed: " + label,
                    Map.of("label", label, "error", ex.getMessage()));
        }
    }

    public void writeArtifact(String relativePath, Object payload) {
        if (!active) {
            return;
        }
        try {
            Path target = dir.resolve(relativePath);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            TraceFiles.writeJson(target, payload);
        } catch (IOException ex) {
            record(OperationType.ERROR, "Artifact write failed: " + relativePath,
                    Map.of("path", relativePath, "error", ex.getMessage()));
        }
    }

    public void markSuccess(Map<String, Object> summary) {
        status = "ok";
        finalizeMeta(summary == null ? Map.of() : summary);
    }

    public void markFailure(String message, Map<String, Object> detail) {
        status = "error";
        errorMessage = message;
        Map<String, Object> payload = new LinkedHashMap<>(detail == null ? Map.of() : detail);
        payload.put("message", message);
        record(OperationType.ERROR, message, payload);
        finalizeMeta(Map.of("error", message));
    }

    @Override
    public void close() {
        if (!active) {
            return;
        }
        if ("running".equals(status)) {
            finalizeMeta(Map.of("note", "closed without explicit status"));
        }
    }

    private void finalizeMeta(Map<String, Object> extra) {
        try {
            Path metaPath = dir.resolve("meta.json");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = TraceFiles.mapper().readValue(metaPath.toFile(), Map.class);
            meta.put("ended_at", Instant.now().toString());
            meta.put("duration_ms", java.time.Duration.between(startedAt, Instant.now()).toMillis());
            meta.put("status", status);
            meta.put("operation_count", sequence);
            if (errorMessage != null) {
                meta.put("error", errorMessage);
            }
            meta.put("summary", extra);
            TraceFiles.writeJson(metaPath, meta);
        } catch (IOException ex) {
            opLog.warn("[trace:{}] failed to finalize meta: {}", traceId, ex.getMessage());
        }
    }
}
