package com.docgen.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the current HEAD commit id for a document under
 * {@code docs/.commits/{docName}/HEAD}.
 */
final class HeadPointer {

    private HeadPointer() {
    }

    static Optional<String> read(Path docCommitsDir) {
        Path headFile = docCommitsDir.resolve("HEAD");
        if (!Files.exists(headFile)) {
            return Optional.empty();
        }
        try {
            String id = Files.readString(headFile, StandardCharsets.UTF_8).trim();
            return id.isEmpty() ? Optional.empty() : Optional.of(id);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read HEAD for " + docCommitsDir, e);
        }
    }

    static void write(Path docCommitsDir, String commitId) {
        try {
            Files.createDirectories(docCommitsDir);
            Files.writeString(docCommitsDir.resolve("HEAD"), commitId, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write HEAD for " + docCommitsDir, e);
        }
    }
}
