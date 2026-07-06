package com.docgen.index;

import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookmarkIndexerTest {

    private final BookmarkIndexer indexer = new BookmarkIndexer();

    @Test
    void addsParagraphBookmark() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.singleParagraph("Hello");
        indexer.ensureBookmarks(document);
        assertTrue(BookmarkIndexer.collectBookmarkNames(document).contains("dg_p0"));
    }

    @Test
    void isIdempotent() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.singleParagraph("Hello");
        indexer.ensureBookmarks(document);
        int firstCount = BookmarkIndexer.collectBookmarkNames(document).size();
        indexer.ensureBookmarks(document);
        assertEquals(firstCount, BookmarkIndexer.collectBookmarkNames(document).size());
    }

    @Test
    void addsTableCellBookmarks() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        indexer.ensureBookmarks(document);
        var names = BookmarkIndexer.collectBookmarkNames(document);
        assertTrue(names.contains("dg_tbl0_r0_c0"));
        assertTrue(names.contains("dg_tbl0_r2_c2"));
        assertEquals(9, names.size());
    }
}
