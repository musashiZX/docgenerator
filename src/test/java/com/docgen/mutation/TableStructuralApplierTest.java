package com.docgen.mutation;

import com.docgen.document.DocumentSession;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.ApplyResult;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.DeleteMutation;
import com.docgen.model.InsertMutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableStructuralApplierTest {

    private final StructuralIndexBuilder indexBuilder = new StructuralIndexBuilder();
    private final BookmarkIndexer indexer = new BookmarkIndexer();
    private final BookmarkResolver resolver = new BookmarkResolver();
    private final TableStructuralApplier tableApplier = new TableStructuralApplier(resolver, indexer);
    private final MutationApplier applier = new MutationApplier(
            new MutationValidator(),
            new ModifyApplier(resolver),
            new InsertApplier(resolver, indexer),
            new DeleteApplier(resolver),
            tableApplier,
            new FormatApplier(resolver),
            new NodeHashGuard(indexBuilder));

    private DocumentSession session;
    private StructuralIndex index;

    @BeforeEach
    void setUp() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.table3x3();
        indexer.ensureBookmarks(document);
        session = new DocumentSession(document);
        index = indexBuilder.build(document, "table.docx");
    }

    @Test
    void insertRowAfterClonesAndSetsCellTexts() throws Exception {
        MutationBatch batch = new MutationBatch(1, "add row", List.of(
                new InsertMutation("insert", "dg_tbl0_r1_c0", "after", "table_row",
                        null, null, List.of("A", "B", "C"))));

        ApplyResult result = applier.apply(session, batch, index);

        assertEquals(3, result.createdIds().size());
        Map<String, String> texts = cellTexts();
        assertEquals("A", texts.get("dg_tbl0_r3_c0"));
        assertEquals("B", texts.get("dg_tbl0_r3_c1"));
        assertEquals("C", texts.get("dg_tbl0_r3_c2"));
        assertEquals("R1C0", texts.get("dg_tbl0_r1_c0"), "template row unchanged");
        assertEquals(12, texts.size(), "3x3 + 1 row = 12 cells");
    }

    @Test
    void twoConsecutiveAfterRowInsertsOnSameAnchorStackAsSeparateRows() throws Exception {
        // Reproduces the reported bug: "add 2 more rows" to a table, one
        // batch with two table_row inserts on the SAME anchor+position —
        // previously rejected outright as DUPLICATE_TABLE_OP; now the
        // engine chains them like it already does for paragraph inserts.
        MutationBatch batch = new MutationBatch(1, "add two rows", List.of(
                new InsertMutation("insert", "dg_tbl0_r2_c0", "after", "table_row",
                        null, null, List.of("E224", "Netherlands", "Drink additives")),
                new InsertMutation("insert", "dg_tbl0_r2_c0", "after", "table_row",
                        null, null, List.of("E225", "Spain", "Snack additives"))));

        ApplyResult result = applier.apply(session, batch, index);

        assertEquals(6, result.createdIds().size(), "two new rows of 3 cells each");
        Map<String, String> texts = cellTexts();
        assertEquals(15, texts.size(), "3x3 + 2 rows = 15 cells");

        // First new row lands immediately after the anchor (row 2 -> row 3).
        assertEquals("E224", texts.get("dg_tbl0_r3_c0"));
        assertEquals("Netherlands", texts.get("dg_tbl0_r3_c1"));
        assertEquals("Drink additives", texts.get("dg_tbl0_r3_c2"));
        // Second new row stacks after the FIRST new row, not before it.
        assertEquals("E225", texts.get("dg_tbl0_r4_c0"));
        assertEquals("Spain", texts.get("dg_tbl0_r4_c1"));
        assertEquals("Snack additives", texts.get("dg_tbl0_r4_c2"));
        assertEquals("R2C0", texts.get("dg_tbl0_r2_c0"), "anchor row itself unchanged");
    }

    @Test
    void insertColumnAfterAddsCellsPerRow() throws Exception {
        MutationBatch batch = new MutationBatch(1, "add col", List.of(
                new InsertMutation("insert", "dg_tbl0_r0_c1", "after", "table_column",
                        null, null, List.of("X", "Y", "Z"))));

        ApplyResult result = applier.apply(session, batch, index);

        assertEquals(3, result.createdIds().size());
        Map<String, String> texts = cellTexts();
        assertEquals(12, texts.size());
        assertTrue(texts.containsValue("X"));
        assertTrue(texts.containsValue("Y"));
        assertTrue(texts.containsValue("Z"));
        assertEquals("R0C1", texts.get("dg_tbl0_r0_c1"));
    }

    @Test
    void deleteRowRemovesAllCellsInRow() throws Exception {
        MutationBatch batch = new MutationBatch(1, "drop row", List.of(
                new DeleteMutation("delete", "dg_tbl0_r1_c1", "table_row", "R1C1")));

        ApplyResult result = applier.apply(session, batch, index);

        assertTrue(result.changedIds().contains("dg_tbl0_r1_c0"));
        assertTrue(result.changedIds().contains("dg_tbl0_r1_c1"));
        assertTrue(result.changedIds().contains("dg_tbl0_r1_c2"));
        Map<String, String> texts = cellTexts();
        assertEquals(6, texts.size());
        assertFalse(texts.containsKey("dg_tbl0_r1_c0"));
        assertEquals("R0C0", texts.get("dg_tbl0_r0_c0"));
        assertEquals("R2C0", texts.get("dg_tbl0_r2_c0"));
    }

    @Test
    void deleteColumnRemovesAllCellsInColumn() throws Exception {
        MutationBatch batch = new MutationBatch(1, "drop col", List.of(
                new DeleteMutation("delete", "dg_tbl0_r0_c1", "table_column", "R0C1")));

        ApplyResult result = applier.apply(session, batch, index);

        assertEquals(3, result.changedIds().size());
        Map<String, String> texts = cellTexts();
        assertEquals(6, texts.size());
        assertFalse(texts.containsKey("dg_tbl0_r0_c1"));
        assertEquals("R0C0", texts.get("dg_tbl0_r0_c0"));
        assertEquals("R0C2", texts.get("dg_tbl0_r0_c2"));
    }

    @Test
    void validatorAcceptsTableRowInsertOnCellAnchor() {
        MutationBatch batch = new MutationBatch(1, "ok", List.of(
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "table_row",
                        null, null, List.of("a", "b", "c"))));
        assertTrue(new MutationValidator().validate(batch, index).isEmpty());
    }

    @Test
    void insertRowClonesMergedExampleUsingContentSlots() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.qaStyleTable();
        indexer.ensureBookmarks(document);
        session = new DocumentSession(document);
        index = indexBuilder.build(document, "qa.docx");
        assertFalse(TableOps.isDataframe(
                ((org.docx4j.wml.Tbl) org.docx4j.XmlUtils.unwrap(
                        document.getMainDocumentPart().getContent().getFirst()))));

        // Content slots only: question + answer (omit empty spacer)
        MutationBatch batch = new MutationBatch(1, "clone example", List.of(
                new InsertMutation("insert", "dg_tbl0_r1_c1", "after", "table_row",
                        null, null, List.of("Is this product from German?", "No"))));

        ApplyResult result = applier.apply(session, batch, index);

        assertEquals(3, result.createdIds().size());
        Map<String, String> texts = cellTexts();
        assertTrue(texts.containsValue("Is this product from German?"));
        assertTrue(texts.containsValue("No"));
        assertEquals("Is this product organic?", texts.get("dg_tbl0_r1_c1"));
    }

    @Test
    void insertRowIgnoresTrailingEmptyPadOntoContentSlots() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.qaStyleTable();
        indexer.ensureBookmarks(document);
        session = new DocumentSession(document);
        index = indexBuilder.build(document, "qa.docx");

        // LLM often pads to physical width: ["Q", "Yes", ""]. Must NOT put Q in spacer.
        MutationBatch batch = new MutationBatch(1, "padded", List.of(
                new InsertMutation("insert", "dg_tbl0_r1_c1", "before", "table_row",
                        null, null, List.of("Is this product Chinese food?", "Yes", ""))));

        ApplyResult result = applier.apply(session, batch, index);

        assertEquals(3, result.createdIds().size());
        Map<String, String> texts = cellTexts();
        assertTrue(texts.containsValue("Is this product Chinese food?"));
        assertTrue(texts.containsValue("Yes"));

        StructuralIndex after = indexBuilder.build(session.document(), "qa.docx");
        BlockDescriptor question = after.blocks().stream()
                .filter(b -> "Is this product Chinese food?".equals(b.text()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, question.col(), "question must land in content col, not spacer");
        BlockDescriptor answer = after.blocks().stream()
                .filter(b -> question.row().equals(b.row())
                        && question.tableIndex().equals(b.tableIndex())
                        && "Yes".equals(b.text()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, answer.col());
        assertTrue(after.blocks().stream().anyMatch(b ->
                question.row().equals(b.row())
                        && Integer.valueOf(0).equals(b.col())
                        && (b.text() == null || b.text().isBlank())));
    }

    @Test
    void insertRowKeepsMultiRunCellWhenTextUnchanged() throws Exception {
        WordprocessingMLPackage document = FixtureFactory.multiRunCeSizeTable();
        indexer.ensureBookmarks(document);
        session = new DocumentSession(document);
        index = indexBuilder.build(document, "lang.docx");

        // Same CE Size text as the template row — must not collapse 9 spaced runs to 1.
        MutationBatch batch = new MutationBatch(1, "add chinese", List.of(
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "table_row",
                        null, null, List.of("Chinese", "Y",
                                "Minimum 1.2mm high(In regular script)"))));

        applier.apply(session, batch, index);

        org.docx4j.wml.Tbl table = (org.docx4j.wml.Tbl) org.docx4j.XmlUtils.unwrap(
                session.document().getMainDocumentPart().getContent().getFirst());
        org.docx4j.wml.Tr newRow = TableOps.rowsOf(table).get(1);
        org.docx4j.wml.Tc ceCell = TableOps.cellsOf(newRow).get(2);
        org.docx4j.wml.P paragraph = TableOps.firstParagraph(ceCell);
        long runCount = paragraph.getContent().stream()
                .filter(n -> org.docx4j.XmlUtils.unwrap(n) instanceof org.docx4j.wml.R)
                .count();
        assertTrue(runCount >= 5, "expected multi-run CE Size cell, got " + runCount);
        assertEquals("Minimum 1.2mm high(In regular script)",
                com.docgen.index.BlockTextIndex.of(paragraph).fullText());
    }

    private Map<String, String> cellTexts() throws Exception {
        Map<String, String> texts = new HashMap<>();
        for (BlockDescriptor block : indexBuilder.build(session.document(), "doc.docx").blocks()) {
            texts.put(block.targetId(), block.text());
        }
        return texts;
    }
}
