package com.docgen.api;

import com.docgen.proposal.ProposalService;
import com.docgen.recovery.CheckpointMeta;
import com.docgen.recovery.CommitMeta;
import com.docgen.recovery.CommitStore;
import com.docgen.recovery.CheckpointStore;
import com.docgen.recovery.DocumentWorkspace;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class RecoveryController {

    private final DocumentWorkspace workspace;
    private final CommitStore commitStore;
    private final CheckpointStore checkpointStore;
    private final ProposalService proposalService;

    public RecoveryController(
            DocumentWorkspace workspace,
            CommitStore commitStore,
            CheckpointStore checkpointStore,
            ProposalService proposalService) {
        this.workspace = workspace;
        this.commitStore = commitStore;
        this.checkpointStore = checkpointStore;
        this.proposalService = proposalService;
    }

    @GetMapping("/{name}/commits")
    public Map<String, Object> listCommits(@PathVariable("name") String name) {
        workspace.ensureInitialCommit(name);
        List<CommitMeta> commits = commitStore.list(name);
        String headId = commitStore.getHead(name).map(CommitMeta::commitId).orElse(null);
        Map<String, Object> response = new HashMap<>();
        response.put("doc_name", name);
        response.put("head_commit_id", headId);
        response.put("commits", commits);
        return response;
    }

    @PostMapping("/{name}/commits")
    public CommitMeta createCommit(
            @PathVariable("name") String name,
            @RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Commit message is required.");
        }
        workspace.ensureInitialCommit(name);
        return workspace.commit(name, message.trim());
    }

    @GetMapping("/{name}/commits/{commitId}/download")
    public ResponseEntity<byte[]> downloadCommit(
            @PathVariable("name") String name,
            @PathVariable("commitId") String commitId) {
        byte[] bytes = commitStore.loadSnapshot(name, commitId);
        String filename = name.replaceAll("\\.docx$", "") + "-" + commitId + ".docx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }

    @GetMapping("/{name}/checkpoints")
    public Map<String, Object> listCheckpoints(@PathVariable("name") String name) {
        List<CheckpointMeta> checkpoints = checkpointStore.list(name);
        return Map.of(
                "doc_name", name,
                "checkpoints", checkpoints);
    }

    @PostMapping("/{name}/restore/commit/{commitId}")
    public Map<String, Object> restoreCommit(
            @PathVariable("name") String name,
            @PathVariable("commitId") String commitId) {
        int rejected = proposalService.rejectAllPending(name);
        workspace.restoreCommit(name, commitId);
        return Map.of(
                "status", "ok",
                "restored", "commit",
                "commit_id", commitId,
                "rejected_proposals", rejected);
    }

    @PostMapping("/{name}/restore/checkpoint/{checkpointId}")
    public Map<String, Object> restoreCheckpoint(
            @PathVariable("name") String name,
            @PathVariable("checkpointId") String checkpointId) {
        int rejected = proposalService.rejectAllPending(name);
        workspace.restoreCheckpoint(name, checkpointId);
        return Map.of(
                "status", "ok",
                "restored", "checkpoint",
                "checkpoint_id", checkpointId,
                "rejected_proposals", rejected);
    }
}
