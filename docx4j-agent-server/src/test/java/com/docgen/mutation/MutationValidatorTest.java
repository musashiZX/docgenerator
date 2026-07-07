package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
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
