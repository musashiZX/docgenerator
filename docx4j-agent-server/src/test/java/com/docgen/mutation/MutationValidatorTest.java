package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.DeleteMutation;
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
    void emptyOldTextRejected() {
        MutationBatch batch = batch(modify("dg_p0", "", "Hi"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("EMPTY_OLD_TEXT", errors.getFirst().code());
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
                new InsertMutation("insert", "dg_tbl0_r0_c0", "after", "paragraph", "x", null));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, tableIndex);
        assertEquals("UNSUPPORTED_ANCHOR", errors.getFirst().code());
    }

    @Test
    void insertWithBadPositionRejected() {
        MutationBatch batch = batch(
                new InsertMutation("insert", "dg_p0", "above", "paragraph", "x", null));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("BAD_POSITION", errors.getFirst().code());
    }

    @Test
    void insertAnchoredOnDeletedBlockRejected() {
        MutationBatch batch = batch(
                new DeleteMutation("delete", "dg_p0"),
                new InsertMutation("insert", "dg_p0", "after", "paragraph", "x", null));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("ANCHOR_DELETED", errors.getFirst().code());
    }

    @Test
    void deleteTableCellRejected() {
        StructuralIndex tableIndex = new StructuralIndex("doc.docx", List.of(
                new BlockDescriptor("dg_tbl0_r0_c0", "table_cell", "R0C0", 4, 1, 0, "Normal", 0, 0, 0)));
        MutationBatch batch = batch(new DeleteMutation("delete", "dg_tbl0_r0_c0"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, tableIndex);
        assertEquals("UNSUPPORTED_DELETE", errors.getFirst().code());
    }

    @Test
    void modifyAndDeleteSameTargetRejected() {
        MutationBatch batch = batch(
                modify("dg_p0", "Hello", "Hi"),
                new DeleteMutation("delete", "dg_p0"));

        List<MutationValidator.ValidationError> errors = validator.validate(batch, index);
        assertEquals("DUPLICATE_TARGET", errors.getFirst().code());
    }

    @Test
    void validMixedBatchHasNoErrors() {
        MutationBatch batch = batch(
                modify("dg_p0", "Hello", "Hi"),
                new InsertMutation("insert", "dg_p0", "after", "paragraph", "New paragraph", null));

        assertTrue(validator.validate(batch, index).isEmpty());
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
