package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.InsertMutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableRowAnchorRepairTest {

    @Test
    void repairsWrongAnchorWhenRequestNamesAnotherRow() {
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl0_r33_c1", 0, 33, 1, "Is it mentioned on the packaging?"),
                cell("dg_tbl0_r33_c2", 0, 33, 2, "No"),
                cell("dg_tbl0_r38_c1", 0, 38, 1, "Is this product organic?"),
                cell("dg_tbl0_r38_c2", 0, 38, 2, "No"),
                cell("dg_tbl1_r40_c1", 1, 40, 1, "Is this product part of a fair trade program?"),
                cell("dg_tbl1_r40_c2", 1, 40, 2, "No")));

        MutationBatch wrong = new MutationBatch(1, "bad", List.of(
                new InsertMutation("insert", "dg_tbl1_r40_c1", "before", "table_row",
                        null, null, List.of("Is this product Chinese food?", "Yes"))));

        MutationBatch fixed = TableRowAnchorRepair.repair(
                "Before Is this product organic? No, add a new row: Is this product Chinese food? Yes",
                wrong,
                index);

        InsertMutation insert = (InsertMutation) fixed.mutations().getFirst();
        assertEquals("dg_tbl0_r38_c1", insert.anchorId());
        assertEquals("before", insert.position());
        assertEquals(List.of("Is this product Chinese food?", "Yes"), insert.cells());
    }

    @Test
    void keepsLlmAnchorWhenItAlreadyMatchesNamedRow() {
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl0_r38_c1", 0, 38, 1, "Is this product organic?"),
                cell("dg_tbl0_r38_c2", 0, 38, 2, "No")));

        MutationBatch ok = new MutationBatch(1, "ok", List.of(
                new InsertMutation("insert", "dg_tbl0_r38_c2", "after", "table_row",
                        null, null, List.of("Is this product from German?", "No"))));

        MutationBatch repaired = TableRowAnchorRepair.repair(
                "After Is this product organic? No, add a new row: Is this product from German? / No",
                ok,
                index);

        assertEquals("dg_tbl0_r38_c2", ((InsertMutation) repaired.mutations().getFirst()).anchorId());
    }

    @Test
    void doesNotInventAnchorWhenNamedRowIsMissing() {
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl1_r40_c1", 1, 40, 1, "Is this product part of a fair trade program?"),
                cell("dg_tbl1_r40_c2", 1, 40, 2, "No")));

        MutationBatch wrong = new MutationBatch(1, "bad", List.of(
                new InsertMutation("insert", "dg_tbl1_r40_c1", "before", "table_row",
                        null, null, List.of("Is this product Chinese food?", "Yes"))));

        MutationBatch same = TableRowAnchorRepair.repair(
                "Before Is this product from England? No, add a new row: Is this product Chinese food? Yes",
                wrong,
                index);

        assertEquals("dg_tbl1_r40_c1", ((InsertMutation) same.mutations().getFirst()).anchorId());
    }

    @Test
    void doesNotRedirectAnchorToTheTableCaptionRow() {
        // Reproduces the reported bug: a table's own caption row ("Additives
        // declaration", one merged cell) text-matches the user's phrase
        // "the additives declaration table" better than the real example
        // row ever could — that must not steal the anchor.
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl3_r0_c0", 3, 0, 0, "Additives declaration"),
                cell("dg_tbl3_r1_c0", 3, 1, 0, "E-number"),
                cell("dg_tbl3_r1_c1", 3, 1, 1, "Name"),
                cell("dg_tbl3_r1_c2", 3, 1, 2, "Category / way of use"),
                cell("dg_tbl3_r2_c0", 3, 2, 0, "E223"),
                cell("dg_tbl3_r2_c1", 3, 2, 1, "Antioxidant"),
                cell("dg_tbl3_r2_c2", 3, 2, 2, "Foodadditives")));

        MutationBatch correct = new MutationBatch(1, "add row", List.of(
                new InsertMutation("insert", "dg_tbl3_r2_c0", "after", "table_row",
                        null, null, List.of("E224", "Netherlands", "Drink additives"))));

        MutationBatch result = TableRowAnchorRepair.repair(
                "I want to add more rows in the additives declaration table. "
                        + "Could you please add 2 more rows to it? "
                        + "1. E224 Netherlands Drink additives. 2. E225 Spain Snack additives",
                correct,
                index);

        assertEquals("dg_tbl3_r2_c0", ((InsertMutation) result.mutations().getFirst()).anchorId(),
                "must not redirect onto the single-cell caption row dg_tbl3_r0_c0");
    }

    @Test
    void retryHintDoesNotSuggestTheTableCaptionRow() {
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl3_r0_c0", 3, 0, 0, "Additives declaration"),
                cell("dg_tbl3_r2_c0", 3, 2, 0, "E223"),
                cell("dg_tbl3_r2_c1", 3, 2, 1, "Antioxidant"),
                cell("dg_tbl3_r2_c2", 3, 2, 2, "Foodadditives")));

        String hint = TableRowAnchorRepair.retryHint(
                "add rows to the additives declaration table", index);
        assertTrue(hint.isBlank() || !hint.contains("dg_tbl3_r0_c0"));
    }

    @Test
    void doesNotRedirectAnchorOnabareCommonWordMatch() {
        // Reproduces the GUANGDELI eval failure: the request is about "the
        // additives declaration table", and some unrelated cell elsewhere
        // literally contains the single word "additives". That must NOT pull
        // the anchor away from the model's (correct) choice in the real table.
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl2_r2_c0", 2, 2, 0, "E223"),
                cell("dg_tbl2_r2_c1", 2, 2, 1, "Antioxidant"),
                cell("dg_tbl2_r2_c2", 2, 2, 2, "Food additives"),
                cell("dg_tbl25_r9_c0", 25, 9, 0, "See section"),
                cell("dg_tbl25_r9_c1", 25, 9, 1, "additives")));

        MutationBatch correct = new MutationBatch(1, "add rows", List.of(
                new InsertMutation("insert", "dg_tbl2_r2_c0", "after", "table_row",
                        null, null, List.of("E224", "Netherlands", "Drink additives"))));

        MutationBatch result = TableRowAnchorRepair.repair(
                "In the additives declaration table, add 2 more rows: 1. E224 "
                        + "Netherlands Drink additives. 2. E225 Spain Snack additives",
                correct, index);

        assertEquals("dg_tbl2_r2_c0",
                ((InsertMutation) result.mutations().getFirst()).anchorId());
    }

    @Test
    void doesNotRedirectToARowWithTheWrongColumnCount() {
        // Reproduces a real eval failure: the request describes the NEW
        // row's content using column-name-ish phrasing ("...country of
        // origin China"), which happens to literally match an unrelated
        // 2-column label/value row (dg_tbl0_r5) elsewhere in the document.
        // The correct 3-column component-list row (dg_tbl1_r2) is where the
        // LLM actually anchored — that must be kept, not overridden onto a
        // row whose cell count can't even fit the 3 values being inserted.
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl0_r5_c0", 0, 5, 0, "Country of origin"),
                cell("dg_tbl0_r5_c1", 0, 5, 1, "China"),
                cell("dg_tbl1_r1_c0", 1, 1, 0, "Ingredient"),
                cell("dg_tbl1_r1_c1", 1, 1, 1, "Quantity (%)"),
                cell("dg_tbl1_r1_c2", 1, 1, 2, "Country of origin"),
                cell("dg_tbl1_r2_c0", 1, 2, 0, "SOYBEAN"),
                cell("dg_tbl1_r2_c1", 1, 2, 1, "92%"),
                cell("dg_tbl1_r2_c2", 1, 2, 2, "China"),
                cell("dg_tbl1_r3_c0", 1, 3, 0, "WATER"),
                cell("dg_tbl1_r3_c1", 1, 3, 1, "8%"),
                cell("dg_tbl1_r3_c2", 1, 3, 2, "China")));

        MutationBatch correct = new MutationBatch(1, "add row", List.of(
                new InsertMutation("insert", "dg_tbl1_r3_c0", "after", "table_row",
                        null, null, List.of("SALT", "0.5%", "China"))));

        MutationBatch result = TableRowAnchorRepair.repair(
                "In the component list table, add a new ingredient row: SALT, "
                        + "quantity 0.5%, country of origin China.",
                correct, index);

        assertEquals("dg_tbl1_r3_c0",
                ((InsertMutation) result.mutations().getFirst()).anchorId());
    }

    @Test
    void retryHintListsMatchingRows() {
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl0_r38_c1", 0, 38, 1, "Is this product organic?"),
                cell("dg_tbl0_r38_c2", 0, 38, 2, "No")));

        String hint = TableRowAnchorRepair.retryHint(
                "After Is this product organic? No, add Chinese food", index);
        assertTrue(hint.contains("dg_tbl0_r38"));
        assertTrue(hint.contains("organic"));
    }

    private static BlockDescriptor cell(String id, int table, int row, int col, String text) {
        return new BlockDescriptor(id, "table_cell", text, text.length(), 1, 0, "Normal", table, row, col);
    }
}
