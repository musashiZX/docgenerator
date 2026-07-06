package com.docgen.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuralIndexTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripJson() throws Exception {
        StructuralIndex index = new StructuralIndex(
                "sample.docx",
                List.of(new BlockDescriptor(
                        "dg_p0",
                        "paragraph",
                        "Hello",
                        5,
                        1,
                        0,
                        "Normal",
                        null,
                        null,
                        null)));

        String json = mapper.writeValueAsString(index);
        StructuralIndex restored = mapper.readValue(json, StructuralIndex.class);

        assertEquals("sample.docx", restored.documentId());
        assertEquals(1, restored.blockCount());
        assertEquals("dg_p0", restored.blocks().getFirst().targetId());
        assertEquals(5, restored.blocks().getFirst().charCount());
    }
}
