package com.docgen.recovery;

import com.docgen.document.DocumentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Auto snapshots under {@code docs/.checkpoints/{docName}/{checkpointId}/}.
 */
@Component
public class CheckpointStore {

    private final Path checkpointsRoot;
    private final ObjectMapper mapper;

    public CheckpointStore(DocumentLoader documentLoader, ObjectMapper mapper) throws IOException {
        this.checkpointsRoot = documentLoader.docsDirectory().resolve(".checkpoints");
        this.mapper = mapper.copy().findAndRegisterModules();
        Files.createDirectories(checkpointsRoot);
    }

    public CheckpointMeta create(
            String docName,
            byte[] snapshot,
            String proposalId,
            String changeSummary) {
        String checkpointId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        CheckpointMeta meta = new CheckpointMeta(checkpointId, docName, proposalId, changeSummary, now);
        Path dir = checkpointDir(docName, checkpointId);
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("snapshot.docx"), snapshot);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("meta.json").toFile(), meta);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create checkpoint " + checkpointId, e);
        }
        return meta;
    }

    public List<CheckpointMeta> list(String docName) {
        Path docDir = checkpointsRoot.resolve(DocumentLoader.sanitizeFilename(docName));
        if (!Files.isDirectory(docDir)) {
            return List.of();
        }
        List<CheckpointMeta> checkpoints = new ArrayList<>();
        try (var dirs = Files.list(docDir)) {
            dirs.filter(Files::isDirectory).forEach(dir ->
                    loadMeta(docName, dir.getFileName().toString()).ifPresent(checkpoints::add));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list checkpoints for " + docName, e);
        }
        checkpoints.sort(Comparator.comparing(CheckpointMeta::createdAt).reversed());
        return checkpoints;
    }

    public byte[] loadSnapshot(String docName, String checkpointId) {
        Path snapshot = checkpointDir(docName, checkpointId).resolve("snapshot.docx");
        try {
            return Files.readAllBytes(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read checkpoint snapshot " + checkpointId, e);
        }
    }

    public Optional<CheckpointMeta> loadMeta(String docName, String checkpointId) {
        Path metaFile = checkpointDir(docName, checkpointId).resolve("meta.json");
        if (!Files.exists(metaFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(metaFile.toFile(), CheckpointMeta.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read checkpoint meta " + checkpointId, e);
        }
    }

    private Path checkpointDir(String docName, String checkpointId) {
        return checkpointsRoot
                .resolve(DocumentLoader.sanitizeFilename(docName))
                .resolve(checkpointId);
    }
}
