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
