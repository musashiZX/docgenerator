package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Insert one paragraph before/after an existing paragraph anchor.
 * Stage 4 scope: body paragraphs only (not table rows/cells).
 */
public record InsertMutation(
        String op,
        @JsonProperty("anchor_id") String anchorId,
        String position,
        @JsonProperty("node_type") String nodeType,
        String text,
        String style
) implements Mutation {
}
