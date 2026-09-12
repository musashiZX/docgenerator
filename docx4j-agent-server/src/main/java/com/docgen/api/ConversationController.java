package com.docgen.api;

import com.docgen.conversation.ConversationService;
import com.docgen.conversation.ConversationSession;
import com.docgen.model.ApplyResult;
import com.docgen.model.FocusBlock;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * "Keep reasoning with the AI" workflow: one active chat session per
 * document. Each message refines the session's current proposal rather than
 * creating an independent one; approve/reject act on that current proposal.
 */
@RestController
@RequestMapping("/api/documents/{name}/session")
public class ConversationController {

    public record MessageRequest(
            String message,
            String model,
            @JsonProperty("selected_text") String selectedText,
            @JsonProperty("selected_blocks") List<FocusBlock> selectedBlocks
    ) {
    }

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ConversationSession get(@PathVariable("name") String name) {
        return conversationService.get(name);
    }

    /** All sessions (active + archived) for this document, newest first. */
    @GetMapping("/history")
    public List<ConversationSession> history(@PathVariable("name") String name) {
        return conversationService.list(name);
    }

    @PostMapping("/message")
    public ConversationSession sendMessage(
            @PathVariable("name") String name, @RequestBody MessageRequest request) throws Exception {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        return conversationService.sendMessage(
                name, request.message(), request.model(), request.selectedBlocks(), request.selectedText());
    }

    @PostMapping("/new")
    public ConversationSession startNew(@PathVariable("name") String name) {
        return conversationService.startNew(name);
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@PathVariable("name") String name) throws Exception {
        ApplyResult result = conversationService.approve(name);
        return Map.of(
                "status", "approved",
                "applied_count", result.appliedCount(),
                "changed_ids", result.changedIds(),
                "created_ids", result.createdIds(),
                "formatted_ids", result.formattedIds());
    }

    @PostMapping("/reject")
    public ConversationSession reject(@PathVariable("name") String name) {
        return conversationService.reject(name);
    }
}
