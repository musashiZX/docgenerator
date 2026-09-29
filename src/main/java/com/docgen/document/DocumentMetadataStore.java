package com.docgen.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Persists {@code docs/.meta/{name}.json} sidecars for the document library. */
@Component
public class DocumentMetadataStore {

    private final Path metaDir;
    private final ObjectMapper mapper;

    public DocumentMetadataStore(DocumentLoader documentLoader, ObjectMapper objectMapper) throws IOException {
        this.metaDir = documentLoader.docsDirectory().resolve(".meta");
        this.mapper = objectMapper.copy().findAndRegisterModules();
        Files.createDirectories(metaDir);
    }

    public void save(DocumentMetadata metadata) {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(metaPath(metadata.filename()).toFile(), metadata);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save metadata for " + metadata.filename(), e);
        }
    }

    public DocumentMetadata read(String filename) {
        Path path = metaPath(filename);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return mapper.readValue(path.toFile(), DocumentMetadata.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read metadata for " + filename, e);
        }
    }

    /** Bump {@code updated_at} after a mutation save; create a sidecar if missing. */
    public void touchUpdated(String filename) {
        Path docPath = metaDir.getParent().resolve(filename);
        if (!Files.exists(docPath)) {
            return;
        }
        try {
            long size = Files.size(docPath);
            DocumentMetadata existing = read(filename);
            if (existing != null) {
                save(existing.withUpdated(Instant.now(), size));
            } else {
                save(DocumentMetadata.created(filename, size));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not touch metadata for " + filename, e);
        }
    }

    private Path metaPath(String filename) {
        return metaDir.resolve(filename + ".json");
    }
}
