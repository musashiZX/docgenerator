package com.docgen.index;

import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.CTMarkupRange;
import org.docx4j.wml.P;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
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

    /** Bookmarks each cell in a newly inserted row; returns cell target ids in column order. */
    public List<String> bookmarkNewTableRow(
            WordprocessingMLPackage document, int tableIndex, List<P> cellParagraphs) {
        Set<String> existing = collectBookmarkNames(document);
        int row = nextFreeTableRow(existing, tableIndex);
        while (true) {
            boolean free = true;
            for (int col = 0; col < cellParagraphs.size(); col++) {
                if (existing.contains("dg_tbl" + tableIndex + "_r" + row + "_c" + col)) {
                    free = false;
                    break;
                }
            }
            if (free) {
                break;
            }
            row++;
        }
        List<String> ids = new ArrayList<>();
        long nextId = maxBookmarkNumericId(document) + 1;
        for (int col = 0; col < cellParagraphs.size(); col++) {
            String name = "dg_tbl" + tableIndex + "_r" + row + "_c" + col;
            addBookmark(cellParagraphs.get(col), name, nextId++);
            existing.add(name);
            ids.add(name);
        }
        return ids;
    }

    /** Bookmarks each cell in a newly inserted column; returns cell target ids in row order. */
    public List<String> bookmarkNewTableColumn(
            WordprocessingMLPackage document, int tableIndex, List<P> cellParagraphs) {
        Set<String> existing = collectBookmarkNames(document);
        int col = nextFreeTableColAcross(existing, tableIndex);
        while (true) {
            boolean free = true;
            for (int row = 0; row < cellParagraphs.size(); row++) {
                if (existing.contains("dg_tbl" + tableIndex + "_r" + row + "_c" + col)) {
                    free = false;
                    break;
                }
            }
            if (free) {
                break;
            }
            col++;
        }
        List<String> ids = new ArrayList<>();
        long nextId = maxBookmarkNumericId(document) + 1;
        for (int row = 0; row < cellParagraphs.size(); row++) {
            String name = "dg_tbl" + tableIndex + "_r" + row + "_c" + col;
            addBookmark(cellParagraphs.get(row), name, nextId++);
            existing.add(name);
            ids.add(name);
        }
        return ids;
    }

    static int nextFreeTableRow(Set<String> existing, int tableIndex) {
        int max = -1;
        String prefix = "dg_tbl" + tableIndex + "_r";
        for (String name : existing) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            int row = parseTableRow(name, tableIndex);
            if (row >= 0) {
                max = Math.max(max, row);
            }
        }
        return max + 1;
    }

    static int nextFreeTableCol(Set<String> existing, int tableIndex, int row) {
        int max = -1;
        String prefix = "dg_tbl" + tableIndex + "_r" + row + "_c";
        for (String name : existing) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(name.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return max + 1;
    }

    static int nextFreeTableColAcross(Set<String> existing, int tableIndex) {
        int max = -1;
        String prefix = "dg_tbl" + tableIndex + "_r";
        for (String name : existing) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            int col = parseTableCol(name, tableIndex);
            if (col >= 0) {
                max = Math.max(max, col);
            }
        }
        return max + 1;
    }

    static int nextFreeTableRowForCol(Set<String> existing, int tableIndex, int col) {
        int row = 0;
        while (existing.contains("dg_tbl" + tableIndex + "_r" + row + "_c" + col)) {
            row++;
        }
        return row;
    }

    static int parseTableRow(String name, int tableIndex) {
        String prefix = "dg_tbl" + tableIndex + "_r";
        if (!name.startsWith(prefix)) {
            return -1;
        }
        int cAt = name.indexOf("_c", prefix.length());
        if (cAt < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length(), cAt));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int parseTableCol(String name, int tableIndex) {
        String prefix = "dg_tbl" + tableIndex + "_r";
        if (!name.startsWith(prefix)) {
            return -1;
        }
        int cAt = name.indexOf("_c", prefix.length());
        if (cAt < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(cAt + 2));
        } catch (NumberFormatException e) {
            return -1;
        }
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
