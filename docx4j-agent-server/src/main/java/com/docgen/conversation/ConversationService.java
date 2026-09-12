package com.docgen.conversation;

import com.docgen.document.DocumentIndexService;
import com.docgen.llm.ComplianceClient;
import com.docgen.model.ApplyResult;
import com.docgen.model.DeleteMutation;
import com.docgen.model.FocusBlock;
import com.docgen.model.FormatMutation;
import com.docgen.model.InsertMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationValidator;
import com.docgen.proposal.Proposal;
import com.docgen.proposal.ProposalService;
import com.docgen.proposal.ProposalStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Chat refines the same proposal" workflow: one active session per document.
 * Each user message regenerates the batch considering the whole conversation
 * so far and supersedes the session's previous (still-pending) proposal —
 * rejecting it rather than leaving it orphaned — so there is always at most
 * one live proposal per session. Approve/reject/history all delegate to the
 * existing, tested ProposalService; this layer only adds the chat memory and
 * the one-active-session-per-doc bookkeeping on top.
 */
@Service
public class ConversationService {

    private final ConversationSessionStore store;
    private final ComplianceClient complianceClient;
    private final ProposalService proposalService;
    private final DocumentIndexService documentIndexService;

    public ConversationService(
            ConversationSessionStore store,
            ComplianceClient complianceClient,
            ProposalService proposalService,
            DocumentIndexService documentIndexService) {
        this.store = store;
        this.complianceClient = complianceClient;
        this.proposalService = proposalService;
        this.documentIndexService = documentIndexService;
    }

    public ConversationSession get(String docName) {
        return getOrCreateActive(docName);
    }

    public List<ConversationSession> list(String docName) {
        return store.list(docName);
    }

    /** Archives the current active session (if any) and starts a fresh, empty one. */
    public ConversationSession startNew(String docName) {
        store.findActive(docName).ifPresent(this::archiveAndSupersede);
        return createSession(docName);
    }

    /**
     * Append a user message, regenerate the batch with full conversation
     * context, and supersede the session's previous pending proposal.
     */
    public ConversationSession sendMessage(
            String docName, String message, String modelOverride,
            List<FocusBlock> focusBlocks, String selectedText) throws Exception {

        ConversationSession session = getOrCreateActive(docName);
        rejectIfPending(session.currentProposalId());

        session = session.withTurn(new ConversationTurn("user", message, null, Instant.now()));
        List<ComplianceClient.ConversationTurnContext> priorContext = toLlmContext(session.turns());

        StructuralIndex index = documentIndexService.buildIndex(docName);
        // A failed attempt (validation rejected the batch, or the model
        // declined) is still a normal chat turn, not an HTTP error — the
        // conversation stays open with a plain-language explanation so the
        // user can clarify and keep going, instead of a raw error toast.
        try {
            ComplianceClient.LlmProposal llm = complianceClient.propose(
                    message, index, modelOverride, focusBlocks, selectedText, priorContext);
            Proposal proposal = proposalService.propose(
                    docName, llm.batch(), "llm", message, llm.model(), llm.usage(), llm.costUsd());
            String assistantMessage = summarize(llm.batch());
            session = session.withTurn(new ConversationTurn("assistant", assistantMessage, proposal.id(), Instant.now()));
        } catch (MutationValidator.MutationValidationException e) {
            session = session.withTurn(new ConversationTurn("assistant", explain(e), null, Instant.now()));
        } catch (ComplianceClient.LlmDeclinedException e) {
            session = session.withTurn(new ConversationTurn("assistant", explain(e), null, Instant.now()));
        }

        store.save(session);
        return session;
    }

    private static String explain(ComplianceClient.LlmDeclinedException e) {
        return "I can't make that change: " + e.getMessage()
                + "\n\nTry rephrasing, pointing me at the exact text or field, or telling me more about "
                + "what you're looking for — I'll use this conversation to try again.";
    }

    private static String explain(MutationValidator.MutationValidationException e) {
        String details = e.errors().stream()
                .map(err -> "- " + err.message())
                .collect(Collectors.joining("\n"));
        return "I generated an edit, but it didn't pass validation:\n" + details
                + "\n\nCould you clarify what you'd like, or rephrase the request? "
                + "I'll use this conversation's context to try again.";
    }

    /** Approve the session's current proposal; the session is then done (archived). */
    public ApplyResult approve(String docName) throws Exception {
        ConversationSession session = getOrCreateActive(docName);
        String proposalId = session.currentProposalId();
        if (proposalId == null) {
            throw new IllegalArgumentException("No pending proposal in the active session for " + docName);
        }
        ApplyResult result = proposalService.approve(proposalId);
        store.save(session.withStatus(ConversationStatus.ARCHIVED));
        return result;
    }

    /** Reject the session's current proposal; the session stays active for further chat. */
    public ConversationSession reject(String docName) {
        ConversationSession session = getOrCreateActive(docName);
        rejectIfPending(session.currentProposalId());
        return session;
    }

    private ConversationSession getOrCreateActive(String docName) {
        return store.findActive(docName).orElseGet(() -> createSession(docName));
    }

    private ConversationSession createSession(String docName) {
        ConversationSession session = new ConversationSession(
                UUID.randomUUID().toString(), docName, ConversationStatus.ACTIVE, Instant.now(), List.of());
        store.save(session);
        return session;
    }

    private void archiveAndSupersede(ConversationSession session) {
        rejectIfPending(session.currentProposalId());
        store.save(session.withStatus(ConversationStatus.ARCHIVED));
    }

    private void rejectIfPending(String proposalId) {
        if (proposalId == null) {
            return;
        }
        tryGetProposal(proposalId)
                .filter(p -> p.status() == ProposalStatus.PENDING)
                .ifPresent(p -> proposalService.reject(proposalId));
    }

    private Optional<Proposal> tryGetProposal(String id) {
        try {
            return Optional.of(proposalService.get(id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Every turn except the trailing (just-appended, "current request") user turn. */
    private static List<ComplianceClient.ConversationTurnContext> toLlmContext(List<ConversationTurn> turns) {
        List<ComplianceClient.ConversationTurnContext> context = new ArrayList<>();
        for (int i = 0; i < turns.size() - 1; i++) {
            ConversationTurn t = turns.get(i);
            context.add(new ComplianceClient.ConversationTurnContext(t.role(), t.message()));
        }
        return context;
    }

    private static String summarize(MutationBatch batch) {
        String explanation = batch.explanation() == null || batch.explanation().isBlank()
                ? "Proposed " + batch.mutations().size() + " change(s)."
                : batch.explanation();
        StringBuilder sb = new StringBuilder(explanation);
        for (Mutation mutation : batch.mutations()) {
            sb.append('\n').append("- ").append(describe(mutation));
        }
        return sb.toString();
    }

    private static String describe(Mutation mutation) {
        return switch (mutation) {
            case ModifyMutation m -> "modify " + m.targetId() + ": \"" + m.oldText() + "\" -> \"" + m.newText() + "\"";
            case InsertMutation m -> "insert " + m.nodeType() + " " + m.position() + " " + m.anchorId();
            case DeleteMutation m -> "delete " + m.nodeType() + " " + m.targetId();
            case FormatMutation m -> "format " + m.targetId();
        };
    }
}
