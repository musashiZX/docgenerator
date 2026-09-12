package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.DeleteMutation;
import com.docgen.model.FormatMutation;
import com.docgen.model.InsertMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationValidatorTest {

    private final MutationValidator validator = new MutationValidator();

    private final StructuralIndex index = new StructuralIndex("doc.docx", List.of(
            block("dg_p0", "Hello world"),
            block("dg_tbl0_r1_c1", "R1C1")));

    @Test
    void unknownTargetIdRejected() {
        MutationBatch batch = batch(modify("dg_p999", "Hello", "Hi"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);

        assertEquals(1, errors.size());
        assertEquals("UNKNOWN_TARGET", errors.getFirst().code());
    }

    @Test
    void duplicateTargetIdRejected() {
        MutationBatch batch = batch(
                modify("dg_p0", "Hello", "Hi"),
                modify("dg_p0", "world", "earth"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);

        assertEquals(1, errors.size());
        assertEquals("DUPLICATE_TARGET", errors.getFirst().code());
    }

    @Test
    void validBatchHasNoErrors() {
        MutationBatch batch = batch(
                modify("dg_p0", "Hello", "Hi"),
                modify("dg_tbl0_r1_c1", "R1C1", "CENTER"));

        assertTrue(validator.validate(batch, index).isEmpty());
    }

    @Test
    void emptyOldTextRejectedWhenBlockIsNotActuallyEmpty() {
        // dg_p0's real text is "Hello world" — old_text="" (asserting "this
        // block is currently empty") is a mismatch, same family as
        // OLD_TEXT_NOT_FOUND for a substring that isn't there.
        MutationBatch batch = batch(modify("dg_p0", "", "Hi"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("OLD_TEXT_NOT_FOUND", errors.getFirst().code());
    }

    @Test
    void emptyOldTextAcceptedWhenBlockIsActuallyEmpty() {
        StructuralIndex emptyBlockIndex = new StructuralIndex("doc.docx", List.of(
                block("dg_p9", "")));
        MutationBatch batch = batch(modify("dg_p9", "", "New content"));

        assertTrue(validator.validate(batch, emptyBlockIndex).isEmpty());
    }

    @Test
    void oldTextNotInBlockRejectedEarly() {
        MutationBatch batch = batch(modify("dg_p0", "goodbye", "Hi"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("OLD_TEXT_NOT_FOUND", errors.getFirst().code());
    }

    @Test
    void emptyBatchRejected() {
        MutationBatch batch = new MutationBatch(1, null, List.of());

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("EMPTY_BATCH", errors.getFirst().code());
    }

    @Test
    void noOpModifyRejected() {
        MutationBatch batch = batch(modify("dg_p0", "Hello", "Hello"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("NO_OP_MUTATION", errors.getFirst().code());
    }

    @Test
    void insertOnTableCellAnchorRejected() {
        StructuralIndex tableIndex = new StructuralIndex("doc.docx", List.of(
                new BlockDescriptor("dg_tbl0_r0_c0", "table_cell", "R0C0", 4, 1, 0, "Normal", 0, 0, 0)));
        MutationBatch batch = batch(
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "paragraph", "x", null, null));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, tableIndex);
        assertEquals("UNSUPPORTED_ANCHOR", errors.getFirst().code());
    }

    @Test
    void insertWithBadPositionRejected() {
        MutationBatch batch = batch(
                new InsertMutation("insert", "dg_p0", "above", "paragraph", "x", null, null));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("BAD_POSITION", errors.getFirst().code());
    }

    @Test
    void insertAnchoredOnDeletedBlockRejected() {
        MutationBatch batch = batch(
                new DeleteMutation("delete", "dg_p0", null, "Hello"),
                new InsertMutation("insert", "dg_p0", "after", "paragraph", "x", null, null));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("ANCHOR_DELETED", errors.getFirst().code());
    }

    @Test
    void deleteEmptyParagraphAcceptedWithEmptyEvidenceText() {
        // Real reported gap: an empty paragraph has no text to quote as
        // evidence, so the blanket "evidence_text required" rule made
        // deleting a blank line impossible. Verified against the block's
        // OWN indexed text (empty), not just an empty evidence_text claim.
        StructuralIndex emptyBlockIndex = new StructuralIndex("doc.docx", List.of(
                block("dg_p9", "")));
        MutationBatch batch = batch(new DeleteMutation("delete", "dg_p9", null, ""));

        assertTrue(validator.validate(batch, emptyBlockIndex).isEmpty());
    }

    @Test
    void deleteNonEmptyParagraphStillRejectedWithoutEvidenceText() {
        // The exception must not widen into "evidence_text is optional" —
        // a non-empty block with no evidence is still refused.
        MutationBatch batch = batch(new DeleteMutation("delete", "dg_p0", null, ""));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("MISSING_EVIDENCE_TEXT", errors.getFirst().code());
    }

    @Test
    void deleteTableCellRejected() {
        StructuralIndex tableIndex = new StructuralIndex("doc.docx", List.of(
                new BlockDescriptor("dg_tbl0_r0_c0", "table_cell", "R0C0", 4, 1, 0, "Normal", 0, 0, 0)));
        MutationBatch batch = batch(new DeleteMutation("delete", "dg_tbl0_r0_c0", null, "evidence"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, tableIndex);
        assertEquals("UNSUPPORTED_DELETE", errors.getFirst().code());
    }

    @Test
    void modifyAndDeleteSameTargetRejected() {
        MutationBatch batch = batch(
                modify("dg_p0", "Hello", "Hi"),
                new DeleteMutation("delete", "dg_p0", null, "evidence"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("DUPLICATE_TARGET", errors.getFirst().code());
    }

    @Test
    void validMixedBatchHasNoErrors() {
        MutationBatch batch = batch(
                modify("dg_p0", "Hello", "Hi"),
                new InsertMutation("insert", "dg_p0", "after", "paragraph", "New paragraph", null, null));

        assertTrue(validator.validate(batch, index).isEmpty());
    }

    @Test
    void consecutiveAfterInsertsOnSameAnchorAllowed() {
        MutationBatch batch = batch(
                new InsertMutation("insert", "dg_p0", "after", "paragraph", "Line 1", null, null),
                new InsertMutation("insert", "dg_p0", "after", "paragraph", "Line 2", null, null),
                new InsertMutation("insert", "dg_p0", "after", "paragraph", "Line 3", null, null));

        assertTrue(validator.validate(batch, index).isEmpty());
    }

    private static final StructuralIndex tableIndex = new StructuralIndex("doc.docx", List.of(
            new BlockDescriptor("dg_tbl0_r0_c0", "table_cell", "R0C0", 4, 1, 0, "Normal", 0, 0, 0),
            new BlockDescriptor("dg_tbl0_r0_c1", "table_cell", "R0C1", 4, 1, 1, "Normal", 0, 0, 1)));

    @Test
    void consecutiveAfterTableRowInsertsOnSameAnchorAllowed() {
        // Reproduces the reported "add 2 more rows" scenario at the
        // validator level: two table_row inserts, same anchor+position.
        MutationBatch batch = batch(
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "table_row", null, null,
                        List.of("E224", "Netherlands")),
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "table_row", null, null,
                        List.of("E225", "Spain")));

        assertTrue(validator.validate(batch, tableIndex).isEmpty());
    }

    @Test
    void nonConsecutiveTableRowInsertsOnSameAnchorStillRejected() {
        // A THIRD, unrelated mutation breaking the consecutive run must NOT
        // be treated as chained — genuine duplicates are still caught.
        MutationBatch batch = batch(
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "table_row", null, null,
                        List.of("E224", "Netherlands")),
                new FormatMutation("format", "dg_tbl0_r0_c1", null, null, null, null, null, null, "center"),
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "table_row", null, null,
                        List.of("E225", "Spain")));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, tableIndex);
        assertTrue(errors.stream().anyMatch(e -> "DUPLICATE_TABLE_OP".equals(e.code())));
    }

    private static ModifyMutation modify(String targetId, String oldText, String newText) {
        return new ModifyMutation("modify", targetId, oldText, 0, newText);
    }

    private static MutationBatch batch(Mutation... mutations) {
        return new MutationBatch(1, "test", List.of(mutations));
    }

    private static BlockDescriptor block(String targetId, String text) {
        return new BlockDescriptor(targetId, "paragraph", text, text.length(), 1, 0, "Normal",
                null, null, null);
    }
}
