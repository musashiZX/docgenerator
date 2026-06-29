package com.docgen.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds user-facing summaries from agent tool logs (reused by chat API and trace endpoints). */
public final class RoundSummaryBuilder {

    private RoundSummaryBuilder() {}

    public static Map<String, Object> fromToolLog(
            String traceId,
            String documentName,
            List<Map<String, Object>> toolLog) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map<String, Object> entry : toolLog) {
            String name = String.valueOf(entry.get("name"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = entry.get("args") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();
            String result = String.valueOf(entry.getOrDefault("result", ""));
            if ("read_document".equals(name) || "read_table".equals(name)) {
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("tool", name);
            line.put("description", describeTool(name, args));
            line.put("result", result);
            changes.add(line);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("trace_id", traceId);
        summary.put("document", documentName);
        summary.put("change_count", changes.size());
        summary.put("changes", changes);
        summary.put("document_saved", !toolLog.isEmpty());
        summary.put("has_snapshots", !toolLog.isEmpty());
        summary.put("summary_text", buildSummaryText(changes));
        return summary;
    }

    public static String buildSummaryText(List<Map<String, Object>> changes) {
        if (changes.isEmpty()) {
            return "No document edits in this round.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("• ").append(changes.get(i).get("description"));
        }
        return sb.toString();
    }

    public static String describeTool(String name, Map<String, Object> args) {
        return switch (name) {
            case "replace_text" -> "Replace \"%s\" → \"%s\"".formatted(
                    str(args.get("find")), str(args.get("replace")));
            case "set_paragraph" -> "Rewrite paragraph %s".formatted(str(args.get("index")));
            case "insert_paragraph" -> "Insert paragraph at index %s".formatted(str(args.get("index")));
            case "append_paragraph" -> "Append paragraph";
            case "delete_paragraph" -> "Delete paragraph %s".formatted(str(args.get("index")));
            case "set_table_cell" -> "Update table[%s] cell [%s][%s]".formatted(
                    str(args.get("table_index")), str(args.get("row")), str(args.get("col")));
            default -> name;
        };
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
