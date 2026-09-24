package com.docgen.onlyoffice;

import com.docgen.document.DocumentLoader;
import com.docgen.index.BookmarkIndexer;
import com.docgen.recovery.CheckpointMeta;
import com.docgen.recovery.CheckpointStore;
import com.docgen.support.FixtureFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real OnlyOffice save-callback path: JWT verification against
 * a real signed token, an actual HTTP download of the "new" file (a local
 * stub server, not a mock), a checkpoint of the pre-edit state, and
 * bookmark backfill on whatever the office suite handed back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OnlyOfficeCallbackControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long!!";

    @TempDir
    static Path tempDocsDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.docs-dir", () -> tempDocsDir.toString());
        registry.add("app.onlyoffice.jwt-secret", () -> JWT_SECRET);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DocumentLoader documentLoader;
    @Autowired
    private BookmarkIndexer bookmarkIndexer;
    @Autowired
    private OnlyOfficeJwtService jwtService;
    @Autowired
    private CheckpointStore checkpointStore;
    @Autowired
    private ObjectMapper mapper;

    private HttpServer stubServer;
    private String docName;

    @BeforeEach
    void seedBookmarkedDocument() throws Exception {
        docName = "onlyoffice-test.docx";
        Path path = tempDocsDir.resolve(docName);
        FixtureFactory.writeTable3x3(path);

        WordprocessingMLPackage doc = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(doc);
        documentLoader.save(doc, path);
    }

    @AfterEach
    void stopStubServer() {
        if (stubServer != null) stubServer.stop(0);
    }

    /** Serves fixedBytes at /new.docx on a real local HTTP server. */
    private String serveFile(byte[] fixedBytes) throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/new.docx", exchange -> {
            exchange.sendResponseHeaders(200, fixedBytes.length);
            exchange.getResponseBody().write(fixedBytes);
            exchange.getResponseBody().close();
        });
        stubServer.start();
        return "http://127.0.0.1:" + stubServer.getAddress().getPort() + "/new.docx";
    }

    private byte[] docWithNewParagraphAppended() throws Exception {
        Path original = documentLoader.resolveDoc(docName);
        WordprocessingMLPackage doc = documentLoader.load(original);
        doc.getMainDocumentPart().addParagraphOfText("Freshly typed in OnlyOffice, no bookmark yet.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    @Test
    void forceSaveWithValidTokenPersistsChecksAndBackfillsBookmarks() throws Exception {
        byte[] edited = docWithNewParagraphAppended();
        String url = serveFile(edited);
        String token = jwtService.signConfig(Map.of("key", "poc"));

        String body = mapper.writeValueAsString(Map.of(
                "key", "poc", "status", 6, "url", url));

        mockMvc.perform(post("/api/onlyoffice/callback/{name}", docName)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(0));

        // Pre-edit state was checkpointed before the overwrite.
        List<CheckpointMeta> checkpoints = checkpointStore.list(docName);
        assertTrue(checkpoints.stream()
                        .anyMatch(c -> "Before manual edit (OnlyOffice)".equals(c.changeSummary())),
                "expected a pre-edit checkpoint");

        // The saved file now has the manual edit, and every block — the
        // pre-existing bookmarked ones AND the newly typed paragraph — is
        // addressable by the app's own indexer.
        Path saved = documentLoader.resolveDoc(docName);
        WordprocessingMLPackage reloaded = documentLoader.load(saved);
        var blocks = new com.docgen.index.StructuralIndexBuilder().build(reloaded, docName).blocks();
        assertTrue(blocks.stream().anyMatch(b ->
                        "Freshly typed in OnlyOffice, no bookmark yet.".equals(b.text())),
                "manual edit should be present");
        assertTrue(blocks.stream().allMatch(b -> b.targetId() != null && b.targetId().startsWith("dg_")),
                "every block, including the newly typed one, must have a dg_ target_id after backfill");
    }

    @Test
    void editingStatusDoesNotTouchTheFile() throws Exception {
        String token = jwtService.signConfig(Map.of("key", "poc"));
        byte[] before = java.nio.file.Files.readAllBytes(documentLoader.resolveDoc(docName));

        String body = mapper.writeValueAsString(Map.of("key", "poc", "status", 1));

        mockMvc.perform(post("/api/onlyoffice/callback/{name}", docName)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(0));

        byte[] after = java.nio.file.Files.readAllBytes(documentLoader.resolveDoc(docName));
        assertTrue(java.util.Arrays.equals(before, after), "status=1 (editing) must not modify the file");
    }

    @Test
    void missingOrInvalidTokenIsRejected() throws Exception {
        String body = mapper.writeValueAsString(Map.of("key", "poc", "status", 6, "url", "http://example.invalid/x"));

        mockMvc.perform(post("/api/onlyoffice/callback/{name}", docName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(1));

        mockMvc.perform(post("/api/onlyoffice/callback/{name}", docName)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(1));
    }
}
