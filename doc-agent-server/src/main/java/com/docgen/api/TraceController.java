package com.docgen.api;

import com.docgen.trace.TraceQueryService;
import com.docgen.trace.TraceWriter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceWriter traceWriter;
    private final TraceQueryService traceQueryService;

    public TraceController(TraceWriter traceWriter, TraceQueryService traceQueryService) {
        this.traceWriter = traceWriter;
        this.traceQueryService = traceQueryService;
    }

    @GetMapping
    public Map<String, Object> listRecent() throws IOException {
        if (!traceWriter.enabled()) {
            return Map.of("enabled", false, "traces", List.of());
        }
        Path root = traceWriter.traceRoot();
        List<Map<String, Object>> traces = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .limit(50)
                    .forEach(dir -> {
                        try {
                            traces.add(traceQueryService.loadMeta(dir));
                        } catch (IOException ex) {
                            traces.add(Map.of("folder", dir.getFileName().toString()));
                        }
                    });
        }
        return Map.of("enabled", true, "trace_root", root.toString(), "traces", traces);
    }

    @GetMapping("/{traceId}/summary")
    public Map<String, Object> getSummary(@PathVariable String traceId) throws IOException {
        return traceQueryService.buildRoundSummary(traceId);
    }

    @GetMapping("/{traceId}")
    public Map<String, Object> getTrace(@PathVariable String traceId) throws IOException {
        Path match = traceQueryService.resolveTraceDir(traceId);
        Map<String, Object> summary = traceQueryService.loadMeta(match);
        Path ops = match.resolve("operations.jsonl");
        if (Files.exists(ops)) {
            summary = new java.util.LinkedHashMap<>(summary);
            summary.put("operations", Files.readString(ops));
        }
        return summary;
    }
}
