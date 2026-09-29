package com.docgen.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileVersionCacheTest {

    @Test
    void repeatedCallsWithNoFileChangeSkipRecompute(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("doc.txt");
        Files.writeString(file, "v1");
        FileVersionCache<String> cache = new FileVersionCache<>();
        AtomicInteger computeCount = new AtomicInteger();

        String first = cache.get("doc", file, () -> {
            computeCount.incrementAndGet();
            return "computed:" + Files.readString(file);
        });
        String second = cache.get("doc", file, () -> {
            computeCount.incrementAndGet();
            return "computed:" + Files.readString(file);
        });

        assertEquals("computed:v1", first);
        assertEquals("computed:v1", second);
        assertEquals(1, computeCount.get(), "second call must be a cache hit, not a recompute");
    }

    @Test
    void fileChangeInvalidatesTheCache(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("doc.txt");
        Files.writeString(file, "v1");
        FileVersionCache<String> cache = new FileVersionCache<>();
        AtomicInteger computeCount = new AtomicInteger();

        cache.get("doc", file, () -> {
            computeCount.incrementAndGet();
            return Files.readString(file);
        });

        // Ensure the mtime actually differs (some filesystems have 1s/2s
        // resolution) before rewriting, so this test isn't flaky.
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5000));
        Files.writeString(file, "v2");

        String afterChange = cache.get("doc", file, () -> {
            computeCount.incrementAndGet();
            return Files.readString(file);
        });

        assertEquals("v2", afterChange);
        assertEquals(2, computeCount.get(), "a changed file must force a recompute");
    }

    @Test
    void manualInvalidateForcesRecomputeEvenIfFileUnchanged(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("doc.txt");
        Files.writeString(file, "v1");
        FileVersionCache<String> cache = new FileVersionCache<>();
        AtomicInteger computeCount = new AtomicInteger();

        cache.get("doc", file, () -> {
            computeCount.incrementAndGet();
            return "x";
        });
        cache.invalidate("doc");
        cache.get("doc", file, () -> {
            computeCount.incrementAndGet();
            return "x";
        });

        assertEquals(2, computeCount.get());
    }

    @Test
    void differentKeysAreCachedIndependently(@TempDir Path tempDir) throws Exception {
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "a");
        Files.writeString(fileB, "b");
        FileVersionCache<String> cache = new FileVersionCache<>();
        AtomicInteger computeCount = new AtomicInteger();

        String a = cache.get("a", fileA, () -> {
            computeCount.incrementAndGet();
            return Files.readString(fileA);
        });
        String b = cache.get("b", fileB, () -> {
            computeCount.incrementAndGet();
            return Files.readString(fileB);
        });

        assertEquals("a", a);
        assertEquals("b", b);
        assertEquals(2, computeCount.get());
    }
}
