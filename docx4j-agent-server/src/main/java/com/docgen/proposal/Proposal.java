package com.docgen.proposal;

import com.docgen.model.MutationBatch;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * One reviewable edit proposal. Persisted as JSON alongside a before.docx
 * snapshot; the working document is only touched on approve.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Proposal(
        String id,
        @JsonProperty("doc_name") String docName,
        ProposalStatus status,
        String source,
        String prompt,
        String model,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("decided_at") Instant decidedAt,
        MutationBatch batch,
        List<BlockDiff> diffs
) {

    public Proposal withStatus(ProposalStatus newStatus, Instant decidedAt) {
        return new Proposal(id, docName, newStatus, source, prompt, model,
                createdAt, decidedAt, batch, diffs);
    }
}
