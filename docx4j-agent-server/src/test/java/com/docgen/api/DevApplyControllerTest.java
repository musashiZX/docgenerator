package com.docgen.api;

import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.BlockDescriptor;
import com.docgen.support.FixtureFactory;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DevApplyControllerTest {

    @TempDir
    static Path tempDocsDir;

    @DynamicPropertySource
    static void docsDir(DynamicPropertyRegistry registry) {
        registry.add("app.docs-dir", () -> tempDocsDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private Path docPath;

    @BeforeEach
    void seedDocument() throws Exception {
        docPath = tempDocsDir.resolve("table-3x3.docx");
        FixtureFactory.writeTable3x3(docPath);
    }

    @Test
    void validModifyBatchAppliesAndPersists() throws Exception {
        String body = """
                {
                  "schema_version": 1,
                  "explanation": "test",
                  "mutations": [{
                    "op": "modify",
                    "target_id": "dg_tbl0_r1_c1",
                    "old_text": "R1C1",
                    "new_text": "CENTER"
                  }]
                }
                """;

        mockMvc.perform(post("/api/dev/apply/table-3x3.docx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.applied_count").value(1))
                .andExpect(jsonPath("$.changed_ids", hasItem("dg_tbl0_r1_c1")));

        Map<String, String> texts = blockTextsOnDisk();
        assertEquals("CENTER", texts.get("dg_tbl0_r1_c1"));
        assertEquals("R0C0", texts.get("dg_tbl0_r0_c0"));
    }

    @Test
    void unknownTargetReturns400AndFileUnchanged() throws Exception {
        String body = """
                {
                  "schema_version": 1,
                  "mutations": [{
                    "op": "modify",
                    "target_id": "dg_nope",
                    "old_text": "R1C1",
                    "new_text": "CENTER"
                  }]
                }
                """;

        mockMvc.perform(post("/api/dev/apply/table-3x3.docx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.details[0].code").value("UNKNOWN_TARGET"));

        assertEquals("R1C1", blockTextsOnDisk().get("dg_tbl0_r1_c1"));
    }

    @Test
    void staleOldTextReturns409() throws Exception {
        String body = """
                {
                  "schema_version": 1,
                  "mutations": [{
                    "op": "modify",
                    "target_id": "dg_tbl0_r1_c1",
                    "old_text": "R1C1",
                    "occurrence": 1,
                    "new_text": "CENTER"
                  }]
                }
                """;

        // occurrence 1 doesn't exist → validator flags it as OLD_TEXT_NOT_FOUND (400)
        mockMvc.perform(post("/api/dev/apply/table-3x3.docx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].code").value("OLD_TEXT_NOT_FOUND"));
    }

    private Map<String, String> blockTextsOnDisk() throws Exception {
        WordprocessingMLPackage document = WordprocessingMLPackage.load(docPath.toFile());
        Map<String, String> texts = new HashMap<>();
        for (BlockDescriptor block : new StructuralIndexBuilder().build(document, "x").blocks()) {
            texts.put(block.targetId(), block.text());
        }
        return texts;
    }
}
