package com.docgen.mutation;

import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModifyApplierTest {

    private final BookmarkIndexer indexer = new BookmarkIndexer();
    private final ModifyApplier applier = new ModifyApplier(new BookmarkResolver());
    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();

    @Test
    void happyPathSingleRun() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.singleParagraph("Hello");
        indexer.ensureBookmarks(document);

        applier.apply(document, modify("dg_p0", "Hello", "Hello world"));

        assertEquals("Hello world", blockTexts(document).get("dg_p0"));
    }

    @Test
    void happyPathMultiRun() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.boldThenNormal("soy", ", wheat");
        indexer.ensureBookmarks(document);

        applier.apply(document, modify("dg_p0", "soy, wheat", "milk"));

        assertEquals("milk", blockTexts(document).get("dg_p0"));
    }

    @Test
    void tableCellModify() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        indexer.ensureBookmarks(document);

        applier.apply(document, modify("dg_tbl0_r1_c1", "R1C1", "CENTER"));

        assertEquals("CENTER", blockTexts(document).get("dg_tbl0_r1_c1"));
    }

    @Test
    void wrongOldTextThrowsStaleTargetAndLeavesDocumentUntouched() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        indexer.ensureBookmarks(document);
        Map<String, String> before = blockTexts(document);

        assertThrows(StaleTargetException.class,
                () -> applier.apply(document, modify("dg_tbl0_r1_c1", "NOT THERE", "x")));

        assertEquals(before, blockTexts(document));
    }

    @Test
    void otherBlocksUnchangedAfterModify() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        indexer.ensureBookmarks(document);
        Map<String, String> before = blockTexts(document);

        applier.apply(document, modify("dg_tbl0_r1_c1", "R1C1", "CENTER"));

        Map<String, String> after = blockTexts(document);
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String expected = entry.getKey().equals("dg_tbl0_r1_c1") ? "CENTER" : entry.getValue();
            assertEquals(expected, after.get(entry.getKey()), "block " + entry.getKey());
        }
    }

    private static ModifyMutation modify(String targetId, String oldText, String newText) {
        return new ModifyMutation("modify", targetId, oldText, 0, newText);
    }

    private Map<String, String> blockTexts(WordprocessingMLPackage document) throws Exception {
        Map<String, String> texts = new HashMap<>();
        for (BlockDescriptor block : indexBuilder.build(document, "test").blocks()) {
            texts.put(block.targetId(), block.text());
        }
        return texts;
    }
}
