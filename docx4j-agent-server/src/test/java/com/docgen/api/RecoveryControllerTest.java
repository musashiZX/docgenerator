package com.docgen.api;

import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.docgen.proposal.Proposal;
import com.docgen.proposal.ProposalService;
import com.docgen.recovery.CheckpointStore;
import com.docgen.support.FixtureFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RecoveryControllerTest {

    @TempDir
    static Path tempDocsDir;

    @DynamicPropertySource
    static void docsDir(DynamicPropertyRegistry registry) {
        registry.add("app.docs-dir", () -> tempDocsDir.toString());
    }

    private static final String DOC = "work.docx";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private CheckpointStore checkpointStore;

    @BeforeEach
    void seedDocument() throws Exception {
        Files.createDirectories(tempDocsDir);
        deleteRecursively(tempDocsDir.resolve(".commits"));
        deleteRecursively(tempDocsDir.resolve(".checkpoints"));
        deleteRecursively(tempDocsDir.resolve(".proposals"));
        Files.createDirectories(tempDocsDir.resolve(".proposals"));
        for (Path path : Files.list(tempDocsDir).filter(p -> Files.isRegularFile(p)).toList()) {
            Files.deleteIfExists(path);
        }
        FixtureFactory.writeParagraphs(tempDocsDir.resolve(DOC), "Alpha", "Bravo");
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best-effort cleanup between tests.
                }
            });
        }
    }

    @Test
    void postCommitReturnsCommitId() throws Exception {
        mockMvc.perform(post("/api/documents/" + DOC + "/commits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Baseline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit_id").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Baseline"));
    }

    @Test
    void downloadCommitReturnsValidDocxBytes() throws Exception {
        byte[] original = Files.readAllBytes(tempDocsDir.resolve(DOC));

        mockMvc.perform(post("/api/documents/" + DOC + "/commits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Baseline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit_id").isNotEmpty());

        String commitId = mockMvc.perform(get("/api/documents/" + DOC + "/commits"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        commitId = commitId.replaceAll(".*\"head_commit_id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        byte[] downloaded = mockMvc.perform(get("/api/documents/" + DOC + "/commits/" + commitId + "/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".docx")))
                .andReturn().getResponse().getContentAsByteArray();

        assertEquals(original.length, downloaded.length);
    }

    @Test
    void restoreCommitRejectsPendingProposals() throws Exception {
        Proposal pending = proposalService.propose(DOC,
                new MutationBatch(1, "test", List.of(
                        new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!"))),
                "manual", null, null);

        mockMvc.perform(post("/api/documents/" + DOC + "/commits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Baseline\"}"))
                .andExpect(status().isOk());

        String commitId = mockMvc.perform(get("/api/documents/" + DOC + "/commits"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"head_commit_id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        FixtureFactory.writeParagraphs(tempDocsDir.resolve(DOC), "Drifted", "Bravo");

        mockMvc.perform(post("/api/documents/" + DOC + "/restore/commit/" + commitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.rejected_proposals").value(1));

        assertEquals("REJECTED", proposalService.get(pending.id()).status().name());
    }

    @Test
    void commitModifyRestoreRoundTrip() throws Exception {
        byte[] baseline = Files.readAllBytes(tempDocsDir.resolve(DOC));

        mockMvc.perform(post("/api/documents/" + DOC + "/commits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Baseline\"}"))
                .andExpect(status().isOk());

        String commitId = mockMvc.perform(get("/api/documents/" + DOC + "/commits"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"head_commit_id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        FixtureFactory.writeParagraphs(tempDocsDir.resolve(DOC), "Modified", "Bravo");

        mockMvc.perform(post("/api/documents/" + DOC + "/restore/commit/" + commitId))
                .andExpect(status().isOk());

        assertArrayEquals(baseline, Files.readAllBytes(tempDocsDir.resolve(DOC)));
    }

    @Test
    void approveCreatesCheckpointMatchingPreApproveShadow() throws Exception {
        Proposal proposal = proposalService.propose(DOC,
                new MutationBatch(1, "test", List.of(
                        new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!"))),
                "manual", null, null);
        byte[] beforeApprove = Files.readAllBytes(tempDocsDir.resolve(DOC));
        int checkpointsBefore = checkpointStore.list(DOC).size();

        proposalService.approve(proposal.id());

        assertEquals(checkpointsBefore + 1, checkpointStore.list(DOC).size());
        byte[] checkpointBytes = checkpointStore.loadSnapshot(
                DOC, checkpointStore.list(DOC).get(0).checkpointId());
        assertArrayEquals(beforeApprove, checkpointBytes);
    }
}
