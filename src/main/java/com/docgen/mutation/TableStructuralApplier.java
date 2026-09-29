package com.docgen.mutation;

import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.model.DeleteMutation;
import com.docgen.model.InsertMutation;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.P;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblGrid;
import org.docx4j.wml.TblGridCol;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Tr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Insert/delete table rows by cloning an example row (including merges), and
 * insert/delete columns on dataframe-shaped tables only.
 */
@Component
public class TableStructuralApplier {

    private static final Logger log = LoggerFactory.getLogger(TableStructuralApplier.class);

    private final BookmarkResolver bookmarkResolver;
    private final BookmarkIndexer bookmarkIndexer;

    public TableStructuralApplier(BookmarkResolver bookmarkResolver, BookmarkIndexer bookmarkIndexer) {
        this.bookmarkResolver = bookmarkResolver;
        this.bookmarkIndexer = bookmarkIndexer;
    }

    /**
     * Clones the anchor row (layout, merges, formatting) and inserts it before/after.
     * Optional {@code cells} fills texts — either 1:1 with physical cells, or only the
     * non-blank content cells from the template (so spacers / vMerge cells can be omitted).
     *
     * @return target ids of the newly created cells (column order).
     */
    public List<String> insertRow(WordprocessingMLPackage document, InsertMutation mutation) {
        return insertRow(document, mutation, mutation.anchorId());
    }

    /**
     * Like {@link #insertRow} but resolves {@code effectiveAnchorId} instead of
     * {@code mutation.anchorId()}. Used when several consecutive "after" row
     * inserts on the same anchor must stack as separate rows — the caller
     * redirects each one after the first onto the row just created by the
     * previous insert (mirrors InsertApplier's paragraph chaining).
     */
    public List<String> insertRow(WordprocessingMLPackage document, InsertMutation mutation, String effectiveAnchorId) {
        P anchorParagraph = bookmarkResolver.resolve(document, effectiveAnchorId);
        TableOps.CellLocation loc = TableOps.locateCell(document, anchorParagraph);
        if (loc == null) {
            throw new IllegalArgumentException(
                    "table_row insert anchor must be a table cell: " + effectiveAnchorId);
        }

        List<Tc> templateCells = TableOps.cellsOf(loc.row());
        List<String> templateTexts = TableOps.cellTexts(loc.row());
        int rowsBefore = TableOps.rowsOf(loc.table()).size();
        int gridCols = gridColCount(loc.table());
        boolean dataframe = TableOps.isDataframe(loc.table());

        log.info(
                "table_row insert: anchor={} effectiveAnchor={} position={} tableIndex={} rowIndex={} colIndex={} "
                        + "physicalCells={} templateTexts={} rowsBefore={} gridCols={} dataframe={} cells={}",
                mutation.anchorId(),
                effectiveAnchorId,
                mutation.position(),
                loc.tableIndex(),
                loc.rowIndex(),
                loc.colIndex(),
                templateCells.size(),
                templateTexts,
                rowsBefore,
                gridCols,
                dataframe,
                mutation.cells());

        Tr clone = XmlUtils.deepCopy(loc.row());
        List<Tc> clonedCells = TableOps.cellsOf(clone);
        List<P> paragraphs = new ArrayList<>();
        for (Tc cell : clonedCells) {
            P paragraph = TableOps.firstParagraph(cell);
            TableOps.stripDgBookmarkPairs(paragraph);
            paragraphs.add(paragraph);
        }
        applyRowCellTexts(clonedCells, templateTexts, mutation.cells(), effectiveAnchorId);

        int rowContentIndex = TableOps.indexOfRow(loc.table(), loc.row());
        int insertAt = "before".equals(mutation.position()) ? rowContentIndex : rowContentIndex + 1;
        loc.table().getContent().add(insertAt, clone);

        List<String> newIds = bookmarkIndexer.bookmarkNewTableRow(
                document, loc.tableIndex(), paragraphs);
        int rowsAfter = TableOps.rowsOf(loc.table()).size();
        log.info(
                "table_row insert done: tableIndex={} insertAt={} rowsAfter={} newCellIds={} "
                        + "(only this Tbl is mutated; following body content may reflow across pages)",
                loc.tableIndex(),
                insertAt,
                rowsAfter,
                newIds);
        return newIds;
    }

    /** @return target ids of the newly created cells (row order). */
    public List<String> insertColumn(WordprocessingMLPackage document, InsertMutation mutation) {
        P anchorParagraph = bookmarkResolver.resolve(document, mutation.anchorId());
        TableOps.CellLocation loc = TableOps.locateCell(document, anchorParagraph);
        if (loc == null) {
            throw new IllegalArgumentException(
                    "table_column insert anchor must be a table cell: " + mutation.anchorId());
        }
        requireDataframe(loc.table(), mutation.anchorId());

        List<Tr> rows = TableOps.rowsOf(loc.table());
        if (mutation.cells() != null && mutation.cells().size() != rows.size()) {
            throw new IllegalArgumentException(
                    "cells length " + mutation.cells().size()
                            + " does not match table row count " + rows.size());
        }

        TblGridCol templateGridCol = gridColAt(loc.table(), loc.colIndex());
        List<P> paragraphs = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            Tr row = rows.get(r);
            List<Tc> cells = TableOps.cellsOf(row);
            Tc template = cells.get(loc.colIndex());
            Tc clone = XmlUtils.deepCopy(template);
            P paragraph = TableOps.firstParagraph(clone);
            TableOps.stripDgBookmarkPairs(paragraph);
            if (mutation.cells() != null) {
                TableOps.setCellTextPreservingFirstRunFormat(clone, mutation.cells().get(r));
            }
            int cellContentIndex = TableOps.indexOfCell(row, template);
            int insertAt = "before".equals(mutation.position()) ? cellContentIndex : cellContentIndex + 1;
            row.getContent().add(insertAt, clone);
            paragraphs.add(paragraph);
        }

        int gridInsertAt = "before".equals(mutation.position()) ? loc.colIndex() : loc.colIndex() + 1;
        TableOps.ensureGridColumn(loc.table(), gridInsertAt, templateGridCol);

        return bookmarkIndexer.bookmarkNewTableColumn(document, loc.tableIndex(), paragraphs);
    }

    /** @return target ids of cells removed with the row. */
    public Set<String> deleteRow(WordprocessingMLPackage document, DeleteMutation mutation) {
        P paragraph = bookmarkResolver.resolve(document, mutation.targetId());
        TableOps.CellLocation loc = TableOps.locateCell(document, paragraph);
        if (loc == null) {
            throw new IllegalArgumentException(
                    "table_row delete target must be a table cell: " + mutation.targetId());
        }

        List<Tr> rows = TableOps.rowsOf(loc.table());
        if (rows.size() <= 1) {
            throw new IllegalArgumentException(
                    "Cannot delete the last remaining row in table " + loc.tableIndex());
        }

        Set<String> removed = cellIdsInRow(loc.row());
        int rowContentIndex = TableOps.indexOfRow(loc.table(), loc.row());
        loc.table().getContent().remove(rowContentIndex);
        return removed;
    }

    /** @return target ids of cells removed with the column. */
    public Set<String> deleteColumn(WordprocessingMLPackage document, DeleteMutation mutation) {
        P paragraph = bookmarkResolver.resolve(document, mutation.targetId());
        TableOps.CellLocation loc = TableOps.locateCell(document, paragraph);
        if (loc == null) {
            throw new IllegalArgumentException(
                    "table_column delete target must be a table cell: " + mutation.targetId());
        }
        requireDataframe(loc.table(), mutation.targetId());

        List<Tr> rows = TableOps.rowsOf(loc.table());
        int colCount = TableOps.cellsOf(rows.getFirst()).size();
        if (colCount <= 1) {
            throw new IllegalArgumentException(
                    "Cannot delete the last remaining column in table " + loc.tableIndex());
        }

        Set<String> removed = new LinkedHashSet<>();
        for (Tr row : rows) {
            List<Tc> cells = TableOps.cellsOf(row);
            Tc cell = cells.get(loc.colIndex());
            removed.addAll(cellIdsInCell(cell));
            int cellContentIndex = TableOps.indexOfCell(row, cell);
            row.getContent().remove(cellContentIndex);
        }
        TableOps.removeGridColumn(loc.table(), loc.colIndex());
        return removed;
    }

    /** Collects dg_* ids for every cell in the same row as {@code targetId}. */
    public Set<String> rowCellIds(WordprocessingMLPackage document, String targetId) {
        P paragraph = bookmarkResolver.resolve(document, targetId);
        TableOps.CellLocation loc = TableOps.locateCell(document, paragraph);
        if (loc == null) {
            return Set.of(targetId);
        }
        return cellIdsInRow(loc.row());
    }

    /** Collects dg_* ids for every cell in the same column as {@code targetId}. */
    public Set<String> columnCellIds(WordprocessingMLPackage document, String targetId) {
        P paragraph = bookmarkResolver.resolve(document, targetId);
        TableOps.CellLocation loc = TableOps.locateCell(document, paragraph);
        if (loc == null) {
            return Set.of(targetId);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Tr row : TableOps.rowsOf(loc.table())) {
            List<Tc> cells = TableOps.cellsOf(row);
            if (loc.colIndex() < cells.size()) {
                ids.addAll(cellIdsInCell(cells.get(loc.colIndex())));
            }
        }
        return ids;
    }

    /**
     * Fills cloned row cells from {@code cells}. Prefers mapping non-blank values onto the
     * example row's non-blank content slots — so LLM pads like {@code ["Q", "Yes", ""]} do
     * not shove the question into an empty spacer column. Falls back to 1:1 physical fill
     * when lengths match and content-slot mapping does not apply.
     */
    static void applyRowCellTexts(
            List<Tc> clonedCells, List<String> templateTexts, List<String> cells, String anchorId) {
        if (cells == null) {
            return;
        }
        List<Integer> contentSlots = new ArrayList<>();
        for (int i = 0; i < templateTexts.size(); i++) {
            String text = templateTexts.get(i);
            if (text != null && !text.isBlank()) {
                contentSlots.add(i);
            }
        }
        List<String> provided = new ArrayList<>();
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                provided.add(cell);
            }
        }
        if (!contentSlots.isEmpty() && provided.size() == contentSlots.size()) {
            for (int i = 0; i < contentSlots.size(); i++) {
                TableOps.setCellTextPreservingFirstRunFormat(
                        clonedCells.get(contentSlots.get(i)), provided.get(i));
            }
            return;
        }
        if (cells.size() == clonedCells.size()) {
            for (int i = 0; i < clonedCells.size(); i++) {
                TableOps.setCellTextPreservingFirstRunFormat(clonedCells.get(i), cells.get(i));
            }
            return;
        }
        if (!contentSlots.isEmpty() && cells.size() == contentSlots.size()) {
            for (int i = 0; i < contentSlots.size(); i++) {
                TableOps.setCellTextPreservingFirstRunFormat(
                        clonedCells.get(contentSlots.get(i)), cells.get(i));
            }
            return;
        }
        throw new IllegalArgumentException(
                "cells length " + cells.size() + " does not match template row at " + anchorId
                        + " (physical cells=" + clonedCells.size()
                        + ", content cells=" + contentSlots.size()
                        + ", non-blank provided=" + provided.size()
                        + ", template texts=" + templateTexts + ")");
    }

    private static void requireDataframe(Tbl table, String id) {
        if (!TableOps.isDataframe(table)) {
            throw new IllegalArgumentException(
                    "table_column ops only support dataframe-shaped tables (no merges, "
                            + "uniform columns). Rejected for: " + id);
        }
    }

    private static Set<String> cellIdsInRow(Tr row) {
        Set<String> ids = new LinkedHashSet<>();
        for (Tc cell : TableOps.cellsOf(row)) {
            ids.addAll(cellIdsInCell(cell));
        }
        return ids;
    }

    private static Set<String> cellIdsInCell(Tc cell) {
        Set<String> ids = new LinkedHashSet<>();
        P paragraph = TableOps.firstParagraph(cell);
        if (paragraph == null) {
            return ids;
        }
        for (Object node : paragraph.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof CTBookmark bookmark
                    && bookmark.getName() != null
                    && bookmark.getName().startsWith("dg_")) {
                ids.add(bookmark.getName());
            }
        }
        return ids;
    }

    private static TblGridCol gridColAt(Tbl table, int colIndex) {
        TblGrid grid = table.getTblGrid();
        if (grid == null || colIndex < 0 || colIndex >= grid.getGridCol().size()) {
            return null;
        }
        return grid.getGridCol().get(colIndex);
    }

    private static int gridColCount(Tbl table) {
        TblGrid grid = table.getTblGrid();
        return grid == null ? -1 : grid.getGridCol().size();
    }
}
