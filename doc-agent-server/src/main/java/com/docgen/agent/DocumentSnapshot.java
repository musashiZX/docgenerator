package com.docgen.agent;

import com.syncfusion.docio.BuiltinStyle;
import com.syncfusion.docio.Entity;
import com.syncfusion.docio.IWParagraph;
import com.syncfusion.docio.IWSection;
import com.syncfusion.docio.WParagraph;
import com.syncfusion.docio.WSection;
import com.syncfusion.docio.WTable;
import com.syncfusion.docio.WTableCell;
import com.syncfusion.docio.WTableRow;
import com.syncfusion.docio.WTextBody;
import com.syncfusion.docio.WordDocument;

import java.util.ArrayList;
import java.util.List;

/** Walks the document body and assigns stable paragraph / table indices for the LLM. */
public final class DocumentSnapshot {

    private DocumentSnapshot() {}

    public static String render(WordDocument document) {
        try {
            List<String> lines = new ArrayList<>();
            int pIdx = 0;
            int tIdx = 0;
            boolean hasContent = false;

            for (int s = 0; s < document.getSections().getCount(); s++) {
                WSection section = document.getSections().get(s);
                WTextBody body = section.getBody();
                for (int i = 0; i < body.getChildEntities().getCount(); i++) {
                    Entity entity = body.getChildEntities().get(i);
                    hasContent = true;
                    if (entity instanceof IWParagraph paragraph) {
                        lines.add("[" + pIdx + "] (" + paragraphStyle(paragraph) + ") " + paragraph.getText());
                        pIdx++;
                    } else if (entity instanceof WTable table) {
                        int rows = table.getRows().getCount();
                        int cols = rows > 0 ? table.getRows().get(0).getCells().getCount() : 0;
                        lines.add("[TABLE " + tIdx + "] (" + rows + " rows × " + cols + " cols)");
                        for (int r = 0; r < rows; r++) {
                            WTableRow row = table.getRows().get(r);
                            List<String> cells = new ArrayList<>();
                            for (int c = 0; c < row.getCells().getCount(); c++) {
                                cells.add(cellText(row.getCells().get(c)).replace("\n", " / "));
                            }
                            lines.add("  row " + r + ": " + cells);
                        }
                        tIdx++;
                    }
                }
            }

            return hasContent ? String.join("\n", lines) : "(empty document)";
        } catch (Exception ex) {
            throw new IllegalStateException("Could not render document snapshot.", ex);
        }
    }

    static List<BodyBlock> bodyBlocks(WordDocument document) {
        try {
            List<BodyBlock> blocks = new ArrayList<>();
            int pIdx = 0;
            int tIdx = 0;

            for (int s = 0; s < document.getSections().getCount(); s++) {
                WSection section = document.getSections().get(s);
                WTextBody body = section.getBody();
                for (int i = 0; i < body.getChildEntities().getCount(); i++) {
                    Entity entity = body.getChildEntities().get(i);
                    if (entity instanceof IWParagraph) {
                        blocks.add(new BodyBlock(body, i, entity, pIdx, -1));
                        pIdx++;
                    } else if (entity instanceof WTable table) {
                        blocks.add(new BodyBlock(body, i, table, -1, tIdx));
                        tIdx++;
                    }
                }
            }
            return blocks;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not walk document body.", ex);
        }
    }

    static int paragraphCount(WordDocument document) {
        int count = 0;
        for (BodyBlock block : bodyBlocks(document)) {
            if (block.isParagraph()) {
                count++;
            }
        }
        return count;
    }

    static List<WTable> tables(WordDocument document) {
        List<WTable> tables = new ArrayList<>();
        for (BodyBlock block : bodyBlocks(document)) {
            if (block.isTable()) {
                tables.add(block.asTable());
            }
        }
        return tables;
    }

    private static String paragraphStyle(IWParagraph paragraph) throws Exception {
        String style = paragraph.getStyleName();
        return style == null || style.isBlank() ? "Normal" : style;
    }

    private static String cellText(WTableCell cell) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cell.getChildEntities().getCount(); i++) {
            Entity child = cell.getChildEntities().get(i);
            if (child instanceof IWParagraph p) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(p.getText());
            }
        }
        return sb.toString();
    }

    record BodyBlock(
            WTextBody body,
            int entityIndex,
            Entity entity,
            int paragraphIndex,
            int tableIndex
    ) {
        boolean isParagraph() {
            return entity instanceof IWParagraph;
        }

        boolean isTable() {
            return entity instanceof WTable;
        }

        IWParagraph asParagraph() {
            return (IWParagraph) entity;
        }

        WTable asTable() {
            return (WTable) entity;
        }
    }

    static void applyStyle(IWParagraph paragraph, String style) throws Exception {
        if (style == null || style.isBlank()) {
            return;
        }
        switch (style) {
            case "Title" -> paragraph.applyStyle(BuiltinStyle.Title);
            case "Heading 1" -> paragraph.applyStyle(BuiltinStyle.Heading1);
            case "Heading 2" -> paragraph.applyStyle(BuiltinStyle.Heading2);
            case "Heading 3" -> paragraph.applyStyle(BuiltinStyle.Heading3);
            case "List Bullet" -> paragraph.getListFormat().applyDefBulletStyle();
            case "List Number" -> paragraph.getListFormat().applyDefNumberedStyle();
            case "Quote" -> paragraph.applyStyle(BuiltinStyle.BlockText);
            default -> paragraph.applyStyle(BuiltinStyle.Normal);
        }
    }
}
