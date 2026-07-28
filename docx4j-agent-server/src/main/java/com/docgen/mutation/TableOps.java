package com.docgen.mutation;

import com.docgen.index.BlockTextIndex;
import org.docx4j.XmlUtils;
import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.Body;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.CTMarkupRange;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblGrid;
import org.docx4j.wml.TblGridCol;
import org.docx4j.wml.Tc;
import org.docx4j.wml.TcPr;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Locates tables and checks whether they are simple dataframe-shaped grids. */
final class TableOps {

    record CellLocation(Tbl table, int tableIndex, Tr row, int rowIndex, Tc cell, int colIndex) {
    }

    private TableOps() {
    }

    static CellLocation locateCell(WordprocessingMLPackage document, P paragraph) {
        MainDocumentPart main = document.getMainDocumentPart();
        Body body = main.getJaxbElement().getBody();
        if (body == null) {
            return null;
        }
        int tableIndex = 0;
        for (Object node : body.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (!(unwrapped instanceof Tbl table)) {
                continue;
            }
            List<Tr> rows = rowsOf(table);
            for (int r = 0; r < rows.size(); r++) {
                List<Tc> cells = cellsOf(rows.get(r));
                for (int c = 0; c < cells.size(); c++) {
                    if (firstParagraph(cells.get(c)) == paragraph) {
                        return new CellLocation(table, tableIndex, rows.get(r), r, cells.get(c), c);
                    }
                }
            }
            tableIndex++;
        }
        return null;
    }

    /**
     * True when every body row has the same cell count and no horizontal/vertical merges.
     * Complicated layout tables are rejected for structural ops.
     */
    static boolean isDataframe(Tbl table) {
        List<Tr> rows = rowsOf(table);
        if (rows.isEmpty()) {
            return false;
        }
        int expectedCols = -1;
        for (Tr row : rows) {
            List<Tc> cells = cellsOf(row);
            if (cells.isEmpty()) {
                return false;
            }
            if (expectedCols < 0) {
                expectedCols = cells.size();
            } else if (cells.size() != expectedCols) {
                return false;
            }
            for (Tc cell : cells) {
                if (hasMerge(cell)) {
                    return false;
                }
            }
        }
        return expectedCols > 0;
    }

    static List<Tr> rowsOf(Tbl table) {
        List<Tr> rows = new ArrayList<>();
        for (Object node : table.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof Tr row) {
                rows.add(row);
            }
        }
        return rows;
    }

    static List<Tc> cellsOf(Tr row) {
        List<Tc> cells = new ArrayList<>();
        for (Object node : row.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof Tc cell) {
                cells.add(cell);
            }
        }
        return cells;
    }

    /** Plain text of each physical cell in the row (first paragraph only). */
    static List<String> cellTexts(Tr row) {
        List<String> texts = new ArrayList<>();
        for (Tc cell : cellsOf(row)) {
            P paragraph = firstParagraph(cell);
            if (paragraph == null) {
                texts.add("");
            } else {
                texts.add(BlockTextIndex.of(paragraph).fullText());
            }
        }
        return texts;
    }

    static P firstParagraph(Tc cell) {
        for (Object node : cell.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof P paragraph) {
                return paragraph;
            }
        }
        return null;
    }

    static int indexOfRow(Tbl table, Tr row) {
        List<Object> content = table.getContent();
        for (int i = 0; i < content.size(); i++) {
            if (XmlUtils.unwrap(content.get(i)) == row) {
                return i;
            }
        }
        return -1;
    }

    static int indexOfCell(Tr row, Tc cell) {
        List<Object> content = row.getContent();
        for (int i = 0; i < content.size(); i++) {
            if (XmlUtils.unwrap(content.get(i)) == cell) {
                return i;
            }
        }
        return -1;
    }

    /** Removes dg_* bookmark start/end pairs from a paragraph. */
    static void stripDgBookmarkPairs(P paragraph) {
        if (paragraph == null) {
            return;
        }
        List<BigInteger> ids = new ArrayList<>();
        for (Object node : paragraph.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof CTBookmark bookmark
                    && bookmark.getName() != null
                    && bookmark.getName().startsWith("dg_")
                    && bookmark.getId() != null) {
                ids.add(bookmark.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        paragraph.getContent().removeIf(node -> {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof CTBookmark bookmark) {
                return bookmark.getName() != null && bookmark.getName().startsWith("dg_");
            }
            if (unwrapped instanceof CTMarkupRange end && end.getId() != null) {
                return ids.contains(end.getId());
            }
            return false;
        });
    }

    /**
     * Sets cell text while preserving the template paragraph's run structure when possible.
     * PDF-converted cells often split one visual line into many runs with different
     * character spacing; collapsing those to a single run changes wrapping/layout.
     * Unchanged text is a no-op. Changed text is redistributed across existing text
     * runs (keeping each run's rPr); falls back to a single first-run-styled run
     * only when the cell has no text runs yet.
     */
    static void setCellTextPreservingFirstRunFormat(Tc cell, String text) {
        P paragraph = firstParagraph(cell);
        if (paragraph == null) {
            return;
        }
        String desired = text == null ? "" : text;
        BlockTextIndex index = BlockTextIndex.of(paragraph);
        if (desired.equals(index.fullText())) {
            return;
        }

        List<Text> textNodes = new ArrayList<>();
        for (Object node : paragraph.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (!(unwrapped instanceof R run)) {
                continue;
            }
            for (Object child : run.getContent()) {
                Object u = XmlUtils.unwrap(child);
                if (u instanceof Text t) {
                    textNodes.add(t);
                }
            }
        }

        if (textNodes.isEmpty()) {
            var factory = Context.getWmlObjectFactory();
            R run = factory.createR();
            Text t = factory.createText();
            t.setValue(desired);
            t.setSpace("preserve");
            run.getContent().add(t);
            paragraph.getContent().add(run);
            return;
        }

        if (textNodes.size() == 1) {
            Text t = textNodes.getFirst();
            t.setValue(desired);
            t.setSpace("preserve");
            return;
        }

        int[] oldLens = new int[textNodes.size()];
        int oldTotal = 0;
        for (int i = 0; i < textNodes.size(); i++) {
            String value = textNodes.get(i).getValue();
            oldLens[i] = value == null ? 0 : value.length();
            oldTotal += oldLens[i];
        }
        if (oldTotal <= 0) {
            textNodes.getFirst().setValue(desired);
            textNodes.getFirst().setSpace("preserve");
            for (int i = 1; i < textNodes.size(); i++) {
                textNodes.get(i).setValue("");
            }
            return;
        }

        int offset = 0;
        int newTotal = desired.length();
        for (int i = 0; i < textNodes.size(); i++) {
            int sliceLen;
            if (i == textNodes.size() - 1) {
                sliceLen = Math.max(0, newTotal - offset);
            } else {
                sliceLen = (int) Math.round(oldLens[i] * (double) newTotal / oldTotal);
                sliceLen = Math.min(sliceLen, newTotal - offset);
                sliceLen = Math.max(0, sliceLen);
            }
            String slice = desired.substring(offset, offset + sliceLen);
            Text t = textNodes.get(i);
            t.setValue(slice);
            if (!slice.isEmpty()) {
                t.setSpace("preserve");
            }
            offset += sliceLen;
        }
    }

    static void ensureGridColumn(Tbl table, int insertAt, TblGridCol templateCol) {
        TblGrid grid = table.getTblGrid();
        if (grid == null) {
            return;
        }
        TblGridCol col = templateCol != null
                ? XmlUtils.deepCopy(templateCol)
                : Context.getWmlObjectFactory().createTblGridCol();
        if (col.getW() == null) {
            col.setW(BigInteger.valueOf(2000));
        }
        List<TblGridCol> cols = grid.getGridCol();
        if (insertAt < 0 || insertAt > cols.size()) {
            cols.add(col);
        } else {
            cols.add(insertAt, col);
        }
    }

    static void removeGridColumn(Tbl table, int colIndex) {
        TblGrid grid = table.getTblGrid();
        if (grid == null) {
            return;
        }
        List<TblGridCol> cols = grid.getGridCol();
        if (colIndex >= 0 && colIndex < cols.size()) {
            cols.remove(colIndex);
        }
    }

    private static boolean hasMerge(Tc cell) {
        TcPr tcPr = cell.getTcPr();
        if (tcPr == null) {
            return false;
        }
        if (tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null
                && tcPr.getGridSpan().getVal().intValue() > 1) {
            return true;
        }
        return tcPr.getVMerge() != null;
    }
}
