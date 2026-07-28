package com.docgen.document;

import com.docgen.config.AppProperties;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.PlainTextExtractor;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for PDF-converted docs with inter-word spaces in dedicated runs. */
class GuangdeliSpacePreservationTest {

    private static final Path GUANGDELI = Path.of("docs",
            "21260  GUANGDELI, Dried Beancurd Roll, 25x300g.docx");

    static boolean guangdeliFixturePresent() {
        return Files.isRegularFile(GUANGDELI);
    }

    @Test
    @EnabledIf("guangdeliFixturePresent")
    void saveRoundTripKeepsProductNameLabelSpacing() throws Exception {
        Path work = Files.createTempFile("guangdeli-", ".docx");
        Files.copy(GUANGDELI, work, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        DocumentLoader loader = new DocumentLoader(new AppProperties("docs", null, null, null, false));
        WordprocessingMLPackage document = loader.load(work);
        new BookmarkIndexer().ensureBookmarks(document);
        loader.save(document, work);

        WordprocessingMLPackage reloaded = loader.load(work);
        var paragraph = new BookmarkResolver().resolve(reloaded, "dg_tbl0_r6_c1");
        assertEquals("Product name:", PlainTextExtractor.extractFromParagraph(paragraph));
        assertTrue(cellXml(work).contains("xml:space=\"preserve\""),
                "saved docx must mark whitespace runs with xml:space=preserve");
    }

    private static String cellXml(Path docx) throws Exception {
        try (var zf = new java.util.zip.ZipFile(docx.toFile())) {
            return new String(zf.getInputStream(zf.getEntry("word/document.xml")).readAllBytes());
        }
    }
}
