package com.docgen.onlyoffice;

import com.docgen.document.DocumentLoader;
import com.docgen.index.BookmarkIndexer;
import com.docgen.support.FixtureFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real async round trip a "Close" click drives in the
 * browser: this app calls a stub Document Server's forcesave command, the
 * stub (standing in for the real OnlyOffice service) POSTs back to this
 * app's own real /callback endpoint on a background thread — same as the
 * genuine Document Server does — and the forcesave request blocks until
 * that lands. No mocking of the coordination logic itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnlyOfficeForceSaveControllerTest {

    // Bound at class-load time, before @DynamicPropertySource is evaluated,
    // so its (fixed, real) port can be wired into the Spring context.
    private static final HttpServer STUB_SERVER = createStubServer();

    @TempDir
    static Path tempDocsDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.docs-dir", () -> tempDocsDir.toString());
        registry.add("app.onlyoffice.document-server-url",
                () -> "http://127.0.0.1:" + STUB_SERVER.getAddress().getPort());
    }

    private static HttpServer createStubServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @LocalServerPort
    private int appPort;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DocumentLoader documentLoader;
    @Autowired
    private BookmarkIndexer bookmarkIndexer;
    @Autowired
    private ObjectMapper mapper;

    private String docName;

    @BeforeEach
    void setUp() throws Exception {
        removeContextIfPresent("/coauthoring/CommandService.ashx");
        removeContextIfPresent("/new.docx");

        docName = "onlyoffice-forcesave-test.docx";
        Path path = tempDocsDir.resolve(docName);
        FixtureFactory.writeTable3x3(path);
        WordprocessingMLPackage doc = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(doc);
        documentLoader.save(doc, path);
    }

    private static void removeContextIfPresent(String path) {
        try {
            STUB_SERVER.removeContext(path);
        } catch (IllegalArgumentException ignored) {
            // no context registered yet — fine
        }
    }

    private byte[] editedDocBytes() throws Exception {
        WordprocessingMLPackage doc = documentLoader.load(documentLoader.resolveDoc(docName));
        doc.getMainDocumentPart().addParagraphOfText("Added by the stub Document Server.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /** Registers CommandService.ashx (always accepts) plus the "new file" download it points at. */
    private void stubCommandServiceThenCallback(int calledBackStatus) throws Exception {
        byte[] edited = editedDocBytes();
        STUB_SERVER.createContext("/new.docx", exchange -> {
            exchange.sendResponseHeaders(200, edited.length);
            exchange.getResponseBody().write(edited);
            exchange.getResponseBody().close();
        });
        STUB_SERVER.createContext("/coauthoring/CommandService.ashx", exchange -> {
            byte[] resp = "{\"error\":0}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();

            // Mimic the real Document Server: the callback lands shortly
            // after the command is acknowledged, on its own thread.
            new Thread(() -> {
                try {
                    Thread.sleep(150);
                    Map<String, Object> callbackBody = Map.of(
                            "key", "test-key", "status", calledBackStatus,
                            "url", "http://127.0.0.1:" + STUB_SERVER.getAddress().getPort() + "/new.docx");
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    restTemplate.postForEntity(
                            "http://localhost:" + appPort + "/api/onlyoffice/callback/" + docName,
                            new HttpEntity<>(mapper.writeValueAsString(callbackBody), headers),
                            String.class);
                } catch (Exception ignored) {
                    // test will fail on its own assertions if this never lands
                }
            }).start();
        });
    }

    @SuppressWarnings("unchecked")
    @Test
    void forceSaveWaitsForTheCallbackAndReportsSaved() throws Exception {
        stubCommandServiceThenCallback(6);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/onlyoffice/forcesave/" + docName,
                Map.of("key", "test-key"),
                Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("saved", response.getBody().get("status"));

        Path saved = documentLoader.resolveDoc(docName);
        WordprocessingMLPackage reloaded = documentLoader.load(saved);
        var blocks = new com.docgen.index.StructuralIndexBuilder().build(reloaded, docName).blocks();
        assertTrue(blocks.stream().anyMatch(b -> "Added by the stub Document Server.".equals(b.text())));
    }

    @SuppressWarnings("unchecked")
    @Test
    void forceSaveReportsNoChangesWithoutWaitingOnCallback() {
        STUB_SERVER.createContext("/coauthoring/CommandService.ashx", exchange -> {
            byte[] resp = "{\"error\":4}".getBytes(); // "no changes to save"
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/onlyoffice/forcesave/" + docName,
                Map.of("key", "test-key"),
                Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no_changes", response.getBody().get("status"));
    }
}
