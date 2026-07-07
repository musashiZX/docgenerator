package com.docgen.mutation;

import com.docgen.support.FixtureFactory;
import org.docx4j.TextUtils;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RunEditorTest {

    @Test
    void singleRunReplaceKeepsRunAndRpr() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.boldThenNormal("Hello world", ""));
        R boldRun = runsOf(paragraph).getFirst();

        RunEditor.replaceSpan(paragraph, 6, 11, "docx4j");

        assertEquals("Hello docx4j", TextUtils.getText(paragraph));
        List<R> runsAfter = runsOf(paragraph);
        assertSame(boldRun, runsAfter.getFirst(), "edited run must be the same instance");
        assertNotNull(runsAfter.getFirst().getRPr());
        assertNotNull(runsAfter.getFirst().getRPr().getB(), "bold rPr must survive the edit");
    }

    @Test
    void runsOutsideSpanUntouched() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.boldThenNormal("AAA", "BBB"));

        RunEditor.replaceSpan(paragraph, 0, 3, "XX");

        assertEquals("XXBBB", TextUtils.getText(paragraph));
        List<R> runs = runsOf(paragraph);
        assertEquals(2, runs.size());
    }

    @Test
    void crossRunReplaceProducesCorrectText() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.boldThenNormal("soy", ", wheat"));

        RunEditor.replaceSpan(paragraph, 0, 10, "milk");

        assertEquals("milk", TextUtils.getText(paragraph));
    }

    @Test
    void crossRunReplacementInheritsFirstRunBold() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.boldThenNormal("soy", ", wheat"));
        R boldRun = runsOf(paragraph).getFirst();

        RunEditor.replaceSpan(paragraph, 0, 10, "milk");

        List<R> runs = runsOf(paragraph);
        assertEquals(1, runs.size(), "fully-consumed second run must be removed");
        assertSame(boldRun, runs.getFirst());
        assertNotNull(runs.getFirst().getRPr().getB(), "replacement inherits bold from first spanned run");
    }

    @Test
    void crossRunPartialKeepsTailOfLastRun() throws Exception {
        P paragraph = firstParagraph(FixtureFactory.boldThenNormal("soy", ", wheat, milk"));

        // replace "soy, wheat" keeping ", milk"
        RunEditor.replaceSpan(paragraph, 0, 10, "nuts");

        assertEquals("nuts, milk", TextUtils.getText(paragraph));
        List<R> runs = runsOf(paragraph);
        assertEquals(2, runs.size());
        assertNull(runs.getLast().getRPr(), "tail run keeps its original (absent) rPr");
    }

    private static P firstParagraph(WordprocessingMLPackage document) {
        return (P) XmlUtils.unwrap(document.getMainDocumentPart().getContent().getFirst());
    }

    private static List<R> runsOf(P paragraph) {
        List<R> runs = new ArrayList<>();
        for (Object node : paragraph.getContent()) {
            if (XmlUtils.unwrap(node) instanceof R run) {
                runs.add(run);
            }
        }
        return runs;
    }
}
