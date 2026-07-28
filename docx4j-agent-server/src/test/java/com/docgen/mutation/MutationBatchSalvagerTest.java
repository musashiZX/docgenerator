package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationBatchSalvagerTest {

    private final StructuralIndex index = new StructuralIndex("doc.docx", List.of(
            block("dg_p0", "Alpha"),
            block("dg_tbl4_r2_c2", "Food Safety in this cell")));

    @Test
    void dropsOldTextNotFoundAndKeepsValidMutations() {
        MutationBatch batch = new MutationBatch(1, "replace", List.of(
                new ModifyMutation("modify", "dg_p0", "Alpha", 0, "Alpha!"),
                new ModifyMutation("modify", "dg_tbl4_r2_c2",
                        "TRN-01, TRN-02, TRN-", 0, "TRN-01, TRN-02, TRN-03")));

        List<MutationValidator.ValidationError> errors = new MutationValidator().validate(batch, index);
        Optional<MutationBatch> salvaged = MutationBatchSalvager.dropInvalid(batch, errors);

        assertTrue(salvaged.isPresent());
        assertEquals(1, salvaged.get().mutations().size());
        assertEquals("dg_p0", ((ModifyMutation) salvaged.get().mutations().get(0)).targetId());
        assertTrue(salvaged.get().explanation().contains("Dropped 1 invalid"));
        assertTrue(new MutationValidator().validate(salvaged.get(), index).isEmpty());
    }

    @Test
    void returnsEmptyWhenAllMutationsInvalid() {
        MutationBatch batch = new MutationBatch(1, "bad", List.of(
                new ModifyMutation("modify", "dg_nope", "x", 0, "y")));
        List<MutationValidator.ValidationError> errors = new MutationValidator().validate(batch, index);
        assertTrue(MutationBatchSalvager.dropInvalid(batch, errors).isEmpty());
    }

    private static BlockDescriptor block(String id, String text) {
        return new BlockDescriptor(id, "paragraph", text, text.length(), 1, 0, "Normal", null, null, null);
    }
}
