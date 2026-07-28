package com.docgen.document;

import com.docgen.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DocumentMetadataStoreTest {

    @TempDir
    Path tempDir;

    private DocumentMetadataStore store;

    @BeforeEach
    void setUp() throws Exception {
        DocumentLoader loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
        store = new DocumentMetadataStore(loader, new ObjectMapper());
    }

    @Test
    void saveReadAndTouchUpdated() throws Exception {
        DocumentMetadata meta = DocumentMetadata.uploaded("doc.docx", "orig.docx", 100);
        store.save(meta);

        DocumentMetadata loaded = store.read("doc.docx");
        assertEquals("upload", loaded.source());
        assertEquals(100, loaded.sizeBytes());

        java.nio.file.Files.write(tempDir.resolve("docs/doc.docx"), new byte[200]);
        store.touchUpdated("doc.docx");

        DocumentMetadata touched = store.read("doc.docx");
        assertEquals(200, touched.sizeBytes());
        assertNotNull(touched.updatedAt());
    }
}
