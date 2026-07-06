package com.docgen.index;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.CTBookmark;
import org.docx4j.wml.P;
import org.springframework.stereotype.Component;

/** Resolves a dg_* target id to the bookmarked paragraph. */
@Component
public class BookmarkResolver {

    public P resolve(WordprocessingMLPackage document, String targetId) {
        for (BodyWalker.Block block : BodyWalker.walk(document)) {
            P paragraph = switch (block) {
                case BodyWalker.ParagraphBlock(var p) -> p;
                case BodyWalker.TableCellBlock(var tblIdx, var row, var col, var cell, var p) -> p;
            };
            for (Object node : paragraph.getContent()) {
                Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
                if (unwrapped instanceof CTBookmark bookmark && targetId.equals(bookmark.getName())) {
                    return paragraph;
                }
            }
        }
        throw new UnknownTargetException("Unknown target_id: " + targetId);
    }

    public static class UnknownTargetException extends RuntimeException {
        public UnknownTargetException(String message) {
            super(message);
        }
    }
}
