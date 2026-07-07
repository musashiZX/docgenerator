package com.docgen.api;

import com.docgen.support.FixtureFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Test
    void listReturnsSeededDocument() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'single-paragraph.docx')]").exists());
    }

    @Test
    void downloadReturnsDocxBytes() throws Exception {
        mockMvc.perform(get("/api/documents/single-paragraph.docx/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("single-paragraph.docx")));
    }

    @Test
    void uploadStoresValidDocx() throws Exception {
        byte[] content = docxBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "uploaded-doc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content);

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.name").value("uploaded-doc.docx"));

        mockMvc.perform(get("/api/documents/uploaded-doc.docx/index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].text").value("Uploaded"));
    }

    @Test
    void uploadRejectsNonDocxContent() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.docx", "application/octet-stream", "not a docx".getBytes());

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isBadRequest());
    }

    private static byte[] docxBytes() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        FixtureFactory.singleParagraph("Uploaded").save(out);
        return out.toByteArray();
    }
}
