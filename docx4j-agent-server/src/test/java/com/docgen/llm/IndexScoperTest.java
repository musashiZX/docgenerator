package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.StructuralIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexScoperTest {

    @Test
    void scopesToTableCellsMatchingQuotedTerm() {
        StructuralIndex full = new StructuralIndex("doc.docx", List.of(
                cell("dg_tbl0_r1_c3", "Food Safety"),
                cell("dg_tbl2_r5_c1", "TRN-01"),
                para("dg_p0", "Food Safety title")));

        StructuralIndex scoped = IndexScoper.scopeForLlm(
                "Change all the 'Food Safety' in the tables to 'Food Safe'", full);

        assertEquals(1, scoped.blocks().size());
        assertEquals("dg_tbl0_r1_c3", scoped.blocks().get(0).targetId());
    }

    @Test
    void leavesFullIndexWhenScopeWouldNotShrinkMuch() {
        List<BlockDescriptor> blocks = List.of(
                cell("dg_tbl0_r1_c3", "Food Safety"),
                para("dg_p0", "intro"));
        StructuralIndex full = new StructuralIndex("doc.docx", blocks);

        StructuralIndex scoped = IndexScoper.scopeForLlm("fix typo in 'Food Safety'", full);

        assertEquals(full.blocks().size(), scoped.blocks().size());
    }

    @Test
    void leavesFullIndexWhenAMultiEditRequestQuotesNewTextThatMatchesNoBlockYet() {
        // Reproduces a real eval failure: "change the name to 'X' and change
        // 'Y' to 'Z'" quotes the NEW value for one edit (matches nothing —
        // it doesn't exist yet) and the OLD value for the other (matches
        // exactly one block). With >1 quoted terms the old code skipped the
        // MIN_SHRINK floor entirely, scoping down to that one matched block
        // and silently dropping the block the first edit actually needs —
        // the LLM then saw an incomplete index and wrongly declined the
        // request as impossible.
        StructuralIndex full = new StructuralIndex("doc.docx", List.of(
                para("dg_p0", "Zixuan Chen"),
                para("dg_p1", "China."),
                para("dg_p2", "Graduate from HIT.")));

        StructuralIndex scoped = IndexScoper.scopeForLlm(
                "Change the name to 'Zixuan Chen, MSc' and change the country line "
                        + "from 'China.' to 'China, Netherlands.'",
                full);

        assertEquals(full.blocks().size(), scoped.blocks().size(),
                "must fall back to the full index rather than dropping dg_p0");
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
