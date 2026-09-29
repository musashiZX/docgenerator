package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Envelope for all mutations proposed in one edit request. */
public record MutationBatch(
        @JsonProperty("schema_version") int schemaVersion,
        String explanation,
        List<Mutation> mutations
) {
}
