package com.docgen.document;

import com.docgen.index.BookmarkIndexer;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.StructuralIndex;
import com.docgen.recovery.DocumentWorkspace;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class DocumentIndexService {

    private final DocumentLoader documentLoader;
    private final BookmarkIndexer bookmarkIndexer;
    private final StructuralIndexBuilder structuralIndexBuilder;
    private final DocumentWorkspace workspace;

    public DocumentIndexService(
            DocumentLoader documentLoader,
            BookmarkIndexer bookmarkIndexer,
            StructuralIndexBuilder structuralIndexBuilder,
            DocumentWorkspace workspace) {
        this.documentLoader = documentLoader;
        this.bookmarkIndexer = bookmarkIndexer;
        this.structuralIndexBuilder = structuralIndexBuilder;
        this.workspace = workspace;
    }

    public StructuralIndex buildIndex(String docName) throws Exception {
        workspace.ensureInitialCommit(docName);
        Path path = documentLoader.resolveDoc(docName);
        WordprocessingMLPackage document = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(document);
        documentLoader.save(document, path);
        return structuralIndexBuilder.build(document, docName);
    }
}
