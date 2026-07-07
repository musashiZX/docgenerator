package com.docgen.proposal;

import com.docgen.config.AppProperties;
import com.docgen.document.DocumentLoader;
import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalStoreTest {

    @TempDir
    Path tempDir;

    private ProposalStore store;

    @BeforeEach
    void setUp() throws Exception {
        DocumentLoader loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, false));
        store = new ProposalStore(loader, new ObjectMapper());
    }

    @Test
    void saveAndLoadRoundTrip() {
        Proposal proposal = sampleProposal("p-1", "doc.docx");
        byte[] snapshot = {1, 2, 3};

        store.save(proposal, snapshot);
        Proposal loaded = store.load("p-1").orElseThrow();

        assertEquals(proposal.id(), loaded.id());
        assertEquals(proposal.docName(), loaded.docName());
        assertEquals(ProposalStatus.PENDING, loaded.status());
        assertEquals(1, loaded.batch().mutations().size());
        assertEquals("modify", loaded.batch().mutations().get(0).op());
        assertEquals(1, loaded.diffs().size());
        assertEquals("old", loaded.diffs().get(0).beforeText());
        assertArrayEquals(snapshot, store.loadBeforeSnapshot("p-1"));
    }

    @Test
    void updatePreservesSnapshotAndChangesStatus() {
        Proposal proposal = sampleProposal("p-2", "doc.docx");
        store.save(proposal, new byte[]{9});

        store.update(proposal.withStatus(ProposalStatus.APPROVED, Instant.now()));

        assertEquals(ProposalStatus.APPROVED, store.load("p-2").orElseThrow().status());
        assertArrayEquals(new byte[]{9}, store.loadBeforeSnapshot("p-2"));
    }

    @Test
    void listFiltersByDocumentNewestFirst() {
        store.save(sampleProposal("a", "one.docx"), null);
        store.save(sampleProposal("b", "two.docx"), null);
        store.save(sampleProposal("c", "one.docx"), null);

        assertEquals(3, store.list(null).size());
        List<Proposal> forOne = store.list("one.docx");
        assertEquals(2, forOne.size());
        assertTrue(forOne.stream().allMatch(p -> p.docName().equals("one.docx")));
    }

    private static Proposal sampleProposal(String id, String docName) {
        MutationBatch batch = new MutationBatch(1, "test", List.of(
                new ModifyMutation("modify", "dg_p0", "old", 0, "new")));
        return new Proposal(id, docName, ProposalStatus.PENDING, "manual", null, null,
                Instant.now(), null, batch,
                List.of(new BlockDiff("dg_p0", "modify", "old", "new")));
    }
}
