package com.docgen.recovery;

import com.docgen.config.AppProperties;
import com.docgen.document.DocumentLoader;
import com.docgen.support.FixtureFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentWorkspaceTest {

    @TempDir
    Path tempDir;

    private DocumentLoader loader;
    private DocumentWorkspace workspace;
    private CommitStore commitStore;
    private CheckpointStore checkpointStore;
    private Path docPath;
    private static final String DOC = "work.docx";

    @BeforeEach
    void setUp() throws Exception {
        loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        commitStore = new CommitStore(loader, mapper);
        checkpointStore = new CheckpointStore(loader, mapper);
        workspace = new DocumentWorkspace(loader, commitStore, checkpointStore);

        docPath = loader.docsDirectory().resolve(DOC);
        FixtureFactory.writeParagraphs(docPath, "Alpha", "Bravo");
    }

    @Test
    void ensureInitialCommitCreatesHeadWhenMissing() {
        assertTrue(commitStore.getHead(DOC).isEmpty());

        CommitMeta initial = workspace.ensureInitialCommit(DOC);

        assertEquals("Initial import", initial.message());
        assertEquals(initial.commitId(), commitStore.getHead(DOC).orElseThrow().commitId());
        assertArrayEquals(workspace.readShadow(DOC), commitStore.loadSnapshot(DOC, initial.commitId()));
    }

    @Test
    void commitThenShadowBytesEqualCommittedSnapshot() throws Exception {
        workspace.ensureInitialCommit(DOC);
        FixtureFactory.writeParagraphs(docPath, "Changed", "Bravo");

        CommitMeta committed = workspace.commit(DOC, "v2");
        byte[] shadow = workspace.readShadow(DOC);
        byte[] snapshot = commitStore.loadSnapshot(DOC, committed.commitId());

        assertArrayEquals(shadow, snapshot);
        assertEquals(committed.commitId(), commitStore.getHead(DOC).orElseThrow().commitId());
    }

    @Test
    void restoreCommitRewindsShadowToOlderCommit() throws Exception {
        byte[] original = Files.readAllBytes(docPath);
        CommitMeta initial = workspace.ensureInitialCommit(DOC);

        FixtureFactory.writeParagraphs(docPath, "Newer", "Text");
        CommitMeta second = workspace.commit(DOC, "v2");

        workspace.restoreCommit(DOC, initial.commitId());

        assertArrayEquals(original, workspace.readShadow(DOC));
        assertEquals(initial.commitId(), commitStore.getHead(DOC).orElseThrow().commitId());
        assertTrue(commitStore.list(DOC).stream().anyMatch(c -> c.commitId().equals(second.commitId())));
    }

    @Test
    void restoreCheckpointRestoresShadowWithoutMovingHead() throws Exception {
        CommitMeta head = workspace.ensureInitialCommit(DOC);
        byte[] atHead = workspace.readShadow(DOC);

        FixtureFactory.writeParagraphs(docPath, "Edited", "Bravo");
        byte[] edited = workspace.readShadow(DOC);
        CheckpointMeta checkpoint = workspace.createCheckpointOnApprove(DOC, "p1", atHead);

        workspace.restoreCheckpoint(DOC, checkpoint.checkpointId());

        assertArrayEquals(atHead, workspace.readShadow(DOC));
        assertEquals(head.commitId(), commitStore.getHead(DOC).orElseThrow().commitId());
        assertArrayEquals(atHead, checkpointStore.loadSnapshot(DOC, checkpoint.checkpointId()));
    }
}
