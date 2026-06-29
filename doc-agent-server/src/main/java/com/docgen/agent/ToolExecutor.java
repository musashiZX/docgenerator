package com.docgen.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncfusion.docio.Entity;
import com.syncfusion.docio.IWParagraph;
import com.syncfusion.docio.WParagraph;
import com.syncfusion.docio.WTable;
import com.syncfusion.docio.WTableCell;
import com.syncfusion.docio.WTableRow;
import com.syncfusion.docio.WTextBody;
import com.syncfusion.docio.WordDocument;

import java.util.List;

/** Maps LLM tool calls to Syncfusion DocIO operations on a live {@link WordDocument}. */
public class ToolExecutor {

    private final WordDocument document;

    public ToolExecutor(WordDocument document) {
        this.document = document;
    }

    public String dispatch(String toolName, JsonNode args) throws Exception {
        return switch (toolName) {
            case "read_document" -> DocumentSnapshot.render(document);
            case "replace_text" -> replaceText(textArg(args, "find"), textArg(args, "replace"));
            case "append_paragraph" -> appendParagraph(
                    textArg(args, "text"),
                    optionalText(args, "style"));
            case "insert_paragraph" -> insertParagraph(
                    args.path("index").asInt(-1),
                    textArg(args, "text"),
                    optionalText(args, "style"));
            case "set_paragraph" -> setParagraph(
                    args.path("index").asInt(-1),
                    textArg(args, "text"),
                    optionalText(args, "style"));
            case "delete_paragraph" -> deleteParagraph(args.path("index").asInt(-1));
            case "read_table" -> readTable(args.path("table_index").asInt(-1));
            case "set_table_cell" -> setTableCell(
                    args.path("table_index").asInt(-1),
                    args.path("row").asInt(-1),
                    args.path("col").asInt(-1),
                    textArg(args, "text"));
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private String replaceText(String find, String replace) throws Exception {
        if (find == null || find.isBlank()) {
            return "Error: 'find' must be non-empty.";
        }
        int count = document.replace(find, replace, false, false);
        return count > 0
                ? "Replaced " + count + " occurrence(s) of \"" + find + "\"."
                : "No match found for: \"" + find + "\".";
    }

    private String appendParagraph(String text, String style) throws Exception {
        IWParagraph paragraph = document.getLastSection().addParagraph();
        paragraph.appendText(text);
        DocumentSnapshot.applyStyle(paragraph, style);
        int idx = DocumentSnapshot.paragraphCount(document) - 1;
        return "Appended paragraph at index " + idx + ".";
    }

    private String insertParagraph(int index, String text, String style) throws Exception {
        int count = DocumentSnapshot.paragraphCount(document);
        if (index < 0 || index > count) {
            return "Error: index " + index + " out of range (0.." + count + ").";
        }
        if (index == count) {
            return appendParagraph(text, style);
        }

        BodyBlockRef ref = findParagraph(index);
        WParagraph paragraph = new WParagraph(document);
        paragraph.appendText(text);
        DocumentSnapshot.applyStyle(paragraph, style);
        ref.body().getChildEntities().insert(ref.entityIndex(), paragraph);
        return "Inserted paragraph at index " + index + ".";
    }

    private String setParagraph(int index, String text, String style) throws Exception {
        int count = DocumentSnapshot.paragraphCount(document);
        if (index < 0 || index >= count) {
            return "Error: index " + index + " out of range (0.." + (count - 1) + ").";
        }
        IWParagraph paragraph = findParagraph(index).paragraph();
        clearParagraphText(paragraph);
        paragraph.appendText(text);
        DocumentSnapshot.applyStyle(paragraph, style);
        return "Updated paragraph at index " + index + ".";
    }

    private String deleteParagraph(int index) throws Exception {
        int count = DocumentSnapshot.paragraphCount(document);
        if (index < 0 || index >= count) {
            return "Error: index " + index + " out of range (0.." + (count - 1) + ").";
        }
        BodyBlockRef ref = findParagraph(index);
        ref.body().getChildEntities().removeAt(ref.entityIndex());
        return "Deleted paragraph at index " + index + ".";
    }

    private String readTable(int tableIndex) throws Exception {
        List<WTable> tables = DocumentSnapshot.tables(document);
        if (tables.isEmpty()) {
            return "This document contains no tables.";
        }
        if (tableIndex < 0 || tableIndex >= tables.size()) {
            return "Error: table_index " + tableIndex + " out of range (0.." + (tables.size() - 1) + ").";
        }
        WTable table = tables.get(tableIndex);
        StringBuilder sb = new StringBuilder();
        int rows = table.getRows().getCount();
        int cols = rows > 0 ? table.getRows().get(0).getCells().getCount() : 0;
        sb.append("Table ").append(tableIndex).append(": ").append(rows)
                .append(" rows × ").append(cols).append(" cols\n");
        for (int r = 0; r < rows; r++) {
            WTableRow row = table.getRows().get(r);
            for (int c = 0; c < row.getCells().getCount(); c++) {
                String cellText = cellPlainText(row.getCells().get(c)).replace("\n", " / ");
                sb.append("  [").append(r).append("][").append(c).append("]: \"")
                        .append(cellText).append("\"\n");
            }
        }
        return sb.toString().trim();
    }

    private String setTableCell(int tableIndex, int row, int col, String text) throws Exception {
        List<WTable> tables = DocumentSnapshot.tables(document);
        if (tables.isEmpty()) {
            return "This document contains no tables.";
        }
        if (tableIndex < 0 || tableIndex >= tables.size()) {
            return "Error: table_index " + tableIndex + " out of range (0.." + (tables.size() - 1) + ").";
        }
        WTable table = tables.get(tableIndex);
        if (row < 0 || row >= table.getRows().getCount()) {
            return "Error: row " + row + " out of range (0.." + (table.getRows().getCount() - 1) + ").";
        }
        WTableRow tableRow = table.getRows().get(row);
        if (col < 0 || col >= tableRow.getCells().getCount()) {
            return "Error: col " + col + " out of range (0.." + (tableRow.getCells().getCount() - 1) + ").";
        }
        setCellText(tableRow.getCells().get(col), text);
        return "Updated table[" + tableIndex + "] row " + row + " col " + col + " → \"" + text + "\".";
    }

    private static void clearParagraphText(IWParagraph paragraph) throws Exception {
        if (paragraph instanceof WParagraph wParagraph) {
            while (wParagraph.getChildEntities().getCount() > 0) {
                wParagraph.getChildEntities().removeAt(0);
            }
        }
    }

    private static void setCellText(WTableCell cell, String text) throws Exception {
        if (cell.getChildEntities().getCount() == 0) {
            WParagraph paragraph = new WParagraph(cell.getDocument());
            paragraph.appendText(text);
            cell.getChildEntities().add(paragraph);
            return;
        }
        Entity first = cell.getChildEntities().get(0);
        if (first instanceof IWParagraph paragraph) {
            clearParagraphText(paragraph);
            paragraph.appendText(text);
            while (cell.getChildEntities().getCount() > 1) {
                cell.getChildEntities().removeAt(1);
            }
            return;
        }
        cell.getChildEntities().clear();
        WParagraph paragraph = new WParagraph(cell.getDocument());
        paragraph.appendText(text);
        cell.getChildEntities().add(paragraph);
    }

    private static String cellPlainText(WTableCell cell) throws Exception {
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

    private BodyBlockRef findParagraph(int paragraphIndex) {
        int seen = 0;
        for (DocumentSnapshot.BodyBlock block : DocumentSnapshot.bodyBlocks(document)) {
            if (block.isParagraph()) {
                if (seen == paragraphIndex) {
                    return new BodyBlockRef(block.body(), block.entityIndex(), block.asParagraph());
                }
                seen++;
            }
        }
        throw new IllegalArgumentException("Paragraph index not found: " + paragraphIndex);
    }

    private static String textArg(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("Missing required argument: " + field);
        }
        return node.asText();
    }

    private static String optionalText(JsonNode args, String field) {
        JsonNode node = args.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private record BodyBlockRef(WTextBody body, int entityIndex, IWParagraph paragraph) {}
}
