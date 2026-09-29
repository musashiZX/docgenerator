package com.docgen.mutation;

import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.InsertMutation;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InsertApplierTest {

    private final InsertApplier applier =
            new InsertApplier(new BookmarkResolver(), new BookmarkIndexer());
    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();

    private WordprocessingMLPackage document;

    @BeforeEach
    void setUp() throws Exception {
        document = FixtureFactory.paragraphs("Alpha", "Bravo", "Charlie");
        new BookmarkIndexer().ensureBookmarks(document);
    }

    @Test
    void insertAfterAnchorAppearsInOrder() throws Exception {
        String newId = applier.apply(document,
                new InsertMutation("insert", "dg_p1", "after", "paragraph", "Inserted", null, null));

        assertEquals("dg_p3", newId, "new paragraph takes the next free dg_p ordinal");
        assertEquals(List.of("Alpha", "Bravo", "Inserted", "Charlie"), textsInOrder());
    }

    @Test
    void insertBeforeAnchor() throws Exception {
        applier.apply(document,
                new InsertMutation("insert", "dg_p0", "before", "paragraph", "Preamble", null, null));

        assertEquals(List.of("Preamble", "Alpha", "Bravo", "Charlie"), textsInOrder());
    }

    @Test
    void existingIdsUnchangedAfterInsert() throws Exception {
        List<String> idsBefore = idsInOrder();

        String newId = applier.apply(document,
                new InsertMutation("insert", "dg_p1", "after", "paragraph", "Inserted", null, null));

        List<String> idsAfter = idsInOrder();
        idsAfter.remove(newId);
        assertEquals(idsBefore, idsAfter, "pre-existing block ids must not shift");
    }

    @Test
    void tableCellAnchorRejected() throws Exception {
        WordprocessingMLPackage tableDoc = FixtureFactory.table3x3();
        new BookmarkIndexer().ensureBookmarks(tableDoc);

        assertThrows(IllegalArgumentException.class, () -> applier.apply(tableDoc,
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "paragraph", "x", null, null)));
    }

    private List<String> textsInOrder() throws Exception {
        List<String> texts = new ArrayList<>();
        for (BlockDescriptor block : indexBuilder.build(document, "doc").blocks()) {
            texts.add(block.text());
        }
        return texts;
    }

    private List<String> idsInOrder() throws Exception {
        List<String> ids = new ArrayList<>();
        for (BlockDescriptor block : indexBuilder.build(document, "doc").blocks()) {
            ids.add(block.targetId());
        }
        return ids;
    }
}
