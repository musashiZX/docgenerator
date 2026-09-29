package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Applies character formatting (bold/italic/underline/font size) to a
 * verbatim substring of one block, and/or paragraph alignment to the whole
 * block. At least one of {@code text} (run formatting) or {@code align}
 * (paragraph alignment) must be meaningfully set. Unlike modify, this never
 * changes the block's plain text — the hash guard is intentionally blind to
 * it (see NodeHashGuard), so formatted ids are reported separately.
 */
public record FormatMutation(
        String op,
        @JsonProperty("target_id") String targetId,
        String text,
        Integer occurrence,
        Boolean bold,
        Boolean italic,
        Boolean underline,
        @JsonProperty("font_size") Integer fontSize,
        String align
) implements Mutation {

    public int occurrenceOrDefault() {
        return occurrence == null ? 0 : occurrence;
    }

    @JsonIgnore
    public boolean hasRunFormatting() {
        return bold != null || italic != null || underline != null || fontSize != null;
    }

    @JsonIgnore
    public boolean hasAlignment() {
        return align != null && !align.isBlank();
    }
}
