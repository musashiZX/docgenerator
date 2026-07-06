package com.docgen.index;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.Body;
import org.docx4j.wml.P;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Tr;

import java.util.ArrayList;
import java.util.List;

/** Walks top-level body blocks and table cells in document order. */
final class BodyWalker {

    private BodyWalker() {}

    sealed interface Block permits ParagraphBlock, TableCellBlock {
    }

    record ParagraphBlock(P paragraph) implements Block {
    }

    record TableCellBlock(int tableIndex, int row, int col, Tc cell, P paragraph) implements Block {
    }

    static List<Block> walk(WordprocessingMLPackage document) {
        MainDocumentPart main = document.getMainDocumentPart();
        Body body = main.getJaxbElement().getBody();
        if (body == null) {
            return List.of();
        }
        List<Block> blocks = new ArrayList<>();
        int tableIndex = 0;
        for (Object node : body.getContent()) {
            Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
            if (unwrapped instanceof P paragraph) {
                blocks.add(new ParagraphBlock(paragraph));
            } else if (unwrapped instanceof Tbl table) {
                blocks.addAll(walkTable(tableIndex, table));
                tableIndex++;
            }
        }
        return blocks;
    }

    private static List<Block> walkTable(int tableIndex, Tbl table) {
        List<Block> blocks = new ArrayList<>();
        int rowIndex = 0;
        for (Object rowNode : table.getContent()) {
            Object unwrappedRow = org.docx4j.XmlUtils.unwrap(rowNode);
            if (!(unwrappedRow instanceof Tr row)) {
                continue;
            }
            int colIndex = 0;
            for (Object cellNode : row.getContent()) {
                Object unwrappedCell = org.docx4j.XmlUtils.unwrap(cellNode);
                if (unwrappedCell instanceof Tc cell) {
                    P paragraph = firstParagraph(cell);
                    if (paragraph != null) {
                        blocks.add(new TableCellBlock(tableIndex, rowIndex, colIndex, cell, paragraph));
                    }
                    colIndex++;
                }
            }
            rowIndex++;
        }
        return blocks;
    }

    private static P firstParagraph(Tc cell) {
        for (Object node : cell.getContent()) {
            Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
            if (unwrapped instanceof P paragraph) {
                return paragraph;
            }
        }
        return null;
    }
}
