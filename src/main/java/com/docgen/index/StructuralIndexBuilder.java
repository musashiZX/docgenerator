package com.docgen.index;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.StructuralIndex;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Builds the LLM-facing block list from bookmarked document content. */
@Component
public class StructuralIndexBuilder {

    public StructuralIndex build(WordprocessingMLPackage document, String documentId) throws Exception {
        List<BlockDescriptor> blocks = new ArrayList<>();
        int ordinal = 0;
        int paragraphIndex = 0;

        for (BodyWalker.Block block : BodyWalker.walk(document)) {
            switch (block) {
                case BodyWalker.ParagraphBlock(var paragraph) -> {
                    String targetId = findBookmarkName(paragraph);
                    if (targetId == null) {
                        targetId = "dg_p" + paragraphIndex;
                    }
                    String text = PlainTextExtractor.extractFromParagraph(paragraph);
                    blocks.add(new BlockDescriptor(
                            targetId,
                            "paragraph",
                            text,
                            text.length(),
                            PlainTextExtractor.countRunsInParagraph(paragraph),
                            ordinal++,
                            paragraphStyle(paragraph),
                            null,
                            null,
                            null));
                    paragraphIndex++;
                }
                case BodyWalker.TableCellBlock(var tableIndex, var row, var col, var cell, var paragraph) -> {
                    String targetId = findBookmarkName(paragraph);
                    if (targetId == null) {
                        targetId = "dg_tbl" + tableIndex + "_r" + row + "_c" + col;
                    }
                    String text = cell != null
                            ? PlainTextExtractor.extractFromCell(cell)
                            : PlainTextExtractor.extractFromParagraph(paragraph);
                    blocks.add(new BlockDescriptor(
                            targetId,
                            "table_cell",
                            text,
                            text.length(),
                            PlainTextExtractor.countRunsInParagraph(paragraph),
                            ordinal++,
                            paragraphStyle(paragraph),
                            tableIndex,
                            row,
                            col));
                }
            }
        }

        return new StructuralIndex(documentId, blocks);
    }

    private static String findBookmarkName(P paragraph) {
        for (Object node : paragraph.getContent()) {
            Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
            if (unwrapped instanceof CTBookmark bookmark
                    && bookmark.getName() != null
                    && bookmark.getName().startsWith("dg_")) {
                return bookmark.getName();
            }
        }
        return null;
    }

    private static String paragraphStyle(P paragraph) {
        PPr pPr = paragraph.getPPr();
        if (pPr != null && pPr.getPStyle() != null && pPr.getPStyle().getVal() != null) {
            return pPr.getPStyle().getVal();
        }
        return "Normal";
    }
}
