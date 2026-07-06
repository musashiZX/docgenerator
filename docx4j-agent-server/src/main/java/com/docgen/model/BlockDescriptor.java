package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlockDescriptor(
        @JsonProperty("target_id") String targetId,
        String type,
        String text,
        @JsonProperty("char_count") int charCount,
        @JsonProperty("run_count") int runCount,
        int ordinal,
        String style,
        @JsonProperty("table_index") Integer tableIndex,
        Integer row,
        Integer col
) {
}
