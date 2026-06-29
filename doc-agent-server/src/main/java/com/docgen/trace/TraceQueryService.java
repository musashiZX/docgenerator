package com.docgen.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class TraceQueryService {

    private final TraceWriter traceWriter;

    public TraceQueryService(TraceWriter traceWriter) {
        this.traceWriter = traceWriter;
    }

    public Path resolveTraceDir(String traceId) throws IOException {
        Path root = traceWriter.traceRoot();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith("_" + traceId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Trace not found: " + traceId));
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadMeta(Path traceDir) throws IOException {
        Path meta = traceDir.resolve("meta.json");
        if (!Files.exists(meta)) {
            return Map.of("folder", traceDir.getFileName().toString());
        }
        Map<String, Object> parsed = TraceFiles.mapper().readValue(meta.toFile(), Map.class);
        Map<String, Object> row = new LinkedHashMap<>(parsed);
        row.put("folder", traceDir.getFileName().toString());
        return row;
    }

    public List<Map<String, Object>> loadOperations(Path traceDir) throws IOException {
        Path ops = traceDir.resolve("operations.jsonl");
        if (!Files.exists(ops)) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : Files.readAllLines(ops)) {
            if (line.isBlank()) {
                continue;
            }
            events.add(TraceFiles.mapper().readValue(line, new TypeReference<>() {}));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildRoundSummary(String traceId) throws IOException {
        Path dir = resolveTraceDir(traceId);
        Map<String, Object> meta = loadMeta(dir);
        List<Map<String, Object>> toolLog = extractToolLog(loadOperations(dir));

        String docName = resolveDocumentName(dir, meta);

        Map<String, Object> round = RoundSummaryBuilder.fromToolLog(traceId, docName, toolLog);
        round.put("meta", meta);
        round.put("trace_folder", dir.getFileName().toString());
        return round;
    }

    @SuppressWarnings("unchecked")
    private String resolveDocumentName(Path dir, Map<String, Object> meta) throws IOException {
        Path req = dir.resolve("request.json");
        if (Files.exists(req)) {
            Map<String, Object> parsed = TraceFiles.mapper().readValue(req.toFile(), Map.class);
            Object body = parsed.get("body");
            if (body instanceof Map<?, ?> bodyMap) {
                Object doc = ((Map<String, Object>) bodyMap).get("doc_name");
                if (doc != null && !doc.toString().isBlank()) {
                    return doc.toString();
                }
            }
        }
        Object kind = meta.get("kind");
        return kind != null ? kind.toString() : "unknown";
    }

    /** Reconstruct tool log from TOOL_DISPATCH + TOOL_RESULT operation pairs. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractToolLog(List<Map<String, Object>> events) {
        List<Map<String, Object>> toolLog = new ArrayList<>();
        Map<String, Object> pending = null;
        for (Map<String, Object> event : events) {
            String type = String.valueOf(event.get("type"));
            if ("TOOL_DISPATCH".equals(type)) {
                Map<String, Object> detail = event.get("detail") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of();
                pending = new LinkedHashMap<>();
                pending.put("name", event.get("summary"));
                pending.put("args", detail.getOrDefault("args", Map.of()));
            } else if ("TOOL_RESULT".equals(type) && pending != null) {
                Map<String, Object> detail = event.get("detail") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of();
                pending.put("result", detail.getOrDefault("result", ""));
                toolLog.add(pending);
                pending = null;
            }
        }
        return toolLog;
    }
}
