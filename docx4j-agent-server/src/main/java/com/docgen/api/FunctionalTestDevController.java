package com.docgen.api;

import com.docgen.document.BaselineRestoreService;
import com.docgen.proposal.ProposalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev-only helpers for the browser functional-test runner: load the test
 * catalog, restore golden baselines, and append human verdicts to a JSONL log.
 */
@RestController
@ConditionalOnProperty(name = "app.dev-mode", havingValue = "true")
@RequestMapping("/api/dev/functional-tests")
public class FunctionalTestDevController {

    private static final String CATALOG = "docs/xyz-functional-tests.json";
    private static final String RESULTS = "docs/xyz-functional-test-results.jsonl";

    private final BaselineRestoreService baselineRestore;
    private final ProposalService proposalService;
    private final ObjectMapper objectMapper;

    public FunctionalTestDevController(
            BaselineRestoreService baselineRestore,
            ProposalService proposalService,
            ObjectMapper objectMapper) {
        this.baselineRestore = baselineRestore;
        this.proposalService = proposalService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public JsonNode catalog() throws Exception {
        Path path = baselineRestore.repoRoot().resolve(CATALOG);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Test catalog not found: " + path);
        }
        return objectMapper.readTree(path.toFile());
    }

    public record ResetRequest(@com.fasterxml.jackson.annotation.JsonProperty("golden_path") String goldenPath) {
    }

    @PostMapping("/reset/{name}")
    public Map<String, Object> reset(
            @PathVariable("name") String name,
            @RequestBody(required = false) ResetRequest request) throws Exception {
        String goldenPath = request != null && request.goldenPath() != null && !request.goldenPath().isBlank()
                ? request.goldenPath()
                : goldenPathForDocument(name);
        baselineRestore.restore(name, goldenPath);
        int rejected = proposalService.rejectAllPending(name);
        return Map.of(
                "status", "ok",
                "document", name,
                "golden_path", goldenPath,
                "rejected_proposals", rejected);
    }

    @PostMapping("/results")
    public Map<String, Object> recordResult(@RequestBody Map<String, Object> record) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>(record);
        line.putIfAbsent("timestamp", Instant.now().toString());
        line.putIfAbsent("source", "ui");
        Path path = baselineRestore.repoRoot().resolve(RESULTS);
        Files.createDirectories(path.getParent());
        byte[] bytes = (objectMapper.writeValueAsString(line) + System.lineSeparator()).getBytes();
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return Map.of("status", "ok", "path", path.toString());
    }

    private String goldenPathForDocument(String docName) throws Exception {
        JsonNode catalog = catalog();
        if (!docName.equals(catalog.path("document").asText())) {
            throw new IllegalArgumentException(
                    "No golden_path configured for document: " + docName);
        }
        String golden = catalog.path("golden_path").asText(null);
        if (golden == null || golden.isBlank()) {
            golden = "docs/" + docName;
        }
        return golden;
    }
}
