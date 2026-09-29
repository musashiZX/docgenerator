package com.docgen.mutation;

import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the real bug reported this session: typing into an originally
 * empty table cell silently failed (old_text="" was rejected outright, and
 * even once allowed, RunEditor had nowhere to anchor the new text since an
 * empty cell has zero runs). See RunEditorTest for the lower-level fix and
 * MutationValidatorTest for the validation-side fix.
 */
class ModifyApplierTest {

    private final ModifyApplier applier = new ModifyApplier(new BookmarkResolver());
    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();

    private static Map<String, String> textsById(WordprocessingMLPackage document, StructuralIndexBuilder builder) throws Exception {
        return builder.build(document, "doc").blocks().stream()
                .collect(Collectors.toMap(BlockDescriptor::targetId, b -> b.text() == null ? "" : b.text()));
    }

    @Test
    void emptyOldTextInsertsIntoGenuinelyEmptyParagraph() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.emptyParagraph();
        new BookmarkIndexer().ensureBookmarks(document);

        applier.apply(document, new ModifyMutation("modify", "dg_p0", "", 0, "New content"));

        assertEquals("New content", textsById(document, indexBuilder).get("dg_p0"));
    }

    @Test
    void emptyOldTextInsertsIntoGenuinelyEmptyTableCell() throws Exception {
        // This is the exact reported scenario: "add content in a cell and choose center."
        WordprocessingMLPackage document = FixtureFactory.table2x2WithEmptyCell();
        new BookmarkIndexer().ensureBookmarks(document);
        Map<String, String> before = textsById(document, indexBuilder);
        String emptyCellId = before.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow();

        applier.apply(document, new ModifyMutation("modify", emptyCellId, "", 0, "New cell value"));

        assertEquals("New cell value", textsById(document, indexBuilder).get(emptyCellId));
        // sibling cells must be untouched
        assertEquals("R0C0", textsById(document, indexBuilder).get("dg_tbl0_r0_c0"));
        assertEquals("R1C0", textsById(document, indexBuilder).get("dg_tbl0_r1_c0"));
        assertEquals("R1C1", textsById(document, indexBuilder).get("dg_tbl0_r1_c1"));
    }

    @Test
    void emptyOldTextRejectedWhenParagraphIsNotActuallyEmpty() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.paragraphs("Not empty");
        new BookmarkIndexer().ensureBookmarks(document);

        assertThrows(StaleTargetException.class, () -> applier.apply(
                document, new ModifyMutation("modify", "dg_p0", "", 0, "New content")));
    }

    @Test
    void subsequentModifyOnNowNonEmptyCellWorksNormally() throws Exception {
        // Insert into an empty cell, then modify it again with normal (non-empty)
        // old_text — proves the newly-created run behaves like any other run.
        WordprocessingMLPackage document = FixtureFactory.table2x2WithEmptyCell();
        new BookmarkIndexer().ensureBookmarks(document);
        String emptyCellId = textsById(document, indexBuilder).entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow();

        applier.apply(document, new ModifyMutation("modify", emptyCellId, "", 0, "First value"));
        applier.apply(document, new ModifyMutation("modify", emptyCellId, "First", 0, "Second"));

        assertEquals("Second value", textsById(document, indexBuilder).get(emptyCellId));
    }
}
