package com.docgen.index;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.StructuralIndex;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuralIndexBuilderTest {

    private final BookmarkIndexer bookmarkIndexer = new BookmarkIndexer();
    private final StructuralIndexBuilder builder = new StructuralIndexBuilder();

    @Test
    void countsParagraphAndTableCells() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        bookmarkIndexer.ensureBookmarks(document);

        StructuralIndex index = builder.build(document, "table-3x3.docx");
        assertEquals(9, index.blockCount());
        assertEquals("table_cell", index.blocks().getFirst().type());
    }

    @Test
    void blockTextMatchesPlainTextExtractor() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        bookmarkIndexer.ensureBookmarks(document);

        StructuralIndex index = builder.build(document, "table-3x3.docx");
        for (BlockDescriptor block : index.blocks()) {
            assertEquals(block.text().length(), block.charCount());
        }
        assertEquals("R1C2", findBlock(index, "dg_tbl0_r1_c2").text());
    }

    @Test
    void paragraphDocumentProducesOneBlock() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.singleParagraph("Hello");
        bookmarkIndexer.ensureBookmarks(document);

        StructuralIndex index = builder.build(document, "single-paragraph.docx");
        assertEquals(1, index.blockCount());
        assertEquals("dg_p0", index.blocks().getFirst().targetId());
        assertEquals("Hello", index.blocks().getFirst().text());
    }

    private static BlockDescriptor findBlock(StructuralIndex index, String targetId) {
        return index.blocks().stream()
                .filter(block -> targetId.equals(block.targetId()))
                .findFirst()
                .orElseThrow();
    }
}
