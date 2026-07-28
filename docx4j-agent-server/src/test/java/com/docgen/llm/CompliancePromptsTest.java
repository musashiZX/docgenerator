package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.FocusBlock;
import com.docgen.model.StructuralIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompliancePromptsTest {

    private static final StructuralIndex INDEX = new StructuralIndex("doc.docx", List.of(
            block("dg_p0", "Alpha"),
            block("dg_p1", "Bravo")));

    @Test
    void userMessageIncludesMultipleFocusBlocks() {
        String msg = CompliancePrompts.userMessage(
                "edit these",
                INDEX,
                List.of(
                        new FocusBlock("dg_p13", "bullet one"),
                        new FocusBlock("dg_p14", "bullet two")),
                null);

        assertTrue(msg.contains("USER FOCUSED THESE BLOCKS"));
        assertTrue(msg.contains("dg_p13: bullet one"));
        assertTrue(msg.contains("dg_p14: bullet two"));
        assertTrue(msg.contains("DOCUMENT BLOCKS"));
        assertTrue(msg.contains("edit these"));
    }

    @Test
    void legacySelectedTextStillWorks() {
        String msg = CompliancePrompts.userMessage("fix it", INDEX, List.of(), "legacy excerpt");

        assertTrue(msg.contains("USER FOCUSED THESE BLOCKS"));
        assertTrue(msg.contains("legacy excerpt"));
    }

    @Test
    void userMessageIncludesTableRowSummaries() {
        StructuralIndex withTable = new StructuralIndex("doc.docx", List.of(
                block("dg_p0", "Intro"),
                new BlockDescriptor("dg_tbl0_r1_c1", "table_cell", "Is this product organic?",
                        24, 1, 0, "Normal", 0, 1, 1),
                new BlockDescriptor("dg_tbl0_r1_c2", "table_cell", "No",
                        2, 1, 0, "Normal", 0, 1, 2)));

        String msg = CompliancePrompts.userMessage("add a row", withTable, List.of(), null);

        assertTrue(msg.contains("TABLE ROWS"));
        assertTrue(msg.contains("example_cell=dg_tbl0_r1"));
        assertTrue(msg.contains("Is this product organic?"));
    }

    private static BlockDescriptor block(String id, String text) {
        return new BlockDescriptor(id, "paragraph", text, text.length(), 1, 0, "Normal", null, null, null);
    }
}
