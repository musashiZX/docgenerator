package com.docgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Outcome of applying a validated mutation batch. formattedIds is reported
 * separately from changedIds because formatting never changes block text —
 * NodeHashGuard (text-hash based) never sees it.
 */
public record ApplyResult(
        @JsonProperty("applied_count") int appliedCount,
        @JsonProperty("changed_ids") Set<String> changedIds,
        @JsonProperty("created_ids") Set<String> createdIds,
        @JsonProperty("formatted_ids") Set<String> formattedIds
) {
}
