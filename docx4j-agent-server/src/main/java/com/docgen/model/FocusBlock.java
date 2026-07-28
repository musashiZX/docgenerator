package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One block the user focused for LLM context (preview or blocks table). */
public record FocusBlock(
        @JsonProperty("target_id") String targetId,
        String text
) {
}
