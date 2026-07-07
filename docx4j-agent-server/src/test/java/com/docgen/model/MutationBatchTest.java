package com.docgen.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MutationBatchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesModifyBatchFromContractSample() throws Exception {
        String json = """
                {
                  "schema_version": 1,
                  "explanation": "Add sesame to allergen declaration.",
                  "mutations": [{
                    "op": "modify",
                    "target_id": "dg_tbl0_r3_c2",
                    "old_text": "soy, wheat",
                    "occurrence": 0,
                    "new_text": "soy, wheat, sesame"
                  }]
                }
                """;

        MutationBatch batch = mapper.readValue(json, MutationBatch.class);

        assertEquals(1, batch.schemaVersion());
        assertEquals(1, batch.mutations().size());
        ModifyMutation modify = assertInstanceOf(ModifyMutation.class, batch.mutations().getFirst());
        assertEquals("dg_tbl0_r3_c2", modify.targetId());
        assertEquals("soy, wheat", modify.oldText());
        assertEquals(0, modify.occurrenceOrDefault());
        assertEquals("soy, wheat, sesame", modify.newText());
    }

    @Test
    void occurrenceDefaultsToZeroWhenOmitted() throws Exception {
        String json = """
                {
                  "schema_version": 1,
                  "mutations": [{
                    "op": "modify",
                    "target_id": "dg_p0",
                    "old_text": "a",
                    "new_text": "b"
                  }]
                }
                """;

        MutationBatch batch = mapper.readValue(json, MutationBatch.class);
        ModifyMutation modify = (ModifyMutation) batch.mutations().getFirst();
        assertEquals(0, modify.occurrenceOrDefault());
    }
}
