package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.ApplyResult;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MutationApplierTest {

    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();
    private final MutationApplier applier = new MutationApplier(
            new MutationValidator(),
            new ModifyApplier(new BookmarkResolver()),
            new NodeHashGuard(indexBuilder));

    private DocumentSession session;
    private StructuralIndex index;

    @BeforeEach
    void setUp() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        new BookmarkIndexer().ensureBookmarks(document);
        session = new DocumentSession(document);
        index = indexBuilder.build(document, "table-3x3.docx");
    }

    @Test
    void endToEndValidBatchWithGuard() throws Exception {
        MutationBatch batch = batch(
                new ModifyMutation("modify", "dg_tbl0_r1_c1", "R1C1", 0, "CENTER"),
                new ModifyMutation("modify", "dg_tbl0_r2_c2", "R2C2", 0, "LAST"));

        ApplyResult result = applier.apply(session, batch, index);

        assertEquals(2, result.appliedCount());
        assertEquals(Set.of("dg_tbl0_r1_c1", "dg_tbl0_r2_c2"), result.changedIds());
        Map<String, String> texts = blockTexts();
        assertEquals("CENTER", texts.get("dg_tbl0_r1_c1"));
        assertEquals("LAST", texts.get("dg_tbl0_r2_c2"));
        assertEquals("R0C0", texts.get("dg_tbl0_r0_c0"));
    }

    @Test
    void invalidBatchNeverMutatesDocument() throws Exception {
        Map<String, String> before = blockTexts();
        MutationBatch batch = batch(
                new ModifyMutation("modify", "dg_tbl0_r1_c1", "R1C1", 0, "CENTER"),
                new ModifyMutation("modify", "dg_unknown", "x", 0, "y"));

        assertThrows(MutationValidator.MutationValidationException.class,
                () -> applier.apply(session, batch, index));

        assertEquals(before, blockTexts(), "validation failure must leave the document untouched");
    }

    @Test
    void staleOldTextRollsBackWholeBatch() throws Exception {
        // Make the live document diverge from the (now stale) index: validation
        // passes against the index, but the second mutation fails at apply time.
        new ModifyApplier(new BookmarkResolver()).apply(session.document(),
                new ModifyMutation("modify", "dg_tbl0_r1_c1", "R1C1", 0, "ALREADY-CHANGED"));

        MutationBatch staleBatch = batch(
                new ModifyMutation("modify", "dg_tbl0_r0_c0", "R0C0", 0, "FIRST"),
                new ModifyMutation("modify", "dg_tbl0_r1_c1", "R1C1", 0, "SECOND"));

        assertThrows(StaleTargetException.class,
                () -> applier.apply(session, staleBatch, index));

        Map<String, String> after = blockTexts();
        assertEquals("R0C0", after.get("dg_tbl0_r0_c0"),
                "first mutation must be rolled back when a later one fails");
        assertEquals("ALREADY-CHANGED", after.get("dg_tbl0_r1_c1"));
    }

    private static MutationBatch batch(Mutation... mutations) {
        return new MutationBatch(1, "test", List.of(mutations));
    }

    private Map<String, String> blockTexts() throws Exception {
        Map<String, String> texts = new HashMap<>();
        for (BlockDescriptor block : indexBuilder.build(session.document(), "doc").blocks()) {
            texts.put(block.targetId(), block.text());
        }
        return texts;
    }
}
