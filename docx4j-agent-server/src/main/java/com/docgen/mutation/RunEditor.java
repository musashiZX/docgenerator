package com.docgen.mutation;

import com.docgen.index.BlockTextIndex;
import org.docx4j.XmlUtils;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Text;

import java.util.List;

/**
 * Low-level run surgery: replaces a character span of one paragraph while
 * preserving run properties (w:rPr). When old and new text share a prefix or
 * suffix, only the differing middle is edited so label/value runs keep their
 * formatting. Pure appends insert into the run at the boundary.
 */
public final class RunEditor {

    private RunEditor() {}

    /** Replace [startInText, endInText) within a single w:t node. Keeps the run's rPr. */
    public static void replaceSpanSingleRun(Text text, int startInText, int endInText, String newText) {
        String value = text.getValue() == null ? "" : text.getValue();
        String prefix = value.substring(0, startInText);
        String suffix = value.substring(endInText);
        setText(text, prefix + newText + suffix);
    }

    /** Replace [start, end) of the paragraph's concatenated run text with newText. */
    public static void replaceSpan(P paragraph, int start, int end, String newText) {
        BlockTextIndex index = BlockTextIndex.of(paragraph);
        if (start < 0 || end > index.fullText().length() || start > end) {
            throw new IllegalArgumentException(
                    "Span [" + start + "," + end + ") out of range 0.." + index.fullText().length());
        }
        String oldSlice = index.fullText().substring(start, end);
        if (!oldSlice.equals(newText)) {
            int prefixLen = commonPrefixLength(oldSlice, newText);
            int suffixLen = commonSuffixLength(oldSlice, newText, prefixLen);
            if (prefixLen + suffixLen < oldSlice.length() || prefixLen + suffixLen < newText.length()) {
                int midStart = start + prefixLen;
                int midEnd = end - suffixLen;
                String midNew = newText.substring(prefixLen, newText.length() - suffixLen);
                if (midStart != start || midEnd != end || !midNew.equals(newText)) {
                    if (midStart == midEnd && !midNew.isEmpty()) {
                        insertAt(paragraph, midStart, midNew);
                        return;
                    }
                    replaceSpan(paragraph, midStart, midEnd, midNew);
                    return;
                }
            }
        }
        List<BlockTextIndex.Segment> affected = index.segmentsOverlapping(start, end);
        if (affected.isEmpty()) {
            throw new IllegalArgumentException("Span [" + start + "," + end + ") covers no runs.");
        }

        if (affected.size() == 1) {
            BlockTextIndex.Segment segment = affected.getFirst();
            replaceSpanSingleRun(segment.text(), start - segment.start(), end - segment.start(), newText);
        } else {
            for (int i = 0; i < affected.size(); i++) {
                BlockTextIndex.Segment segment = affected.get(i);
                String value = segment.text().getValue() == null ? "" : segment.text().getValue();
                if (i == 0) {
                    String prefix = value.substring(0, start - segment.start());
                    setText(segment.text(), prefix + newText);
                } else if (i == affected.size() - 1) {
                    String suffix = value.substring(end - segment.start());
                    setText(segment.text(), suffix);
                } else {
                    setText(segment.text(), "");
                }
            }
        }
        removeEmptied(paragraph, affected);
    }

    /** Insert text at a position inside (or at end of) an existing run, preserving that run's rPr. */
    private static void insertAt(P paragraph, int position, String text) {
        BlockTextIndex index = BlockTextIndex.of(paragraph);
        for (BlockTextIndex.Segment segment : index.segments()) {
            if (position >= segment.start() && position <= segment.end()) {
                int offset = position - segment.start();
                replaceSpanSingleRun(segment.text(), offset, offset, text);
                return;
            }
        }
        throw new IllegalArgumentException("Insert position " + position + " is not inside any run.");
    }

    private static void setText(Text text, String value) {
        text.setValue(value);
        text.setSpace("preserve");
    }

    private static void removeEmptied(P paragraph, List<BlockTextIndex.Segment> affected) {
        for (BlockTextIndex.Segment segment : affected) {
            String value = segment.text().getValue();
            if (value != null && !value.isEmpty()) {
                continue;
            }
            segment.run().getContent().remove(segment.rawTextNode());
            if (segment.run().getContent().isEmpty()) {
                removeRun(paragraph, segment.run());
            }
        }
    }

    private static void removeRun(P paragraph, R run) {
        paragraph.getContent().removeIf(node -> XmlUtils.unwrap(node) == run);
    }

    static int commonPrefixLength(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    static int commonSuffixLength(String a, String b, int prefixLen) {
        int max = Math.min(a.length() - prefixLen, b.length() - prefixLen);
        int i = 0;
        while (i < max && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }
}
