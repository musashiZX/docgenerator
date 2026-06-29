package com.docgen.docio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Folder-based document database: {@code docs/*.docx} plus {@code docs/.meta/*.json} sidecars.
 */
public class DocumentStore {

    private final Path docsDir;
    private final Path metaDir;
    private final ObjectMapper mapper;

    public DocumentStore(Path docsDir) throws IOException {
        this.docsDir = docsDir;
        this.metaDir = docsDir.resolve(".meta");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Files.createDirectories(docsDir);
        Files.createDirectories(metaDir);
    }

    public Path docsDirectory() {
        return docsDir;
    }

    public Path metaPath(String filename) {
        return metaDir.resolve(filename + ".json");
    }

    public void saveMetadata(DocumentMetadata metadata) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(metaPath(metadata.filename()).toFile(), metadata);
        } catch (IOException e) {
            throw new DocIoService.DocIoException("Could not save document metadata.", e);
        }
    }

    public DocumentMetadata readMetadata(String filename) {
        Path path = metaPath(filename);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return mapper.readValue(path.toFile(), DocumentMetadata.class);
        } catch (IOException e) {
            throw new DocIoService.DocIoException("Could not read document metadata.", e);
        }
    }

    public void touchUpdated(String filename) {
        Path docPath = docsDir.resolve(filename);
        if (!Files.exists(docPath)) {
            return;
        }
        try {
            long size = Files.size(docPath);
            DocumentMetadata existing = readMetadata(filename);
            if (existing != null) {
                saveMetadata(existing.withUpdated(java.time.Instant.now(), size));
            } else {
                saveMetadata(DocumentMetadata.created(filename, size));
            }
        } catch (IOException e) {
            throw new DocIoService.DocIoException("Could not update document metadata.", e);
        }
    }

    /** Pick a unique .docx filename inside {@code docs/} (adds -2, -3, … if needed). */
    public String uniqueFilename(String desiredName) {
        String safe = DocIoService.sanitizeFilename(desiredName);
        if (!safe.toLowerCase().endsWith(".docx")) {
            safe = stripExtension(safe) + ".docx";
        }
        if (!Files.exists(docsDir.resolve(safe))) {
            return safe;
        }
        String stem = stripExtension(safe);
        for (int n = 2; n < 10_000; n++) {
            String candidate = stem + "-" + n + ".docx";
            if (!Files.exists(docsDir.resolve(candidate))) {
                return candidate;
            }
        }
        throw new DocIoService.DocIoException("Too many files with the same name: " + safe);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
