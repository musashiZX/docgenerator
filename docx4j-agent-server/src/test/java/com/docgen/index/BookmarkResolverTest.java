package com.docgen.index;

import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookmarkResolverTest {

    private final BookmarkIndexer indexer = new BookmarkIndexer();
    private final BookmarkResolver resolver = new BookmarkResolver();

    @Test
    void resolvesParagraphBookmark() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.singleParagraph("Hello");
        indexer.ensureBookmarks(document);

        P paragraph = resolver.resolve(document, "dg_p0");
        assertEquals("Hello", org.docx4j.TextUtils.getText(paragraph).trim());
    }

    @Test
    void unknownIdThrows() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.singleParagraph("Hello");
        indexer.ensureBookmarks(document);

        assertThrows(
                BookmarkResolver.UnknownTargetException.class,
                () -> resolver.resolve(document, "dg_missing"));
    }
}
