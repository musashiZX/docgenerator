package com.docgen.index;

import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlainTextExtractorTest {

    @Test
    void concatenatesMultiRunParagraph() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.multiRunParagraph("soy", ", wheat");
        P paragraph = (P) document.getMainDocumentPart().getContent().getFirst();
        assertEquals("soy, wheat", PlainTextExtractor.extractFromParagraph(paragraph));
        assertEquals(2, PlainTextExtractor.countRunsInParagraph(paragraph));
    }
}
