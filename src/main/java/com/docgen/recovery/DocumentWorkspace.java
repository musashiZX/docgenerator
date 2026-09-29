package com.docgen.recovery;

import com.docgen.document.DocumentLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Manages the shadow working document, HEAD commits, and auto checkpoints.
 */
@Service
public class DocumentWorkspace {

    private static final String INITIAL_COMMIT_MESSAGE = "Initial import";

    private final DocumentLoader documentLoader;
    private final CommitStore commitStore;
    private final CheckpointStore checkpointStore;

    public DocumentWorkspace(
            DocumentLoader documentLoader,
            CommitStore commitStore,
            CheckpointStore checkpointStore) {
        this.documentLoader = documentLoader;
        this.commitStore = commitStore;
        this.checkpointStore = checkpointStore;
    }

    /** Creates an initial commit from the current shadow if none exists yet. */
    public CommitMeta ensureInitialCommit(String docName) {
        if (commitStore.getHead(docName).isPresent()) {
            return commitStore.getHead(docName).orElseThrow();
        }
        byte[] shadow = readShadow(docName);
        return commitStore.create(docName, INITIAL_COMMIT_MESSAGE, shadow, null);
    }

    public CommitMeta commit(String docName, String message) {
        byte[] shadow = readShadow(docName);
        return commitStore.create(docName, message, shadow, null);
    }

    public void restoreCommit(String docName, String commitId) {
        byte[] snapshot = commitStore.loadSnapshot(docName, commitId);
        writeShadow(docName, snapshot);
        commitStore.setHead(docName, commitId);
    }

    public void restoreCheckpoint(String docName, String checkpointId) {
        byte[] snapshot = checkpointStore.loadSnapshot(docName, checkpointId);
        writeShadow(docName, snapshot);
    }

    public CheckpointMeta createCheckpointOnApprove(
            String docName, String proposalId, byte[] beforeBytes) {
        String summary = proposalId != null ? "Before approve " + proposalId : "Before approve";
        return checkpointStore.create(docName, beforeBytes, proposalId, summary);
    }

    /** Snapshots the pre-edit state before a manual (OnlyOffice) save overwrites it. */
    public CheckpointMeta createCheckpointBeforeManualEdit(String docName, byte[] beforeBytes) {
        return checkpointStore.create(docName, beforeBytes, null, "Before manual edit (OnlyOffice)");
    }

    public Optional<CommitMeta> getHead(String docName) {
        return commitStore.getHead(docName);
    }

    public byte[] readShadow(String docName) {
        Path path = documentLoader.resolveDoc(docName);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read shadow document " + docName, e);
        }
    }

    private void writeShadow(String docName, byte[] bytes) {
        Path path = documentLoader.resolveDoc(docName);
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write shadow document " + docName, e);
        }
    }
}
