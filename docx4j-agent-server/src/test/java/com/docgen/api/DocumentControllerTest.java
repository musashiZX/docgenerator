package com.docgen.api;

import com.docgen.support.FixtureFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentControllerTest {

    @TempDir
    static Path tempDocsDir;

    @DynamicPropertySource
    static void docsDir(DynamicPropertyRegistry registry) {
        registry.add("app.docs-dir", () -> tempDocsDir.toString());
    }

    @BeforeEach
    void seedDocument() throws Exception {
        FixtureFactory.singleParagraph("Hello").save(
                tempDocsDir.resolve("single-paragraph.docx").toFile());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indexReturnsBlocks() throws Exception {
        mockMvc.perform(get("/api/documents/single-paragraph.docx/index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("single-paragraph.docx"))
                .andExpect(jsonPath("$.block_count").value(1))
                .andExpect(jsonPath("$.blocks[0].target_id").value("dg_p0"))
                .andExpect(jsonPath("$.blocks[0].text").value("Hello"))
                .andExpect(jsonPath("$.blocks[0].char_count").value(5))
                .andExpect(jsonPath("$.blocks[0].type").value("paragraph"));
    }
}
