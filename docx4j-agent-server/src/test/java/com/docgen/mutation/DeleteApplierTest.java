package com.docgen.mutation;

import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.DeleteMutation;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeleteApplierTest {

    private final DeleteApplier applier = new DeleteApplier(new BookmarkResolver());
    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();

    @Test
    void deletedParagraphGoneFromIndex() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphs("Alpha", "Bravo", "Charlie");
        new BookmarkIndexer().ensureBookmarks(document);

        applier.apply(document, new DeleteMutation("delete", "dg_p1"));

        List<String> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (BlockDescriptor block : indexBuilder.build(document, "doc").blocks()) {
            ids.add(block.targetId());
            texts.add(block.text());
        }
        assertFalse(ids.contains("dg_p1"));
        assertEquals(List.of("Alpha", "Charlie"), texts);
        assertEquals(List.of("dg_p0", "dg_p2"), ids, "surviving ids must not shift");
    }

    @Test
    void tableCellDeleteRejected() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        new BookmarkIndexer().ensureBookmarks(document);

        assertThrows(IllegalArgumentException.class,
                () -> applier.apply(document, new DeleteMutation("delete", "dg_tbl0_r0_c0")));
    }
}
