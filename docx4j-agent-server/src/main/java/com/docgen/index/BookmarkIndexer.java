package com.docgen.index;

import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.CTMarkupRange;
import org.docx4j.wml.P;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Injects stable dg_* Word bookmarks for addressable blocks. */
@Component
public class BookmarkIndexer {

    private static final String PREFIX = "dg_";

    public void ensureBookmarks(WordprocessingMLPackage document) {
        Set<String> existingNames = collectBookmarkNames(document);
        long nextId = maxBookmarkNumericId(document) + 1;

        // A block that already carries ANY dg_* bookmark keeps it forever —
        // names are identity, not position. Freed names (from deletes) are
        // never reused; new paragraphs get ordinals past the historical max.
        int nextOrdinal = 0;
        for (String name : existingNames) {
            if (name.startsWith("dg_p")) {
                try {
                    nextOrdinal = Math.max(nextOrdinal, Integer.parseInt(name.substring(4)) + 1);
                } catch (NumberFormatException ignored) {
                    // non-numeric suffix (not one of ours) — skip
                }
            }
        }

        for (BodyWalker.Block block : BodyWalker.walk(document)) {
            switch (block) {
                case BodyWalker.ParagraphBlock(var paragraph) -> {
                    if (hasDgBookmark(paragraph)) {
                        continue;
                    }
                    String name = "dg_p" + nextOrdinal++;
                    addBookmark(paragraph, name, nextId++);
                    existingNames.add(name);
                }
                case BodyWalker.TableCellBlock(var tblIdx, var row, var col, var cell, var paragraph) -> {
                    if (hasDgBookmark(paragraph)) {
                        continue;
                    }
                    String name = "dg_tbl" + tblIdx + "_r" + row + "_c" + col;
                    if (!existingNames.contains(name)) {
                        addBookmark(paragraph, name, nextId++);
                        existingNames.add(name);
                    }
                }
            }
        }
    }

    /**
     * Bookmarks a freshly inserted paragraph with the next free {@code dg_p{n}}
     * name. Existing ids are never renumbered, so ids of surrounding blocks
     * stay stable. Call after the paragraph is already in the body.
     */
    public String bookmarkNewParagraph(WordprocessingMLPackage document, P paragraph) {
        int nextOrdinal = 0;
        for (String name : collectBookmarkNames(document)) {
            if (name.startsWith("dg_p")) {
                try {
                    nextOrdinal = Math.max(nextOrdinal, Integer.parseInt(name.substring(4)) + 1);
                } catch (NumberFormatException ignored) {
                    // non-numeric suffix (not one of ours) — skip
                }
            }
        }
        String name = "dg_p" + nextOrdinal;
        addBookmark(paragraph, name, maxBookmarkNumericId(document) + 1);
        return name;
    }

    static void addBookmark(P paragraph, String name, long id) {
        if (hasBookmark(paragraph, name)) {
            return;
        }
        var factory = Context.getWmlObjectFactory();
        BigInteger bookmarkId = BigInteger.valueOf(id);

        CTBookmark start = factory.createCTBookmark();
        start.setName(name);
        start.setId(bookmarkId);

        CTMarkupRange end = factory.createCTMarkupRange();
        end.setId(bookmarkId);

        paragraph.getContent().add(0, start);
        paragraph.getContent().add(end);
    }

    static boolean hasDgBookmark(P paragraph) {
        for (Object node : paragraph.getContent()) {
            Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
            if (unwrapped instanceof CTBookmark bookmark
                    && bookmark.getName() != null
                    && bookmark.getName().startsWith(PREFIX)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasBookmark(P paragraph, String name) {
        for (Object node : paragraph.getContent()) {
            Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
            if (unwrapped instanceof CTBookmark bookmark && name.equals(bookmark.getName())) {
                return true;
            }
        }
        return false;
    }

    static Set<String> collectBookmarkNames(WordprocessingMLPackage document) {
        Set<String> names = new HashSet<>();
        forAllParagraphs(document, paragraph -> {
            for (Object node : paragraph.getContent()) {
                Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
                if (unwrapped instanceof CTBookmark bookmark
                        && bookmark.getName() != null
                        && bookmark.getName().startsWith(PREFIX)) {
                    names.add(bookmark.getName());
                }
            }
        });
        return names;
    }

    private static long maxBookmarkNumericId(WordprocessingMLPackage document) {
        long[] max = {0};
        forAllParagraphs(document, paragraph -> {
            for (Object node : paragraph.getContent()) {
                Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
                if (unwrapped instanceof CTBookmark bookmark && bookmark.getId() != null) {
                    max[0] = Math.max(max[0], bookmark.getId().longValue());
                }
            }
        });
        return max[0];
    }

    private static void forAllParagraphs(WordprocessingMLPackage document, ParagraphConsumer consumer) {
        for (BodyWalker.Block block : BodyWalker.walk(document)) {
            switch (block) {
                case BodyWalker.ParagraphBlock(var paragraph) -> consumer.accept(paragraph);
                case BodyWalker.TableCellBlock(var tblIdx, var row, var col, var cell, var paragraph) ->
                        consumer.accept(paragraph);
            }
        }
    }

    @FunctionalInterface
    private interface ParagraphConsumer {
        void accept(P paragraph);
    }
}
