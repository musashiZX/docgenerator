package com.docgen.llm;

import com.docgen.config.AppProperties;
import com.docgen.config.LlmProperties;
import com.docgen.model.FocusBlock;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationBatchAligner;
import com.docgen.mutation.MutationBatchSalvager;
import com.docgen.mutation.MutationValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a natural-language request + structural index into a validated
 * {@link MutationBatch} via an OpenAI-compatible chat/completions endpoint
 * (OpenAI, or Gemini's OpenAI-compat layer — see {@link LlmProperties}).
 * Validation failures are fed back to the model for up to
 * {@value #MAX_RETRIES} repair attempts.
 */
@Component
public class ComplianceClient {

    private static final Logger log = LoggerFactory.getLogger(ComplianceClient.class);
    private static final int MAX_RETRIES = 1;
    public static final String BULK_REPLACE_MODEL = "bulk-replace";
    private static final String OPENAI_BASE_URL = "https://api.openai.com";

    private final AppProperties properties;
    private final LlmProperties llm;
    private final ObjectMapper mapper;
    private final MutationValidator validator;
    private final RestClient restClient;
    private final String chatPath;

    @org.springframework.beans.factory.annotation.Autowired
    public ComplianceClient(AppProperties properties, LlmProperties llm,
                            ObjectMapper mapper, MutationValidator validator) {
        this(properties, llm, mapper, validator,
                RestClient.builder()
                        .baseUrl(llm.isGemini() ? llm.geminiBaseUrl() : OPENAI_BASE_URL)
                        .build());
    }

    ComplianceClient(AppProperties properties, LlmProperties llm, ObjectMapper mapper,
                     MutationValidator validator, RestClient restClient) {
        this.properties = properties;
        this.llm = llm;
        this.mapper = mapper;
        this.validator = validator;
        this.restClient = restClient;
        // OpenAI: {base}/v1/chat/completions. Gemini OpenAI-compat base already
        // ends in /v1beta/openai, so just /chat/completions.
        this.chatPath = llm.isGemini() ? "/chat/completions" : "/v1/chat/completions";
    }

    /** Result plus the model's raw explanation for the UI, and cost-monitoring data. */
    public record LlmProposal(MutationBatch batch, String model, TokenUsage usage, Double costUsd) {
        public LlmProposal(MutationBatch batch, String model) {
            this(batch, model, TokenUsage.ZERO, 0.0);
        }
    }

    private record ChatResult(String content, TokenUsage usage) {
    }

    /** One prior chat message fed back to the model so it "remembers" the conversation so far. */
    public record ConversationTurnContext(String role, String content) {
    }

    /** One request's own outcome within a {@link #proposeBatch} call. */
    public record BatchItemResult(
            String request, MutationBatch batch, TokenUsage usage, Double costUsd, String error) {
    }

    /** Every sub-request's own result, plus everything merged into one batch. */
    public record BatchLlmProposal(
            List<BatchItemResult> items, MutationBatch combinedBatch, String model,
            TokenUsage totalUsage, Double totalCost) {
    }

    // Unbounded virtual-thread-per-task pool: this fan-out is pure I/O wait
    // (blocking HTTP calls to the LLM provider), so virtual threads let N
    // requests run genuinely concurrently without tying up N platform
    // threads — wall-clock time for the whole batch is bounded by the
    // SLOWEST single call, not the sum of all of them.
    private static final ExecutorService BATCH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Runs N independent edit requests concurrently against the SAME
     * document snapshot (one LLM call each, in parallel — not combined into
     * one mega-prompt, which would degrade accuracy as the batch grows),
     * then merges whatever came back into one combined batch. Each item is
     * validated independently first (via the normal {@link #propose} retry
     * path); the caller still needs to validate the COMBINED batch, since
     * merging can introduce new conflicts (e.g. two items touching the same
     * block) that didn't exist when each was checked alone.
     */
    public BatchLlmProposal proposeBatch(List<String> requests, StructuralIndex index, String modelOverride) {
        String model = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride
                : llm.isGemini() ? llm.geminiModel() : properties.defaultModel();

        List<CompletableFuture<BatchItemResult>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> {
                    try {
                        LlmProposal p = propose(request, index, modelOverride);
                        return new BatchItemResult(request, p.batch(), p.usage(), p.costUsd(), null);
                    } catch (Exception e) {
                        return new BatchItemResult(request, null, TokenUsage.ZERO, 0.0, e.getMessage());
                    }
                }, BATCH_EXECUTOR))
                .toList();

        List<BatchItemResult> items = futures.stream().map(CompletableFuture::join).toList();

        List<Mutation> combined = new ArrayList<>();
        TokenUsage totalUsage = TokenUsage.ZERO;
        double totalCost = 0.0;
        for (BatchItemResult item : items) {
            if (item.batch() != null && item.batch().mutations() != null) {
                combined.addAll(item.batch().mutations());
            }
            totalUsage = totalUsage.plus(item.usage());
            if (item.costUsd() != null) {
                totalCost += item.costUsd();
            }
        }
        MutationBatch combinedBatch = new MutationBatch(1, "Batch of " + requests.size() + " request(s)", combined);
        return new BatchLlmProposal(items, combinedBatch, model, totalUsage, totalCost);
    }

    public LlmProposal propose(String request, StructuralIndex index, String modelOverride) {
        return propose(request, index, modelOverride, List.of(), null);
    }

    public LlmProposal propose(String request, StructuralIndex index,
                               String modelOverride, String selectedText) {
        return propose(request, index, modelOverride, List.of(), selectedText);
    }

    public LlmProposal propose(String request, StructuralIndex index,
                               String modelOverride, List<FocusBlock> focusBlocks,
                               String legacySelectedText) {
        return propose(request, index, modelOverride, focusBlocks, legacySelectedText, List.of());
    }

    public LlmProposal propose(String request, StructuralIndex index,
                               String modelOverride, List<FocusBlock> focusBlocks,
                               String legacySelectedText, List<ConversationTurnContext> priorTurns) {
        boolean gemini = llm.isGemini();
        String apiKey = gemini ? llm.geminiApiKey() : properties.openaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(gemini
                    ? "GEMINI_API_KEY is not set. Add it to the repo-root .env file "
                            + "(LLM_PROVIDER=gemini is active)."
                    : "OPENAI_API_KEY is not set. Add it to the repo-root .env file.");
        }
        String model = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride
                : gemini ? llm.geminiModel() : properties.defaultModel();

        Optional<MutationBatch> bulk = BulkReplacePlanner.tryPlan(request, index);
        if (bulk.isPresent() && !bulk.get().mutations().isEmpty()) {
            List<MutationValidator.ValidationError> bulkErrors =
                    validator.validate(bulk.get(), index);
            if (bulkErrors.isEmpty()) {
                log.info("Bulk replace produced {} mutation(s) without LLM",
                        bulk.get().mutations().size());
                return new LlmProposal(bulk.get(), BULK_REPLACE_MODEL);
            }
            log.warn("Bulk replace plan invalid, falling back to LLM: {}", bulkErrors);
        }

        StructuralIndex promptIndex = IndexScoper.scopeForLlm(request, index);
        if (promptIndex.blocks().size() < index.blocks().size()) {
            log.info("Scoped LLM index {} -> {} blocks",
                    index.blocks().size(), promptIndex.blocks().size());
        }

        ArrayNode messages = mapper.createArrayNode();
        String systemPrompt = gemini
                ? CompliancePrompts.SYSTEM + "\n\n" + CompliancePrompts.SCHEMA_HINT
                : CompliancePrompts.SYSTEM;
        messages.add(message("system", systemPrompt));
        if (priorTurns != null) {
            for (ConversationTurnContext turn : priorTurns) {
                messages.add(message(turn.role(), turn.content()));
            }
        }
        messages.add(message("user",
                CompliancePrompts.userMessage(request, promptIndex, focusBlocks, legacySelectedText)));

        List<MutationValidator.ValidationError> lastErrors = null;
        boolean retriedEmpty = false;
        TokenUsage totalUsage = TokenUsage.ZERO;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            ChatResult chatResult = chat(apiKey, model, messages);
            String content = chatResult.content();
            totalUsage = totalUsage.plus(chatResult.usage());
            MutationBatch batch = TableRowAnchorRepair.repair(
                    request, MutationBatchAligner.alignToIndex(parseBatch(content), index), index);
            if (batch.explanation() != null
                    && batch.explanation().contains("Repaired table_row anchor")) {
                log.info("Repaired table_row anchor from request text match");
            }

            if (batch.mutations() == null || batch.mutations().isEmpty()) {
                if (!retriedEmpty) {
                    retriedEmpty = true;
                    log.warn("LLM returned empty batch; nudging for section-replace retry");
                    messages.add(message("assistant", content));
                    messages.add(message("user",
                            CompliancePrompts.emptyBatchRetryMessage(batch.explanation())));
                    continue;
                }
                throw new LlmDeclinedException(
                        batch.explanation() == null || batch.explanation().isBlank()
                                ? "The model returned no mutations for this request."
                                : batch.explanation());
            }

            lastErrors = validator.validate(batch, index);
            if (lastErrors.isEmpty()) {
                log.info("LLM produced valid batch on attempt {} ({} mutations)",
                        attempt + 1, batch.mutations().size());
                return new LlmProposal(batch, model, totalUsage, LlmPricing.costUsd(model, totalUsage));
            }

            Optional<MutationBatch> salvaged = MutationBatchSalvager.dropInvalid(batch, lastErrors);
            if (salvaged.isPresent()) {
                List<MutationValidator.ValidationError> salvageErrors =
                        validator.validate(salvaged.get(), index);
                if (salvageErrors.isEmpty() && !salvaged.get().mutations().isEmpty()) {
                    log.warn("Accepted partial LLM batch ({} of {} mutations)",
                            salvaged.get().mutations().size(), batch.mutations().size());
                    return new LlmProposal(salvaged.get(), model, totalUsage,
                            LlmPricing.costUsd(model, totalUsage));
                }
            }

            log.warn("LLM batch failed validation on attempt {}: {}", attempt + 1, lastErrors);
            if (attempt < MAX_RETRIES) {
                messages.add(message("assistant", content));
                String retry = CompliancePrompts.retryMessage(formatErrors(lastErrors));
                String rowHint = TableRowAnchorRepair.retryHint(request, index);
                if (!rowHint.isBlank()) {
                    retry = retry + "\n" + rowHint;
                }
                messages.add(message("user", retry));
            }
        }

        throw new MutationValidator.MutationValidationException(lastErrors);
    }

    private ChatResult chat(String apiKey, String model, ArrayNode messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        if (llm.isGemini()) {
            // Gemini's OpenAI-compat layer does not reliably honour a strict
            // json_schema with anyOf-typed array items; plain JSON mode is
            // solid and the full schema is already spelled out in the prompt.
            ObjectNode rf = body.putObject("response_format");
            rf.put("type", "json_object");
        } else {
            body.set("response_format", MutationJsonSchema.responseFormat(mapper));
        }

        JsonNode response = restClient.post()
                .uri(chatPath)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode message = response.path("choices").path(0).path("message");
        if (message.path("refusal").isTextual()) {
            throw new LlmDeclinedException("The model refused: " + message.get("refusal").asText());
        }
        TokenUsage usage = parseUsage(response.path("usage"));
        return new ChatResult(message.path("content").asText(""), usage);
    }

    private static TokenUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode()) {
            return TokenUsage.ZERO;
        }
        return new TokenUsage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("total_tokens").asInt(0));
    }

    private MutationBatch parseBatch(String content) {
        try {
            return mapper.readValue(content, MutationBatch.class);
        } catch (Exception e) {
            // Structured outputs make this near-impossible; treat as a hard error.
            throw new IllegalStateException("LLM returned unparseable JSON: " + e.getMessage(), e);
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static String formatErrors(List<MutationValidator.ValidationError> errors) {
        StringBuilder sb = new StringBuilder();
        for (MutationValidator.ValidationError error : errors) {
            sb.append("- mutation #").append(error.mutationIndex())
                    .append(" [").append(error.code()).append("]: ")
                    .append(error.message()).append('\n');
        }
        return sb.toString();
    }

    /** The model could not fulfil the request; message is user-facing. */
    public static class LlmDeclinedException extends RuntimeException {
        public LlmDeclinedException(String message) {
            super(message);
        }
    }
}
