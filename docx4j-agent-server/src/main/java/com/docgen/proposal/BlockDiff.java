package com.docgen.proposal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Human-reviewable before/after plain text for one targeted block.
 * {@code before} is null for inserts; {@code after} is null for deletes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlockDiff(
        @JsonProperty("target_id") String targetId,
        String op,
        @JsonProperty("before_text") String beforeText,
        @JsonProperty("after_text") String afterText
) {
}
