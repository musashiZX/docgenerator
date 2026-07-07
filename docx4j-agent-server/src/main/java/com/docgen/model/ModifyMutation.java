package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Replace {@code old_text} (at the given occurrence) with {@code new_text}
 * inside the single block addressed by {@code target_id}.
 * {@code old_text} is an optimistic lock, never a document-wide search key.
 */
public record ModifyMutation(
        String op,
        @JsonProperty("target_id") String targetId,
        @JsonProperty("old_text") String oldText,
        Integer occurrence,
        @JsonProperty("new_text") String newText
) implements Mutation {

    public int occurrenceOrDefault() {
        return occurrence == null ? 0 : occurrence;
    }
}
