package com.docgen.api;

import com.docgen.document.DocumentIndexService;
import com.docgen.llm.ComplianceClient;
import com.docgen.model.ApplyResult;
import com.docgen.model.FocusBlock;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.proposal.Proposal;
import com.docgen.proposal.ProposalService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Propose/review/approve workflow. A proposal is created either from a manual
 * batch or from a natural-language message (LLM generates the batch); the
 * working document only changes on approve.
 */
@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    private static final Logger log = LoggerFactory.getLogger(ProposalController.class);

    public record ProposeRequest(
            @JsonProperty("doc_name") String docName,
            MutationBatch batch,
            String message,
            String model,
            @JsonProperty("selected_text") String selectedText,
            @JsonProperty("selected_blocks") List<FocusBlock> selectedBlocks
    ) {
    }

    private final ProposalService proposalService;
    private final ComplianceClient complianceClient;
    private final DocumentIndexService documentIndexService;

    public ProposalController(
            ProposalService proposalService,
            ComplianceClient complianceClient,
            DocumentIndexService documentIndexService) {
        this.proposalService = proposalService;
        this.complianceClient = complianceClient;
        this.documentIndexService = documentIndexService;
    }

    @PostMapping
    public Proposal propose(@RequestBody ProposeRequest request, HttpServletRequest http)
            throws Exception {
        String traceId = String.valueOf(http.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        if (request.docName() == null || request.docName().isBlank()) {
            throw new IllegalArgumentException("doc_name is required");
        }
        boolean hasBatch = request.batch() != null;
        boolean hasMessage = request.message() != null && !request.message().isBlank();
        if (hasBatch == hasMessage) {
            throw new IllegalArgumentException(
                    "Provide exactly one of \"batch\" (manual) or \"message\" (LLM).");
        }

        if (hasBatch) {
            log.info("[trace:{}] Manual proposal for {} ({} mutations)",
                    traceId, request.docName(), request.batch().mutations().size());
            return proposalService.propose(
                    request.docName(), request.batch(), "manual", null, null);
        }

        log.info("[trace:{}] LLM proposal for {}: \"{}\"",
                traceId, request.docName(), request.message());
        StructuralIndex index = documentIndexService.buildIndex(request.docName());
        ComplianceClient.LlmProposal llm = complianceClient.propose(
                request.message(), index, request.model(),
                request.selectedBlocks(), request.selectedText());
        return proposalService.propose(
                request.docName(), llm.batch(), "llm", request.message(), llm.model(),
                llm.usage(), llm.costUsd());
    }

    public record BatchProposeRequest(
            @JsonProperty("doc_name") String docName,
            List<String> messages,
            String model
    ) {
    }

    /**
     * Runs several independent edit requests concurrently (one LLM call
     * each, in parallel) and folds whatever comes back into ONE proposal —
     * wall-clock time is bounded by the slowest single call, not the sum of
     * all of them, so a batch of N requests costs about as much time as one.
     */
    @PostMapping("/batch")
    public Map<String, Object> proposeBatch(@RequestBody BatchProposeRequest request, HttpServletRequest http)
            throws Exception {
        String traceId = String.valueOf(http.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        if (request.docName() == null || request.docName().isBlank()) {
            throw new IllegalArgumentException("doc_name is required");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("messages must be a non-empty list");
        }
        log.info("[trace:{}] Batch LLM proposal for {}: {} request(s)",
                traceId, request.docName(), request.messages().size());

        long start = System.currentTimeMillis();
        StructuralIndex index = documentIndexService.buildIndex(request.docName());
        ComplianceClient.BatchLlmProposal batch =
                complianceClient.proposeBatch(request.messages(), index, request.model());
        long elapsedMs = System.currentTimeMillis() - start;

        List<Map<String, Object>> itemSummaries = batch.items().stream()
                .map(item -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("request", item.request());
                    m.put("mutation_count", item.batch() == null ? 0 : item.batch().mutations().size());
                    m.put("error", item.error());
                    return m;
                })
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("items", itemSummaries);
        response.put("elapsed_ms", elapsedMs);
        response.put("total_usage", batch.totalUsage());
        response.put("total_cost_usd", batch.totalCost());

        if (batch.combinedBatch().mutations().isEmpty()) {
            response.put("status", "no_mutations");
            return response;
        }

        Proposal proposal = proposalService.propose(
                request.docName(), batch.combinedBatch(), "llm-batch",
                String.join(" || ", request.messages()), batch.model(),
                batch.totalUsage(), batch.totalCost());
        response.put("status", "ok");
        response.put("proposal", proposal);
        return response;
    }

    @GetMapping
    public List<Proposal> list(@RequestParam(value = "doc", required = false) String docName) {
        return proposalService.list(docName);
    }

    @GetMapping("/{id}")
    public Proposal get(@PathVariable("id") String id) {
        return proposalService.get(id);
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") String id, HttpServletRequest http)
            throws Exception {
        String traceId = String.valueOf(http.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        ApplyResult result = proposalService.approve(id);
        log.info("[trace:{}] Proposal {} approved, changed={}", traceId, id, result.changedIds());
        return Map.of(
                "status", "approved",
                "applied_count", result.appliedCount(),
                "changed_ids", result.changedIds(),
                "created_ids", result.createdIds(),
                "formatted_ids", result.formattedIds(),
                "trace_id", traceId);
    }

    @PostMapping("/{id}/reject")
    public Proposal reject(@PathVariable("id") String id) {
        return proposalService.reject(id);
    }
}
