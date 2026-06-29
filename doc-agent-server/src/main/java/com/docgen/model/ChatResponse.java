package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String reply,
        @JsonProperty("tool_calls") List<Map<String, Object>> toolCalls,
        @JsonProperty("preview_html") String previewHtml,
        @JsonProperty("preview_version") int previewVersion,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("round_summary") Map<String, Object> roundSummary
) {}
