package com.docgen.mutation;

import com.docgen.index.BlockTextIndex;
import com.docgen.support.FixtureFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunEditorTest {

    private static P firstParagraph(WordprocessingMLPackage document) {
        Object body0 = document.getMainDocumentPart().getJaxbElement().getBody().getContent().get(0);
        return (P) XmlUtils.unwrap(body0);
    }

    @Test
    void replaceSpanOnGenuinelyEmptyParagraphInsertsAtStart() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.emptyParagraph();
        P paragraph = firstParagraph(document);
        assertEquals("", BlockTextIndex.of(paragraph).fullText(), "fixture must start with zero runs");

        RunEditor.replaceSpan(paragraph, 0, 0, "New content");

        assertEquals("New content", BlockTextIndex.of(paragraph).fullText());
    }

    @Test
    void replaceSpanOnEmptyParagraphWithEmptyNewTextIsNoOp() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.emptyParagraph();
        P paragraph = firstParagraph(document);

        RunEditor.replaceSpan(paragraph, 0, 0, "");

        assertEquals("", BlockTextIndex.of(paragraph).fullText());
    }

    @Test
    void replaceSpanOnEmptyParagraphRejectsNonZeroSpan() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.emptyParagraph();
        P paragraph = firstParagraph(document);

        assertThrows(IllegalArgumentException.class,
                () -> RunEditor.replaceSpan(paragraph, 1, 1, "x"));
    }
}
