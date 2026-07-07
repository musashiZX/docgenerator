package com.docgen.index;

import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockTextIndexTest {

    @Test
    void singleRunSpanCoversFullText() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.singleParagraph("Hello"));
        BlockTextIndex index = BlockTextIndex.of(paragraph);

        assertEquals("Hello", index.fullText());
        assertEquals(0, index.findSpan("Hello", 0));
        List<BlockTextIndex.Segment> segments = index.segmentsOverlapping(0, 5);
        assertEquals(1, segments.size());
        assertEquals(0, segments.getFirst().start());
        assertEquals(5, segments.getFirst().end());
    }

    @Test
    void multiRunSpanCrossesRuns() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.boldThenNormal("soy", ", wheat"));
        BlockTextIndex index = BlockTextIndex.of(paragraph);

        assertEquals("soy, wheat", index.fullText());
        int start = index.findSpan("soy, wheat", 0);
        assertEquals(0, start);
        List<BlockTextIndex.Segment> segments = index.segmentsOverlapping(start, start + 10);
        assertEquals(2, segments.size());
    }

    @Test
    void occurrenceFindsSecondMatch() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.singleParagraph("soy and soy"));
        BlockTextIndex index = BlockTextIndex.of(paragraph);

        assertEquals(0, index.findSpan("soy", 0));
        assertEquals(8, index.findSpan("soy", 1));
        assertEquals(-1, index.findSpan("soy", 2));
    }

    private static P firstParagraph(WordprocessingMLPackage document) {
        return (P) org.docx4j.XmlUtils.unwrap(document.getMainDocumentPart().getContent().getFirst());
    }
}
