package com.docgen.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiClient {

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public OpenAiClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public ChatTurn chat(String apiKey, String model, String system, List<Map<String, Object>> tools,
                         List<Map<String, Object>> history) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", buildMessages(system, history));
        body.set("tools", mapper.valueToTree(tools));

        JsonNode response = restClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("choices") || response.get("choices").isEmpty()) {
            throw new IllegalStateException("OpenAI returned an empty response.");
        }

        JsonNode message = response.get("choices").get(0).get("message");
        String text = message.path("content").asText("");
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode call : toolCallsNode) {
                JsonNode fn = call.path("function");
                JsonNode argsNode = fn.path("arguments");
                JsonNode args;
                try {
                    args = argsNode.isTextual()
                            ? mapper.readTree(argsNode.asText())
                            : argsNode;
                } catch (Exception e) {
                    args = mapper.createObjectNode();
                }
                toolCalls.add(new ToolCall(
                        call.path("id").asText(),
                        fn.path("name").asText(),
                        args));
            }
        }
        return new ChatTurn(text, toolCalls);
    }

    private ArrayNode buildMessages(String system, List<Map<String, Object>> history) {
        ArrayNode messages = mapper.createArrayNode();
        if (system != null && !system.isBlank()) {
            ObjectNode systemMsg = mapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", system);
            messages.add(systemMsg);
        }

        for (Map<String, Object> entry : history) {
            String role = String.valueOf(entry.get("role"));
            switch (role) {
                case "user" -> {
                    ObjectNode msg = mapper.createObjectNode();
                    msg.put("role", "user");
                    msg.put("content", String.valueOf(entry.get("content")));
                    messages.add(msg);
                }
                case "assistant" -> {
                    ObjectNode msg = mapper.createObjectNode();
                    msg.put("role", "assistant");
                    Object content = entry.get("content");
                    if (content != null) {
                        msg.put("content", String.valueOf(content));
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> calls = (List<Map<String, Object>>) entry.get("tool_calls");
                    if (calls != null && !calls.isEmpty()) {
                        ArrayNode toolCalls = mapper.createArrayNode();
                        for (Map<String, Object> call : calls) {
                            ObjectNode tc = mapper.createObjectNode();
                            tc.put("id", String.valueOf(call.get("id")));
                            tc.put("type", "function");
                            ObjectNode fn = mapper.createObjectNode();
                            fn.put("name", String.valueOf(call.get("name")));
                            try {
                                fn.put("arguments", mapper.writeValueAsString(call.get("args")));
                            } catch (Exception e) {
                                fn.put("arguments", "{}");
                            }
                            tc.set("function", fn);
                            toolCalls.add(tc);
                        }
                        msg.set("tool_calls", toolCalls);
                    }
                    messages.add(msg);
                }
                case "tool_batch" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> responses =
                            (List<Map<String, Object>>) entry.get("responses");
                    if (responses != null) {
                        for (Map<String, Object> response : responses) {
                            ObjectNode msg = mapper.createObjectNode();
                            msg.put("role", "tool");
                            msg.put("tool_call_id", String.valueOf(response.get("id")));
                            msg.put("content", String.valueOf(response.get("content")));
                            messages.add(msg);
                        }
                    }
                }
                default -> {
                }
            }
        }
        return messages;
    }

    public record ToolCall(String id, String name, JsonNode args) {}

    public record ChatTurn(String text, List<ToolCall> toolCalls) {}
}
