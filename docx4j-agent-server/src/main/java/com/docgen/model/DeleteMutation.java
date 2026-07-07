package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Remove one body paragraph addressed by {@code target_id}.
 * Stage 4 scope: table cells and table rows cannot be deleted.
 */
public record DeleteMutation(
        String op,
        @JsonProperty("target_id") String targetId
) implements Mutation {
}
