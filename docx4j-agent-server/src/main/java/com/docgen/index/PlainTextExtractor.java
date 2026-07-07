package com.docgen.index;

import org.docx4j.wml.P;
import org.docx4j.wml.Tc;

/** Concatenates visible text from paragraphs and table cells. */
public final class PlainTextExtractor {

    private PlainTextExtractor() {}

    public static String extractFromParagraph(P paragraph) throws Exception {
        // Keep UI/index text identical to what ModifyApplier searches over,
        // so old_text copied from index always matches apply-time text model.
        return BlockTextIndex.of(paragraph).fullText();
    }

    /** v1: first paragraph in the cell only. */
    public static String extractFromCell(Tc cell) throws Exception {
        for (Object node : cell.getContent()) {
            Object unwrapped = org.docx4j.XmlUtils.unwrap(node);
            if (unwrapped instanceof P paragraph) {
                return extractFromParagraph(paragraph);
            }
        }
        return "";
    }

    public static int countRunsInParagraph(P paragraph) {
        int count = 0;
        for (Object node : paragraph.getContent()) {
            if (org.docx4j.XmlUtils.unwrap(node) instanceof org.docx4j.wml.R) {
                count++;
            }
        }
        return count;
    }
}
