package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record StructuralIndex(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("block_count") int blockCount,
        List<BlockDescriptor> blocks
) {
    public StructuralIndex(String documentId, List<BlockDescriptor> blocks) {
        this(documentId, blocks.size(), blocks);
    }
}
