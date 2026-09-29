package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Insert a body paragraph, table row, or table column relative to an anchor.
 * For {@code table_row} / {@code table_column}, {@code anchor_id} must be a
 * table cell; the engine clones that row/column. Optional {@code cells} sets
 * the new texts (column count for rows, row count for columns).
 */
public record InsertMutation(
        String op,
        @JsonProperty("anchor_id") String anchorId,
        String position,
        @JsonProperty("node_type") String nodeType,
        String text,
        String style,
        List<String> cells
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
