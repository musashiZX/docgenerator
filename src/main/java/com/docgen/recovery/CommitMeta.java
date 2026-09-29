package com.docgen.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommitMeta(
        @JsonProperty("commit_id") String commitId,
        @JsonProperty("doc_name") String docName,
        String message,
        @JsonProperty("parent_commit_id") String parentCommitId,
        @JsonProperty("created_at") Instant createdAt,
        String author
) {
}
