package com.docgen.agent;

import com.docgen.config.AppProperties;
import com.docgen.docio.DocIoService;
import com.docgen.llm.OpenAiClient;
import com.docgen.model.ChatRequest;
import com.docgen.model.ChatResponse;
import com.docgen.trace.RoundSummaryBuilder;
import com.docgen.trace.OperationRecorder;
import com.docgen.trace.OperationTraceSession;
import com.docgen.trace.OperationType;
import com.syncfusion.docio.FormatType;
import com.syncfusion.docio.WordDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AppProperties appProperties;
    private final DocIoService docIoService;
    private final AgentSessionStore sessionStore;
    private final OpenAiClient openAiClient;
    private final OperationRecorder operationRecorder;

    public AgentService(
            AppProperties appProperties,
            DocIoService docIoService,
            AgentSessionStore sessionStore,
            OpenAiClient openAiClient,
            OperationRecorder operationRecorder) {
        this.appProperties = appProperties;
        this.docIoService = docIoService;
        this.sessionStore = sessionStore;
        this.openAiClient = openAiClient;
        this.operationRecorder = operationRecorder;
    }

    public ChatResponse runChat(ChatRequest request) {
        OperationTraceSession trace = operationRecorder.current();
        String apiKey = appProperties.openaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY is not set. Add it to the repo-root .env file.");
        }

        String model = request.model() == null || request.model().isBlank()
                ? appProperties.defaultModel()
                : request.model();

        Path docPath = docIoService.resolveDoc(request.docName());
        List<Map<String, Object>> history = sessionStore.getOrCreate(request.sessionId());
        String userContent = buildUserMessage(request.message(), request.selectedText());
        history.add(Map.of("role", "user", "content", userContent));

        List<Map<String, Object>> toolLog = new ArrayList<>();
        StringBuilder finalText = new StringBuilder();

        Map<String, Object> turnMeta = new LinkedHashMap<>();
        turnMeta.put("session_id", request.sessionId());
        turnMeta.put("doc_name", request.docName());
        turnMeta.put("model", model);
        turnMeta.put("message", request.message());
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            turnMeta.put("selected_text", request.selectedText());
        }
        trace.record(OperationType.AGENT_TURN_START, "Agent turn started", turnMeta);
        trace.snapshotDocument(docPath, "before");

        log.info("CHAT | session={} | model={} | doc={} | prompt={}",
                request.sessionId(), model, request.docName(), truncate(request.message(), 200));

        try (WordDocument document = new WordDocument(docPath.toString())) {
            ToolExecutor executor = new ToolExecutor(document);

            for (int step = 0; step < appProperties.maxToolRounds(); step++) {
                Map<String, Object> llmMeta = Map.of("round", step, "model", model);
                trace.record(OperationType.LLM_ROUND, "LLM round " + step + " request", llmMeta);
                trace.writeArtifact("llm/round-" + step + "-history-size.json",
                        Map.of("history_messages", history.size()));

                long llmStarted = System.currentTimeMillis();
                OpenAiClient.ChatTurn turn = openAiClient.chat(
                        apiKey,
                        model,
                        AgentPrompts.SYSTEM,
                        ToolDefinitions.TOOLS,
                        history);
                long llmDurationMs = System.currentTimeMillis() - llmStarted;

                trace.writeArtifact("llm/round-" + step + "-response.json", Map.of(
                        "text", turn.text(),
                        "duration_ms", llmDurationMs,
                        "tool_calls", turn.toolCalls().stream()
                                .map(c -> Map.of("id", c.id(), "name", c.name(), "args", c.args()))
                                .toList()));
                trace.record(OperationType.LLM_ROUND, "LLM round " + step + " response",
                        Map.of("round", step, "duration_ms", llmDurationMs,
                                "tool_call_count", turn.toolCalls().size(),
                                "text_preview", truncate(turn.text(), 200)));
                log.info("LLM round {} completed in {} ms ({} tool calls)",
                        step, llmDurationMs, turn.toolCalls().size());

                Map<String, Object> assistantEntry = new HashMap<>();
                assistantEntry.put("role", "assistant");
                assistantEntry.put("content", turn.text());
                if (!turn.toolCalls().isEmpty()) {
                    List<Map<String, Object>> serializedCalls = new ArrayList<>();
                    for (OpenAiClient.ToolCall call : turn.toolCalls()) {
                        serializedCalls.add(Map.of(
                                "id", call.id(),
                                "name", call.name(),
                                "args", jsonToMap(call.args())));
                    }
                    assistantEntry.put("tool_calls", serializedCalls);
                }
                history.add(assistantEntry);

                if (turn.text() != null && !turn.text().isBlank()) {
                    finalText.append(turn.text());
                }

                if (turn.toolCalls().isEmpty()) {
                    break;
                }

                List<Map<String, Object>> responses = new ArrayList<>();
                for (OpenAiClient.ToolCall call : turn.toolCalls()) {
                    Map<String, Object> argsMap = jsonToMap(call.args());
                    trace.record(OperationType.TOOL_DISPATCH, call.name(),
                            Map.of("round", step, "args", argsMap));

                    String result;
                    try {
                        result = executor.dispatch(call.name(), call.args());
                    } catch (Exception ex) {
                        result = "Error: " + ex.getMessage();
                        trace.record(OperationType.ERROR, "Tool failed: " + call.name(),
                                Map.of("tool", call.name(), "error", ex.getMessage()));
                        log.warn("TOOL FAILED | {} | {}", call.name(), ex.getMessage());
                    }

                    trace.record(OperationType.TOOL_RESULT, call.name(),
                            Map.of("round", step, "result", result));
                    toolLog.add(Map.of("name", call.name(), "args", argsMap, "result", result));
                    responses.add(Map.of(
                            "id", call.id(),
                            "name", call.name(),
                            "content", truncateForLlmHistory(result, call.name())));
                    log.info("TOOL | {}({}) → {}", call.name(), truncate(argsMap.toString(), 120),
                            truncate(result, 120));
                }
                history.add(Map.of("role", "tool_batch", "responses", responses));
            }

            document.save(docPath.toString(), FormatType.Docx);
            docIoService.markUpdated(docPath.getFileName().toString());
            trace.record(OperationType.DOCUMENT_SAVE, "Document saved after agent turn",
                    Map.of("doc_name", request.docName()));
            trace.snapshotDocument(docPath, "after");
        } catch (Exception ex) {
            trace.record(OperationType.ERROR, "Agent turn failed",
                    Map.of("error", ex.getMessage()));
            throw new DocIoService.DocIoException("Agent turn failed: " + ex.getMessage(), ex);
        }

        String reply = finalText.isEmpty() ? "(no reply)" : finalText.toString();
        // Preview is loaded by the frontend via GET /preview — skip HTML export here (saves ~2s on large docs).
        String previewHtml = "";

        trace.record(OperationType.AGENT_TURN_END, "Agent turn completed",
                Map.of("tool_count", toolLog.size(), "reply_preview", truncate(reply, 200)));

        log.info("CHAT DONE | session={} | tools={} | reply={}",
                request.sessionId(), toolLog.size(), truncate(reply, 200));

        Map<String, Object> roundSummary = RoundSummaryBuilder.fromToolLog(
                trace.traceId(), request.docName(), toolLog);
        return new ChatResponse(reply, toolLog, previewHtml, toolLog.isEmpty() ? 0 : 1,
                trace.traceId(), roundSummary);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonToMap(com.fasterxml.jackson.databind.JsonNode node) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Map.class);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String buildUserMessage(String message, String selectedText) {
        if (selectedText == null || selectedText.isBlank()) {
            return message;
        }
        return """
                The user highlighted this excerpt from the document:
                ---
                %s
                ---
                Apply the request below to that excerpt when possible; use read_document for full context.
                Request: %s
                """.formatted(selectedText.trim(), message);
    }

    /** Keep full tool output in traces; send a smaller payload back to the LLM on later rounds. */
    private static String truncateForLlmHistory(String result, String toolName) {
        if (result == null) {
            return "";
        }
        int max = switch (toolName) {
            case "read_document" -> 12_000;
            case "read_table" -> 8_000;
            default -> 2_000;
        };
        if (result.length() <= max) {
            return result;
        }
        return result.substring(0, max) + "\n…(truncated for LLM context; full output is in the trace log)";
    }
}
