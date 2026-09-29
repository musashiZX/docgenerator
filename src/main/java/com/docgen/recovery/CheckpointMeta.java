package com.docgen.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointMeta(
        @JsonProperty("checkpoint_id") String checkpointId,
        @JsonProperty("doc_name") String docName,
        @JsonProperty("proposal_id") String proposalId,
        @JsonProperty("change_summary") String changeSummary,
        @JsonProperty("created_at") Instant createdAt
) {
}
