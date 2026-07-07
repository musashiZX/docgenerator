package com.docgen.proposal;

import com.docgen.config.AppProperties;
import com.docgen.document.DocumentLoader;
import com.docgen.document.DocumentLibraryService;
import com.docgen.document.DocumentMetadataStore;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.ApplyResult;
import com.docgen.model.DeleteMutation;
import com.docgen.model.InsertMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.docgen.mutation.DeleteApplier;
import com.docgen.mutation.InsertApplier;
import com.docgen.mutation.ModifyApplier;
import com.docgen.mutation.MutationApplier;
import com.docgen.mutation.MutationValidator;
import com.docgen.mutation.NodeHashGuard;
import com.docgen.support.FixtureFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalServiceTest {

    @TempDir
    Path tempDir;

    private DocumentLoader loader;
    private StructuralIndexBuilder indexBuilder;
    private ProposalService service;
    private Path docPath;

    @BeforeEach
    void setUp() throws Exception {
        loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, false));
        indexBuilder = new StructuralIndexBuilder();
        MutationApplier applier = new MutationApplier(
                new MutationValidator(),
                new ModifyApplier(new BookmarkResolver()),
                new InsertApplier(new BookmarkResolver(), new BookmarkIndexer()),
                new DeleteApplier(new BookmarkResolver()),
                new NodeHashGuard(indexBuilder));
        service = new ProposalService(
                loader, new BookmarkIndexer(), indexBuilder, applier,
                new ProposalStore(loader, new ObjectMapper()),
                new DocumentLibraryService(loader, new BookmarkIndexer(),
                        new DocumentMetadataStore(loader, new ObjectMapper())));

        docPath = loader.docsDirectory().resolve("work.docx");
        FixtureFactory.writeParagraphs(docPath, "Alpha", "Bravo", "Charlie");
    }

    @Test
    void proposeDoesNotTouchWorkingDocument() throws Exception {
        Proposal proposal = service.propose("work.docx",
                batch(new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!")),
                "manual", null, null);

        assertEquals(ProposalStatus.PENDING, proposal.status());
        assertEquals("Alpha", currentTexts().get("dg_p0"),
                "propose must not modify the working document");
    }

    @Test
    void diffsShowBeforeAfterPerBlock() throws Exception {
        Proposal proposal = service.propose("work.docx",
                batch(
                        new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!"),
                        new InsertMutation("insert", "dg_p1", "after", "paragraph", "New para", null),
                        new DeleteMutation("delete", "dg_p2")),
                "manual", null, null);

        Map<String, BlockDiff> byId = new HashMap<>();
        proposal.diffs().forEach(diff -> byId.put(diff.targetId(), diff));

        assertEquals("Alpha", byId.get("dg_p0").beforeText());
        assertEquals("Alpha!", byId.get("dg_p0").afterText());
        assertEquals("modify", byId.get("dg_p0").op());

        BlockDiff insert = byId.get("dg_p3");
        assertEquals("insert", insert.op());
        assertNull(insert.beforeText());
        assertEquals("New para", insert.afterText());

        BlockDiff delete = byId.get("dg_p2");
        assertEquals("delete", delete.op());
        assertEquals("Charlie", delete.beforeText());
        assertNull(delete.afterText());
    }

    @Test
    void approveAppliesToWorkingDocument() throws Exception {
        Proposal proposal = service.propose("work.docx",
                batch(new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!")),
                "manual", null, null);

        ApplyResult result = service.approve(proposal.id());

        assertEquals(Set.of("dg_p0"), result.changedIds());
        assertEquals("Alpha!", currentTexts().get("dg_p0"));
        assertEquals(ProposalStatus.APPROVED, service.get(proposal.id()).status());
    }

    @Test
    void rejectLeavesWorkingDocumentUnchanged() throws Exception {
        Proposal proposal = service.propose("work.docx",
                batch(new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!")),
                "manual", null, null);

        Proposal rejected = service.reject(proposal.id());

        assertEquals(ProposalStatus.REJECTED, rejected.status());
        assertEquals("Alpha", currentTexts().get("dg_p0"));
    }

    @Test
    void approveTwiceIsRejected() throws Exception {
        Proposal proposal = service.propose("work.docx",
                batch(new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!")),
                "manual", null, null);
        service.approve(proposal.id());

        assertThrows(IllegalArgumentException.class, () -> service.approve(proposal.id()));
    }

    @Test
    void approveFailsIfDocumentDriftedSinceProposal() throws Exception {
        Proposal proposal = service.propose("work.docx",
                batch(new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!")),
                "manual", null, null);

        // Drift: another actor changes the same block before approval.
        FixtureFactory.writeParagraphs(docPath, "Changed", "Bravo", "Charlie");
        new BookmarkIndexer(); // ids re-created on load inside approve

        assertThrows(MutationValidator.MutationValidationException.class,
                () -> service.approve(proposal.id()));
        assertEquals("Changed", currentTexts().get("dg_p0"), "drifted doc must stay untouched");
        assertEquals(ProposalStatus.PENDING, service.get(proposal.id()).status());
    }

    @Test
    void llmMetadataIsPersisted() throws Exception {
        Proposal proposal = service.propose("work.docx",
                batch(new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!")),
                "llm", "make it pop", "gpt-4o-mini");

        Proposal loaded = service.get(proposal.id());
        assertEquals("llm", loaded.source());
        assertEquals("make it pop", loaded.prompt());
        assertEquals("gpt-4o-mini", loaded.model());
        assertTrue(loaded.createdAt() != null);
    }

    private static MutationBatch batch(com.docgen.model.Mutation... mutations) {
        return new MutationBatch(1, "test", List.of(mutations));
    }

    private Map<String, String> currentTexts() throws Exception {
        var document = loader.load(docPath);
        new BookmarkIndexer().ensureBookmarks(document);
        Map<String, String> texts = new HashMap<>();
        for (var block : indexBuilder.build(document, "work.docx").blocks()) {
            texts.put(block.targetId(), block.text());
        }
        return texts;
    }
}
