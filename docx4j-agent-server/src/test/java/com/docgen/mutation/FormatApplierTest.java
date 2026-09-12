package com.docgen.mutation;

import com.docgen.index.BlockTextIndex;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.FormatMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.support.FixtureFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatApplierTest {

    private final FormatApplier applier = new FormatApplier(new BookmarkResolver());
    private final BookmarkResolver resolver = new BookmarkResolver();

    @Test
    void boldsOnlyTheGivenSubstringAndSplitsRuns() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphs("before commencing any work today");
        new BookmarkIndexer().ensureBookmarks(document);

        applier.apply(document, new FormatMutation(
                "format", "dg_p0", "commencing any work", 0, true, null, null, null, null));

        P paragraph = resolver.resolve(document, "dg_p0");
        BlockTextIndex index = BlockTextIndex.of(paragraph);
        assertEquals("before commencing any work today", index.fullText(), "text must be unchanged");

        List<R> runs = runsOf(paragraph);
        // Expect at least 3 runs after the split: "before ", "commencing any work", " today".
        assertTrue(runs.size() >= 3, "expected the run to split at the format boundaries");

        StringBuilder boldText = new StringBuilder();
        StringBuilder plainText = new StringBuilder();
        for (R run : runs) {
            String text = plainTextOf(run);
            boolean bold = run.getRPr() != null && run.getRPr().getB() != null
                    && Boolean.TRUE.equals(run.getRPr().getB().isVal());
            if (bold) {
                boldText.append(text);
            } else {
                plainText.append(text);
            }
        }
        assertEquals("commencing any work", boldText.toString());
        assertEquals("before  today", plainText.toString());
    }

    @Test
    void wholeParagraphFormattingAppliesToAllRuns() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphWithRuns("Hello", " ", "world");
        new BookmarkIndexer().ensureBookmarks(document);

        applier.apply(document, new FormatMutation(
                "format", "dg_p0", null, null, null, true, null, null, null));

        P paragraph = resolver.resolve(document, "dg_p0");
        for (R run : runsOf(paragraph)) {
            assertTrue(run.getRPr() != null && run.getRPr().getI() != null
                    && Boolean.TRUE.equals(run.getRPr().getI().isVal()));
        }
    }

    @Test
    void alignmentSetsParagraphJustification() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphs("Center me");
        new BookmarkIndexer().ensureBookmarks(document);

        applier.apply(document, new FormatMutation(
                "format", "dg_p0", null, null, null, null, null, null, "center"));

        P paragraph = resolver.resolve(document, "dg_p0");
        assertEquals(JcEnumeration.CENTER, paragraph.getPPr().getJc().getVal());
    }

    @Test
    void fontSizeStoredInHalfPoints() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphs("Bigger text");
        new BookmarkIndexer().ensureBookmarks(document);

        applier.apply(document, new FormatMutation(
                "format", "dg_p0", null, null, null, null, null, 14, null));

        P paragraph = resolver.resolve(document, "dg_p0");
        R run = runsOf(paragraph).get(0);
        assertEquals(28, run.getRPr().getSz().getVal().intValue());
    }

    @Test
    void underlineFalseClearsUnderline() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphs("Plain again");
        new BookmarkIndexer().ensureBookmarks(document);
        applier.apply(document, new FormatMutation(
                "format", "dg_p0", null, null, null, null, true, null, null));
        applier.apply(document, new FormatMutation(
                "format", "dg_p0", null, null, null, null, false, null, null));

        P paragraph = resolver.resolve(document, "dg_p0");
        R run = runsOf(paragraph).get(0);
        assertEquals(org.docx4j.wml.UnderlineEnumeration.NONE, run.getRPr().getU().getVal());
    }

    @Test
    void staleTextThrows() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphs("Some text here");
        new BookmarkIndexer().ensureBookmarks(document);

        assertThrows(StaleTargetException.class, () -> applier.apply(document, new FormatMutation(
                "format", "dg_p0", "nonexistent phrase", 0, true, null, null, null, null)));
    }

    /**
     * The exact reported bug scenario: an empty table cell gets new text
     * typed in, then bolded and centered — the block editor's two-phase
     * save (modify lands first, then format targets the now-existing text).
     */
    @Test
    void formatWorksOnTextJustInsertedIntoAPreviouslyEmptyCell() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table2x2WithEmptyCell();
        new BookmarkIndexer().ensureBookmarks(document);
        StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();

        Map<String, String> before = indexBuilder.build(document, "doc").blocks().stream()
                .collect(Collectors.toMap(BlockDescriptor::targetId, b -> b.text() == null ? "" : b.text()));
        String emptyCellId = before.entrySet().stream()
                .filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).findFirst().orElseThrow();

        ModifyApplier modifyApplier = new ModifyApplier(resolver);
        modifyApplier.apply(document, new ModifyMutation("modify", emptyCellId, "", 0, "New cell value"));
        applier.apply(document, new FormatMutation(
                "format", emptyCellId, "New cell value", 0, true, null, null, null, "center"));

        P cellParagraph = resolver.resolve(document, emptyCellId);
        BlockTextIndex textIndex = BlockTextIndex.of(cellParagraph);
        assertEquals("New cell value", textIndex.fullText());
        assertEquals(JcEnumeration.CENTER, cellParagraph.getPPr().getJc().getVal());
        for (R run : runsOf(cellParagraph)) {
            assertTrue(run.getRPr() != null && run.getRPr().getB() != null
                    && Boolean.TRUE.equals(run.getRPr().getB().isVal()));
        }

        // sibling cells untouched
        Map<String, String> after = indexBuilder.build(document, "doc").blocks().stream()
                .collect(Collectors.toMap(BlockDescriptor::targetId, b -> b.text() == null ? "" : b.text()));
        assertEquals("R0C0", after.get("dg_tbl0_r0_c0"));
        assertEquals("R1C0", after.get("dg_tbl0_r1_c0"));
        assertEquals("R1C1", after.get("dg_tbl0_r1_c1"));
    }

    private static List<R> runsOf(P paragraph) {
        return paragraph.getContent().stream()
                .map(XmlUtils::unwrap)
                .filter(o -> o instanceof R)
                .map(o -> (R) o)
                .toList();
    }

    private static String plainTextOf(R run) {
        StringBuilder sb = new StringBuilder();
        for (Object child : run.getContent()) {
            Object unwrapped = XmlUtils.unwrap(child);
            if (unwrapped instanceof org.docx4j.wml.Text text) {
                sb.append(text.getValue() == null ? "" : text.getValue());
            }
        }
        return sb.toString();
    }
}
