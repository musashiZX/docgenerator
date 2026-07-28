package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkReplacePlannerTest {

    @Test
    void parsesChangeAllInTablesPrompt() {
        Optional<BulkReplacePlanner.ReplaceSpec> spec = BulkReplacePlanner.parse(
                "Change all the 'Food Safety' in the tables to 'Food Safe'");
        assertTrue(spec.isPresent());
        assertEquals("Food Safety", spec.get().search());
        assertEquals("Food Safe", spec.get().replace());
        assertTrue(spec.get().tablesOnly());
    }

    @Test
    void plansOnlyTableCellsContainingSearchText() {
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl0_r1_c3", "Food Safety"),
                cell("dg_tbl0_r6_c3", "Product Quality"),
                cell("dg_tbl2_r5_c1", "TRN-01, TRN-05"),
                para("dg_p0", "Food Safety in title")));

        MutationBatch batch = BulkReplacePlanner.plan(
                new BulkReplacePlanner.ReplaceSpec("Food Safety", "Food Safe", true), index);

        assertEquals(1, batch.mutations().size());
        assertEquals("dg_tbl0_r1_c3", ((ModifyMutation) batch.mutations().get(0)).targetId());
        assertEquals("Food Safety", ((ModifyMutation) batch.mutations().get(0)).oldText());
        assertEquals("Food Safe", ((ModifyMutation) batch.mutations().get(0)).newText());
        assertTrue(new MutationValidator().validate(batch, index).isEmpty());
    }

    @Test
    void tryPlanFromUserMessage() {
        StructuralIndex index = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl0_r3_c3", "Food Safety"),
                cell("dg_tbl0_r4_c3", "Food Safety"),
                cell("dg_tbl3_r1_c1", "TRN-01 through TRN-09")));

        Optional<MutationBatch> batch = BulkReplacePlanner.tryPlan(
                "Change all the 'Food Safety' in the tables to 'Food Safe'", index);

        assertTrue(batch.isPresent());
        assertEquals(2, batch.get().mutations().size());
    }

    private static BlockDescriptor cell(String id, String text) {
        return new BlockDescriptor(id, "table_cell", text, text.length(), 1, 0,
                "TableParagraph", 0, 0, 0);
    }

    private static BlockDescriptor para(String id, String text) {
        return new BlockDescriptor(id, "paragraph", text, text.length(), 1, 0,
                "Normal", null, null, null);
    }
}
