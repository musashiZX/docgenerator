package com.docgen.llm;

import com.docgen.config.AppProperties;
import com.docgen.config.LlmProperties;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                new LlmProperties("openai", null, null, null),
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
    void geminiProviderCallsGeminiCompatEndpointWithGeminiKeyAndModel() {
        RestClient.Builder gBuilder = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/openai");
        MockRestServiceServer gServer = MockRestServiceServer.bindTo(gBuilder).build();
        ComplianceClient gemini = new ComplianceClient(
                new AppProperties("docs", null, "openai-key", "gpt-4o-mini", false),
                new LlmProperties("gemini", "gemini-key", "gemini-3.5-flash-lite",
                        "https://generativelanguage.googleapis.com/v1beta/openai"),
                new ObjectMapper(), new MutationValidator(), gBuilder.build());

        gServer.expect(requestTo(
                        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"))
                .andExpect(header("Authorization", "Bearer gemini-key"))
                .andExpect(jsonPath("$.model").value("gemini-3.5-flash-lite"))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_p0",
                           "old_text":"Alpha","occurrence":0,"new_text":"Alpha!"}]}
                        """), MediaType.APPLICATION_JSON));

        ComplianceClient.LlmProposal proposal = gemini.propose("add bang", index, null);

        assertEquals("gemini-3.5-flash-lite", proposal.model());
        assertEquals("Alpha!", ((ModifyMutation) proposal.batch().mutations().get(0)).newText());
        gServer.verify();
    }

    @Test
    void geminiProviderWithoutKeyFailsFast() {
        ComplianceClient noKey = new ComplianceClient(
                new AppProperties("docs", null, "openai-key", null, false),
                new LlmProperties("gemini", "", null, null),
                new ObjectMapper(), new MutationValidator(), RestClient.builder().build());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> noKey.propose("x", index, null));
        assertTrue(ex.getMessage().contains("GEMINI_API_KEY"));
    }

    @Test
    void reportsTokenUsageAndCostForGpt4oMini() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(chatResponseWithUsage("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_p0",
                           "old_text":"Alpha","occurrence":0,"new_text":"Alpha!"}]}
                        """, 1000, 200), MediaType.APPLICATION_JSON));

        ComplianceClient.LlmProposal proposal = client.propose("add bang", index, null);

        assertEquals(1000, proposal.usage().promptTokens());
        assertEquals(200, proposal.usage().completionTokens());
        assertEquals(1200, proposal.usage().totalTokens());
        // gpt-4o-mini: $0.15/1M in, $0.60/1M out -> 1000*0.15e-6 + 200*0.60e-6
        assertEquals(0.00027, proposal.costUsd(), 1e-9);
        server.verify();
    }

    @Test
    void accumulatesUsageAcrossRetryAttempts() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(chatResponseWithUsage("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_nope",
                           "old_text":"x","occurrence":0,"new_text":"y"}]}
                        """, 500, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(chatResponseWithUsage("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_p0",
                           "old_text":"Alpha","occurrence":0,"new_text":"Alpha!"}]}
                        """, 600, 120), MediaType.APPLICATION_JSON));

        ComplianceClient.LlmProposal proposal = client.propose("fix alpha", index, null);

        assertEquals(1100, proposal.usage().promptTokens(), "summed across both attempts");
        assertEquals(220, proposal.usage().completionTokens());
        server.verify();
    }

    @Test
    void batchRunsRequestsConcurrentlyAndMergesResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com");
        // Order-independent: three sub-requests fire concurrently, so they
        // can hit the mock server in any order.
        MockRestServiceServer unorderedServer =
                MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        ComplianceClient batchClient = new ComplianceClient(
                new AppProperties("docs", null, "test-key", "gpt-4o-mini", false),
                new LlmProperties("openai", null, null, null),
                new ObjectMapper(), new MutationValidator(), builder.build());

        unorderedServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("bump the alpha version")))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_p0",
                           "old_text":"Alpha","occurrence":0,"new_text":"Alpha!"}]}
                        """), MediaType.APPLICATION_JSON));
        unorderedServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("bump the bravo version")))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"edit",
                         "mutations":[{"op":"modify","target_id":"dg_p1",
                           "old_text":"Bravo","occurrence":0,"new_text":"Bravo!"}]}
                        """), MediaType.APPLICATION_JSON));
        // Third item declines outright (empty batch) with NO retry needed —
        // the retry-on-decline path itself is already covered by
        // persistentlyInvalidBatchFailsAfterRetries; keeping this item a
        // clean one-shot decline keeps this test isolated to concurrency+merge.
        unorderedServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("this does not exist anywhere")))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"nothing to do","mutations":[]}
                        """), MediaType.APPLICATION_JSON));
        unorderedServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("this does not exist anywhere")))
                .andRespond(withSuccess(chatResponse("""
                        {"schema_version":1,"explanation":"still nothing to do","mutations":[]}
                        """), MediaType.APPLICATION_JSON));

        ComplianceClient.BatchLlmProposal result = batchClient.proposeBatch(
                List.of("bump the alpha version", "bump the bravo version", "this does not exist anywhere"),
                index, null);

        assertEquals(3, result.items().size());
        assertEquals(2, result.combinedBatch().mutations().size(),
                "the declined item contributes nothing, the other two merge");
        assertNotNull(result.items().stream()
                        .filter(i -> i.request().equals("this does not exist anywhere")).findFirst().orElseThrow().error(),
                "the declined item must record its error rather than silently vanishing");
        assertEquals("Alpha!", ((ModifyMutation) result.combinedBatch().mutations().stream()
                .filter(m -> ((ModifyMutation) m).targetId().equals("dg_p0")).findFirst().orElseThrow()).newText());
        assertEquals("Bravo!", ((ModifyMutation) result.combinedBatch().mutations().stream()
                .filter(m -> ((ModifyMutation) m).targetId().equals("dg_p1")).findFirst().orElseThrow()).newText());
        unorderedServer.verify();
    }

    @Test
    void batchIsMeasurablyFasterThanSequentialCallsWithArtificialLatency() throws Exception {
        // Proves the concurrency claim directly: three sub-requests that each
        // take ~300ms complete as a BATCH in well under 3x that time, because
        // they run in parallel rather than one after another.
        AppProperties props = new AppProperties("docs", null, "test-key", "gpt-4o-mini", false);
        LlmProperties llmProps = new LlmProperties("openai", null, null, null);
        MutationValidator validator = new MutationValidator();
        ObjectMapper mapper = new ObjectMapper();

        RestClient slowClient = RestClient.builder()
                .baseUrl("https://api.openai.com")
                .requestInterceptor((req, body, exec) -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    String json = """
                            {"choices":[{"message":{"role":"assistant","content":"{\\"schema_version\\":1,\\"explanation\\":\\"e\\",\\"mutations\\":[]}"}}]}
                            """;
                    return new org.springframework.mock.http.client.MockClientHttpResponse(
                            json.getBytes(java.nio.charset.StandardCharsets.UTF_8), org.springframework.http.HttpStatus.OK);
                })
                .build();
        ComplianceClient slowBatchClient = new ComplianceClient(props, llmProps, mapper, validator, slowClient);

        long start = System.currentTimeMillis();
        slowBatchClient.proposeBatch(List.of("req1", "req2", "req3"), index, null);
        long elapsedMs = System.currentTimeMillis() - start;

        assertTrue(elapsedMs < 900,
                "3 concurrent 300ms calls should finish well under 900ms (sequential would be ~900ms+); took " + elapsedMs + "ms");
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
                new LlmProperties("openai", null, null, null),
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

    private static String chatResponseWithUsage(String batchJson, int promptTokens, int completionTokens) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String escaped = mapper.writeValueAsString(batchJson.trim());
            return """
                    {"choices":[{"message":{"role":"assistant","content":%s}}],
                     "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
                    """.formatted(escaped, promptTokens, completionTokens, promptTokens + completionTokens);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BlockDescriptor block(String id, String type, String text) {
        return new BlockDescriptor(id, type, text, text.length(), 1, 0, "Normal", null, null, null);
    }
}
