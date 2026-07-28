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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FunctionalTestDevControllerTest {

    @TempDir
    static Path tempRepo;

    static Path docsDir;
    static Path goldenDir;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        docsDir = tempRepo.resolve("server-docs");
        goldenDir = tempRepo.resolve("docs");
        Files.createDirectories(docsDir);
        Files.createDirectories(goldenDir);
        registry.add("app.docs-dir", () -> docsDir.toString());
        registry.add("app.repo-root", () -> tempRepo.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private static final String DOC = "table-3x3.docx";

    @BeforeEach
    void seedGoldenAndCatalog() throws Exception {
        Path golden = goldenDir.resolve(DOC);
        FixtureFactory.writeTable3x3(golden);

        String catalog = """
                {
                  "document": "%s",
                  "golden_path": "docs/%s",
                  "tests": []
                }
                """.formatted(DOC, DOC);
        Files.writeString(goldenDir.resolve("xyz-functional-tests.json"), catalog);
    }

    @Test
    void catalogReturnsJsonFromRepoRoot() throws Exception {
        mockMvc.perform(get("/api/dev/functional-tests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document").value(DOC));
    }

    @Test
    void resetCopiesGoldenWhenWorkingMissing() throws Exception {
        Path golden = goldenDir.resolve(DOC);
        Path working = docsDir.resolve(DOC);
        Files.deleteIfExists(working);

        mockMvc.perform(post("/api/dev/functional-tests/reset/" + DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        assertTrue(Files.exists(working));
        assertEquals(Files.size(golden), Files.size(working));
    }
}
