package com.docgen.llm;

import com.docgen.config.AppProperties;
import com.docgen.model.FocusBlock;
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

import java.util.List;
import java.util.Optional;

/**
 * Turns a natural-language request + structural index into a validated
 * {@link MutationBatch} via OpenAI structured outputs. Validation failures
 * are fed back to the model for up to {@value #MAX_RETRIES} repair attempts.
 */
@Component
public class ComplianceClient {

    private static final Logger log = LoggerFactory.getLogger(ComplianceClient.class);
    private static final int MAX_RETRIES = 1;
    public static final String BULK_REPLACE_MODEL = "bulk-replace";

    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final MutationValidator validator;
    private final RestClient restClient;

    @org.springframework.beans.factory.annotation.Autowired
    public ComplianceClient(AppProperties properties, ObjectMapper mapper, MutationValidator validator) {
        this(properties, mapper, validator,
                RestClient.builder().baseUrl("https://api.openai.com").build());
    }

    ComplianceClient(AppProperties properties, ObjectMapper mapper,
                     MutationValidator validator, RestClient restClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.validator = validator;
        this.restClient = restClient;
    }

    /** Result plus the model's raw explanation for the UI. */
    public record LlmProposal(MutationBatch batch, String model) {
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
        String apiKey = properties.openaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not set. Add it to the repo-root .env file.");
        }
        String model = modelOverride == null || modelOverride.isBlank()
                ? properties.defaultModel()
                : modelOverride;

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
        messages.add(message("system", CompliancePrompts.SYSTEM));
        messages.add(message("user",
                CompliancePrompts.userMessage(request, promptIndex, focusBlocks, legacySelectedText)));

        List<MutationValidator.ValidationError> lastErrors = null;
        boolean retriedEmpty = false;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            String content = chat(apiKey, model, messages);
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
                return new LlmProposal(batch, model);
            }

            Optional<MutationBatch> salvaged = MutationBatchSalvager.dropInvalid(batch, lastErrors);
            if (salvaged.isPresent()) {
                List<MutationValidator.ValidationError> salvageErrors =
                        validator.validate(salvaged.get(), index);
                if (salvageErrors.isEmpty() && !salvaged.get().mutations().isEmpty()) {
                    log.warn("Accepted partial LLM batch ({} of {} mutations)",
                            salvaged.get().mutations().size(), batch.mutations().size());
                    return new LlmProposal(salvaged.get(), model);
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

    private String chat(String apiKey, String model, ArrayNode messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.set("response_format", MutationJsonSchema.responseFormat(mapper));

        JsonNode response = restClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode message = response.path("choices").path(0).path("message");
        if (message.path("refusal").isTextual()) {
            throw new LlmDeclinedException("The model refused: " + message.get("refusal").asText());
        }
        return message.path("content").asText("");
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
