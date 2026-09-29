package com.docgen.document;

import com.docgen.index.BookmarkIndexer;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Upload and listing helpers — ensures bookmarks and metadata sidecars. */
@Service
public class DocumentLibraryService {

    private final DocumentLoader documentLoader;
    private final BookmarkIndexer bookmarkIndexer;
    private final DocumentMetadataStore metadataStore;

    public DocumentLibraryService(
            DocumentLoader documentLoader,
            BookmarkIndexer bookmarkIndexer,
            DocumentMetadataStore metadataStore) {
        this.documentLoader = documentLoader;
        this.bookmarkIndexer = bookmarkIndexer;
        this.metadataStore = metadataStore;
    }

    public StoredDocument storeUpload(String originalName, byte[] content) throws Exception {
        documentLoader.validate(content);
        String name = DocumentLoader.sanitizeFilename(originalName);
        if (!name.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("Only .docx files are supported.");
        }

        WordprocessingMLPackage document = WordprocessingMLPackage.load(new ByteArrayInputStream(content));
        bookmarkIndexer.ensureBookmarks(document);

        Path target = documentLoader.docsDirectory().resolve(name);
        documentLoader.save(document, target);

        DocumentMetadata meta = DocumentMetadata.uploaded(name, originalName, content.length);
        metadataStore.save(meta);
        return new StoredDocument(name, content.length, meta.uploadedAt(), meta.updatedAt());
    }

    public void touchAfterMutation(String docName) {
        metadataStore.touchUpdated(docName);
    }

    public record StoredDocument(
            String name,
            long size,
            java.time.Instant uploadedAt,
            java.time.Instant updatedAt
    ) {
    }
}
