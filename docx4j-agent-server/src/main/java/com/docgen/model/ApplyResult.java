package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/** Outcome of applying a validated mutation batch. */
public record ApplyResult(
        @JsonProperty("applied_count") int appliedCount,
        @JsonProperty("changed_ids") Set<String> changedIds,
        @JsonProperty("created_ids") Set<String> createdIds
) {
}
