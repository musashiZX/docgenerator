package com.docgen.document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches a value derived from a document file (a built {@code StructuralIndex},
 * a rendered preview HTML string, ...), keyed by the file's own mtime+size —
 * no explicit invalidation needed, since every write path (approve, restore,
 * commit, upload) changes the file's mtime, which naturally busts the cache
 * on the next read. Read-only: never touches the file itself.
 *
 * <p>Only safe to cache immutable results (a built index, a rendered HTML
 * string) — never a live, mutable {@code WordprocessingMLPackage}, since that
 * object gets mutated in place by apply/approve and a shared cached instance
 * could then be corrupted out from under a concurrent reader.
 */
public final class FileVersionCache<V> {

    private record Entry<V>(FileTime mtime, long size, V value) {
    }

    private final ConcurrentHashMap<String, Entry<V>> cache = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface ThrowingSupplier<V> {
        V get() throws Exception;
    }

    /**
     * Returns the cached value if {@code path}'s mtime/size match what was
     * cached under {@code key}; otherwise runs {@code compute}, caches the
     * result against the file's state *after* compute ran (compute may have
     * written the file itself, e.g. to persist newly-added bookmarks), and
     * returns it.
     */
    public V get(String key, Path path, ThrowingSupplier<V> compute) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        Entry<V> cached = cache.get(key);
        if (cached != null && cached.mtime().equals(attrs.lastModifiedTime()) && cached.size() == attrs.size()) {
            return cached.value();
        }
        V value = compute.get();
        BasicFileAttributes freshAttrs = Files.readAttributes(path, BasicFileAttributes.class);
        cache.put(key, new Entry<>(freshAttrs.lastModifiedTime(), freshAttrs.size(), value));
        return value;
    }

    /** Drop any cached value for this key — used when a caller knows the file just changed. */
    public void invalidate(String key) {
        cache.remove(key);
    }
}
