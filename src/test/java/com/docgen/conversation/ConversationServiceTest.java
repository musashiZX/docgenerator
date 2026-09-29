package com.docgen.conversation;

import com.docgen.config.AppProperties;
import com.docgen.document.DocumentIndexService;
import com.docgen.document.DocumentLoader;
import com.docgen.llm.ComplianceClient;
import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationValidator;
import com.docgen.proposal.ProposalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers the reported UX gap: a failed proposal attempt (validation
 * rejected, or the model declined) must land as a normal, explanatory chat
 * turn — not an exception that kills the conversation with a raw error.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private ComplianceClient complianceClient;
    @Mock
    private ProposalService proposalService;
    @Mock
    private DocumentIndexService documentIndexService;

    private ConversationService service;

    @BeforeEach
    void setUp() throws Exception {
        DocumentLoader loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
        ConversationSessionStore store = new ConversationSessionStore(loader, new ObjectMapper());
        service = new ConversationService(store, complianceClient, proposalService, documentIndexService);
    }

    @Test
    void validationFailureBecomesAnExplanatoryAssistantTurnNotAnException() throws Exception {
        when(documentIndexService.buildIndex(anyString()))
                .thenReturn(new StructuralIndex("doc.docx", List.of()));
        List<MutationValidator.ValidationError> errors = List.of(
                new MutationValidator.ValidationError(1, "DUPLICATE_TABLE_OP",
                        "At most one table_row insert per row+position per batch for dg_tbl0_r24_c1"));
        when(complianceClient.propose(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new MutationValidator.MutationValidationException(errors));

        ConversationSession session = service.sendMessage(
                "doc.docx", "Add 2 more rows: E224 Netherlands, E225 Spain", null, List.of(), null);

        assertEquals(2, session.turns().size(), "user turn + explanatory assistant turn, no exception");
        assertEquals("user", session.turns().get(0).role());
        ConversationTurn assistantTurn = session.turns().get(1);
        assertEquals("assistant", assistantTurn.role());
        assertNull(assistantTurn.proposalId(), "no proposal was actually created");
        assertTrue(assistantTurn.message().contains("DUPLICATE_TABLE_OP")
                        || assistantTurn.message().contains("row+position"),
                "explanation must surface the actual validation problem: " + assistantTurn.message());
        assertTrue(assistantTurn.message().toLowerCase().contains("clarify")
                        || assistantTurn.message().toLowerCase().contains("rephrase"),
                "explanation must invite the user to continue the conversation: " + assistantTurn.message());
        // Session must stay ACTIVE and open for a follow-up message, not be
        // torn down by the failure.
        assertEquals(ConversationStatus.ACTIVE, service.get("doc.docx").status());
    }

    @Test
    void llmDeclineBecomesAnExplanatoryAssistantTurnNotAnException() throws Exception {
        when(documentIndexService.buildIndex(anyString()))
                .thenReturn(new StructuralIndex("doc.docx", List.of()));
        when(complianceClient.propose(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new ComplianceClient.LlmDeclinedException("No matching field found for that request."));

        ConversationSession session = service.sendMessage(
                "doc.docx", "Change the carbon footprint rating", null, List.of(), null);

        ConversationTurn assistantTurn = session.turns().get(1);
        assertEquals("assistant", assistantTurn.role());
        assertNull(assistantTurn.proposalId());
        assertTrue(assistantTurn.message().contains("No matching field found for that request."));
    }

    @Test
    void successfulProposalStillAttachesAProposalIdAsBefore() throws Exception {
        when(documentIndexService.buildIndex(anyString()))
                .thenReturn(new StructuralIndex("doc.docx", List.of()));
        MutationBatch batch = new MutationBatch(1, "bump version",
                List.of(new ModifyMutation("modify", "dg_p0", "1.0", 0, "1.1")));
        when(complianceClient.propose(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new ComplianceClient.LlmProposal(batch, "gpt-4o-mini"));
        when(proposalService.propose(
                        anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new com.docgen.proposal.Proposal(
                        "prop-1", "doc.docx", com.docgen.proposal.ProposalStatus.PENDING,
                        "llm", "bump version to 1.1", "gpt-4o-mini", java.time.Instant.now(), null, batch, List.of()));

        ConversationSession session = service.sendMessage(
                "doc.docx", "bump version to 1.1", null, List.of(), null);

        ConversationTurn assistantTurn = session.turns().get(1);
        assertEquals("prop-1", assistantTurn.proposalId());
    }
}
