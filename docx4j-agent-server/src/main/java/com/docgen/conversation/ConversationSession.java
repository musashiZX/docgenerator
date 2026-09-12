package com.docgen.conversation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One document's active (or archived) chat thread with the AI editor: a
 * sequence of user/assistant turns. Each assistant turn's batch supersedes
 * the previous one — "keep reasoning about the same proposal," not a pile
 * of independent proposals. Approving or starting a new session archives it.
 */
public record ConversationSession(
        String id,
        @JsonProperty("doc_name") String docName,
        ConversationStatus status,
        @JsonProperty("created_at") Instant createdAt,
        List<ConversationTurn> turns
) {

    public ConversationSession withTurn(ConversationTurn turn) {
        List<ConversationTurn> updated = new ArrayList<>(turns);
        updated.add(turn);
        return new ConversationSession(id, docName, status, createdAt, updated);
    }

    public ConversationSession withStatus(ConversationStatus newStatus) {
        return new ConversationSession(id, docName, newStatus, createdAt, turns);
    }

    /** The most recent assistant turn's proposal id — the one currently under review, or null. */
    public String currentProposalId() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            ConversationTurn t = turns.get(i);
            if ("assistant".equals(t.role()) && t.proposalId() != null) {
                return t.proposalId();
            }
        }
        return null;
    }
}
