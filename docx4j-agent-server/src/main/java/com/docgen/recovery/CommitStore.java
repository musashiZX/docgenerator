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
 * Git-like commit snapshots under {@code docs/.commits/{docName}/{commitId}/}.
 */
@Component
public class CommitStore {

    private final Path commitsRoot;
    private final ObjectMapper mapper;

    public CommitStore(DocumentLoader documentLoader, ObjectMapper mapper) throws IOException {
        this.commitsRoot = documentLoader.docsDirectory().resolve(".commits");
        this.mapper = mapper.copy().findAndRegisterModules();
        Files.createDirectories(commitsRoot);
    }

    public CommitMeta create(String docName, String message, byte[] snapshot, String author) {
        String safeName = DocumentLoader.sanitizeFilename(docName);
        Path docDir = commitsRoot.resolve(safeName);
        String parentId = HeadPointer.read(docDir).orElse(null);
        String commitId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();

        CommitMeta meta = new CommitMeta(commitId, docName, message, parentId, now, author);
        Path commitDir = docDir.resolve(commitId);
        try {
            Files.createDirectories(commitDir);
            Files.write(commitDir.resolve("snapshot.docx"), snapshot);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(commitDir.resolve("meta.json").toFile(), meta);
            HeadPointer.write(docDir, commitId);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create commit " + commitId, e);
        }
        return meta;
    }

    public Optional<CommitMeta> getHead(String docName) {
        String safeName = DocumentLoader.sanitizeFilename(docName);
        Path docDir = commitsRoot.resolve(safeName);
        return HeadPointer.read(docDir).flatMap(id -> loadMeta(docName, id));
    }

    public void setHead(String docName, String commitId) {
        loadMeta(docName, commitId).orElseThrow(() ->
                new IllegalArgumentException("Unknown commit: " + commitId));
        Path docDir = commitsRoot.resolve(DocumentLoader.sanitizeFilename(docName));
        HeadPointer.write(docDir, commitId);
    }

    public List<CommitMeta> list(String docName) {
        String safeName = DocumentLoader.sanitizeFilename(docName);
        Path docDir = commitsRoot.resolve(safeName);
        if (!Files.isDirectory(docDir)) {
            return List.of();
        }
        List<CommitMeta> commits = new ArrayList<>();
        try (var dirs = Files.list(docDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                loadMeta(docName, dir.getFileName().toString()).ifPresent(commits::add);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list commits for " + docName, e);
        }
        commits.sort(Comparator.comparing(CommitMeta::createdAt).reversed());
        return commits;
    }

    public byte[] loadSnapshot(String docName, String commitId) {
        Path snapshot = commitDir(docName, commitId).resolve("snapshot.docx");
        try {
            return Files.readAllBytes(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read commit snapshot " + commitId, e);
        }
    }

    public Optional<CommitMeta> loadMeta(String docName, String commitId) {
        Path metaFile = commitDir(docName, commitId).resolve("meta.json");
        if (!Files.exists(metaFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(metaFile.toFile(), CommitMeta.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read commit meta " + commitId, e);
        }
    }

    private Path commitDir(String docName, String commitId) {
        return commitsRoot
                .resolve(DocumentLoader.sanitizeFilename(docName))
                .resolve(commitId);
    }
}
