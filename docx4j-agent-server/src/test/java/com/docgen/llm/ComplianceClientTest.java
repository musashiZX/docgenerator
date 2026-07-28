package com.docgen.llm;

import com.docgen.config.AppProperties;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ComplianceClientTest {

    private MockRestServiceServer server;
    private ComplianceClient client;

    private final StructuralIndex index = new StructuralIndex("doc.docx", List.of(
            block("dg_p0", "paragraph", "Alpha"),
            block("dg_p1", "paragraph", "Bravo")));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ComplianceClient(
                new AppProperties("docs", null, "test-key", "gpt-4o-mini", false),
                new ObjectMapper(),
                new MutationValidator(),
                builder.build());
    }

    @Test
    void validFirstResponseIsReturned() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_p0",
                           "old_text":"Alpha","occurrence":0,"new_text":"Alpha!"}]}
                        """), MediaType.APPLICATION_JSON));

        ComplianceClient.LlmProposal proposal = client.propose("add bang", index, null);

        MutationBatch batch = proposal.batch();
        assertEquals(1, batch.mutations().size());
        assertEquals("Alpha!", ((ModifyMutation) batch.mutations().get(0)).newText());
        assertEquals("gpt-4o-mini", proposal.model());
        server.verify();
    }

    @Test
    void validationErrorTriggersRetryWithErrorFeedback() {
        // Attempt 1: unknown target -> validator rejects -> client retries.
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_nope",
                           "old_text":"x","occurrence":0,"new_text":"y"}]}
                        """), MediaType.APPLICATION_JSON));
        // Attempt 2: the retry request must feed the validation error back.
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UNKNOWN_TARGET")))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_p0",
                           "old_text":"Alpha","occurrence":0,"new_text":"Alpha!"}]}
                        """), MediaType.APPLICATION_JSON));

        ComplianceClient.LlmProposal proposal = client.propose("add bang", index, null);

        assertEquals("dg_p0", ((ModifyMutation) proposal.batch().mutations().get(0)).targetId());
        server.verify();
    }

    @Test
    void persistentlyInvalidBatchFailsAfterRetries() {
        for (int i = 0; i < 2; i++) { // initial + 1 retry
            server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                    .andRespond(withSuccess(chatResponse("""
                            {"schema_version":1,"explanation":"edit",
                             "mutations":[{"op":"modify","target_id":"dg_nope",
                               "old_text":"x","occurrence":0,"new_text":"y"}]}
                            """), MediaType.APPLICATION_JSON));
        }

        MutationValidator.MutationValidationException ex = assertThrows(
                MutationValidator.MutationValidationException.class,
                () -> client.propose("Harmonize the alpha paragraph", index, null));
        assertTrue(ex.errors().stream().anyMatch(e -> e.code().equals("UNKNOWN_TARGET")));
        server.verify();
    }

    @Test
    void bulkReplaceSkipsLlmForTableFindReplace() {
        StructuralIndex tableIndex = new StructuralIndex("doc.docx", List.of(
                block("dg_tbl0_r1_c3", "table_cell", "Food Safety"),
                block("dg_tbl0_r3_c3", "table_cell", "Food Safety"),
                block("dg_tbl2_r5_c1", "table_cell", "TRN-01, TRN-02")));

        ComplianceClient.LlmProposal proposal = client.propose(
                "Change all the 'Food Safety' in the tables to 'Food Safe'", tableIndex, null);

        assertEquals(ComplianceClient.BULK_REPLACE_MODEL, proposal.model());
        assertEquals(2, proposal.batch().mutations().size());
        server.verify();
    }

    @Test
    void alignsInvalidLlmMutationsOnFirstAttempt() {
        StructuralIndex tableIndex = new StructuralIndex("doc.docx", List.of(
                block("dg_p0", "paragraph", "Food Safety"),
                block("dg_tbl4_r2_c2", "table_cell", "TRN-01, TRN-02, TRN-03")));

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"Food Safety rename",
                         "mutations":[
                           {"op":"modify","target_id":"dg_p0",
                             "old_text":"Food Safety","occurrence":0,"new_text":"Food Safe"},
                           {"op":"modify","target_id":"dg_tbl4_r2_c2",
                             "old_text":"Food Safety","occurrence":0,"new_text":"Food Safe"}
                         ]}
                        """), MediaType.APPLICATION_JSON));

        ComplianceClient.LlmProposal proposal = client.propose(
                "Please update every 'Food Safety' label to read 'Food Safe'", tableIndex, null);

        assertEquals(1, proposal.batch().mutations().size());
        assertEquals("dg_p0", ((ModifyMutation) proposal.batch().mutations().get(0)).targetId());
        server.verify();
    }

    @Test
    void emptyMutationsSurfacesModelExplanationAfterRetry() {
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                    .andRespond(withSuccess(chatResponse("""
                            {"schema_version":1,"explanation":"The request needs table row inserts, which are unsupported.",
                             "mutations":[]}
                            """), MediaType.APPLICATION_JSON));
        }

        ComplianceClient.LlmDeclinedException ex = assertThrows(
                ComplianceClient.LlmDeclinedException.class,
                () -> client.propose("impossible", index, null));
        assertTrue(ex.getMessage().contains("unsupported"));
        server.verify();
    }

    @Test
    void emptyBatchTriggersRetryThenSucceeds() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"Cannot do it","mutations":[]}
                        """), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RECIPES")))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"ok",
                         "mutations":[{"op":"modify","target_id":"dg_p0",
                           "old_text":"Alpha","occurrence":0,"new_text":"Alpha!"}]}
                        """), MediaType.APPLICATION_JSON));

        ComplianceClient.LlmProposal proposal = client.propose("fix alpha", index, null);
        assertEquals("Alpha!", ((ModifyMutation) proposal.batch().mutations().get(0)).newText());
        server.verify();
    }

    @Test
    void missingApiKeyFailsFast() {
        ComplianceClient noKey = new ComplianceClient(
                new AppProperties("docs", null, "", null, false),
                new ObjectMapper(), new MutationValidator(),
                RestClient.builder().build());
        assertThrows(IllegalStateException.class, () -> noKey.propose("x", index, null));
    }

    /** Wraps batch JSON the way Chat Completions returns it (content is a string). */
    private static String chatResponse(String batchJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String escaped = mapper.writeValueAsString(batchJson.trim());
            return """
                    {"choices":[{"message":{"role":"assistant","content":%s}}]}
                    """.formatted(escaped);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BlockDescriptor block(String id, String type, String text) {
        return new BlockDescriptor(id, type, text, text.length(), 1, 0, "Normal", null, null, null);
    }
}
