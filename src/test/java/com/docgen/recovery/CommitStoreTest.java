package com.docgen.recovery;

import com.docgen.config.AppProperties;
import com.docgen.document.DocumentLoader;
import com.docgen.support.FixtureFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitStoreTest {

    @TempDir
    Path tempDir;

    private CommitStore store;
    private static final String DOC = "work.docx";

    @BeforeEach
    void setUp() throws Exception {
        DocumentLoader loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
        store = new CommitStore(loader, new ObjectMapper());
    }

    @Test
    void saveSnapshotAndMetaRoundTrip() {
        byte[] snapshot = {0x50, 0x4b, 0x03, 0x04};

        CommitMeta created = store.create(DOC, "Baseline", snapshot, "tester");
        byte[] loaded = store.loadSnapshot(DOC, created.commitId());
        CommitMeta meta = store.loadMeta(DOC, created.commitId()).orElseThrow();

        assertArrayEquals(snapshot, loaded);
        assertEquals(DOC, meta.docName());
        assertEquals("Baseline", meta.message());
        assertEquals("tester", meta.author());
        assertTrue(meta.createdAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void setHeadAndGetHeadReturnLatestCommitId() {
        CommitMeta first = store.create(DOC, "v1", new byte[]{1}, null);
        CommitMeta second = store.create(DOC, "v2", new byte[]{2}, null);

        assertEquals(second.commitId(), store.getHead(DOC).orElseThrow().commitId());
        store.setHead(DOC, first.commitId());
        assertEquals(first.commitId(), store.getHead(DOC).orElseThrow().commitId());
    }

    @Test
    void listReturnsCommitsNewestFirst() throws Exception {
        DocumentLoader loader = new DocumentLoader(
                new AppProperties(tempDir.resolve("docs").toString(), null, null, null, false));
        Path docPath = loader.docsDirectory().resolve(DOC);
        FixtureFactory.writeParagraphs(docPath, "Hello");

        CommitMeta older = store.create(DOC, "first", new byte[]{1}, null);
        Thread.sleep(5);
        CommitMeta newer = store.create(DOC, "second", new byte[]{2}, null);

        List<CommitMeta> commits = store.list(DOC);
        assertEquals(2, commits.size());
        assertEquals(newer.commitId(), commits.get(0).commitId());
        assertEquals(older.commitId(), commits.get(1).commitId());
    }
}
