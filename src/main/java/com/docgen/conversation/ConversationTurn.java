package com.docgen.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One message in a conversation session. Assistant turns carry the id of the
 * proposal that message produced (the diff the user is reviewing).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationTurn(
        String role, // "user" | "assistant"
        String message,
        @JsonProperty("proposal_id") String proposalId,
        @JsonProperty("created_at") Instant createdAt
) {
}
