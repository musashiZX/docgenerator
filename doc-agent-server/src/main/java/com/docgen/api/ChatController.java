package com.docgen.api;

import com.docgen.agent.AgentService;
import com.docgen.agent.AgentSessionStore;
import com.docgen.model.ChatRequest;
import com.docgen.model.ChatResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentService agentService;
    private final AgentSessionStore sessionStore;

    public ChatController(AgentService agentService, AgentSessionStore sessionStore) {
        this.agentService = agentService;
        this.sessionStore = sessionStore;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequestBody body) {
        ChatRequest request = new ChatRequest(
                body.sessionId(),
                body.docName(),
                body.message(),
                body.model(),
                body.selectedText());
        return agentService.runChat(request);
    }

    @GetMapping("/models")
    public Map<String, List<String>> models() {
        return Map.of("models", List.of(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-5",
                "gpt-5-mini"));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> clearSession(@PathVariable String sessionId) {
        sessionStore.clear(sessionId);
        return Map.of("cleared", sessionId);
    }

    public record ChatRequestBody(
            @JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("doc_name") @NotBlank String docName,
            @NotBlank String message,
            String model,
            @JsonProperty("selected_text") String selectedText
    ) {}
}
