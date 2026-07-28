package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Remove a body paragraph, or a whole table row/column (when {@code node_type}
 * is {@code table_row} / {@code table_column} and {@code target_id} is any cell
 * in that row/column).
 */
public record DeleteMutation(
        String op,
        @JsonProperty("target_id") String targetId,
        @JsonProperty("node_type") String nodeType
) implements Mutation {

    @JsonIgnore
    public boolean isTableRow() {
        return "table_row".equals(nodeType);
    }

    @JsonIgnore
    public boolean isTableColumn() {
        return "table_column".equals(nodeType);
    }

    @JsonIgnore
    public boolean isParagraph() {
        return nodeType == null || nodeType.isBlank() || "paragraph".equals(nodeType);
    }
}
