package com.docgen.recovery;

import com.docgen.config.AppProperties;
import com.docgen.document.DocumentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    private CheckpointStore store;
    private static final String DOC = "work.docx";

    @BeforeEach
    void setUp() throws Exception {
        DocumentLoader loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
        store = new CheckpointStore(loader, new ObjectMapper());
    }

    @Test
    void saveAndLoadSnapshotRoundTrip() {
        byte[] snapshot = {10, 20, 30};

        CheckpointMeta meta = store.create(DOC, snapshot, "proposal-1", "Before approve");
        byte[] loaded = store.loadSnapshot(DOC, meta.checkpointId());

        assertArrayEquals(snapshot, loaded);
        assertEquals("proposal-1", meta.proposalId());
        assertEquals("Before approve", meta.changeSummary());
    }

    @Test
    void listReturnsCheckpointsNewestFirst() throws Exception {
        CheckpointMeta first = store.create(DOC, new byte[]{1}, "p1", "first");
        Thread.sleep(5);
        CheckpointMeta second = store.create(DOC, new byte[]{2}, "p2", "second");

        List<CheckpointMeta> checkpoints = store.list(DOC);
        assertEquals(2, checkpoints.size());
        assertEquals(second.checkpointId(), checkpoints.get(0).checkpointId());
        assertEquals(first.checkpointId(), checkpoints.get(1).checkpointId());
    }
}
