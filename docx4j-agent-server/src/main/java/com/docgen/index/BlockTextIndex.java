package com.docgen.index;

import org.docx4j.XmlUtils;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps plain-text character offsets of one paragraph to the w:r / w:t nodes
 * that hold them. Built fresh before each edit; never cached across mutations.
 */
public final class BlockTextIndex {

    /**
     * One w:t node's slice of the paragraph text: [start, end) in fullText.
     * rawTextNode is the original (possibly JAXBElement-wrapped) child of the
     * run's content list, kept so it can be removed by identity.
     */
    public record Segment(R run, Object rawTextNode, Text text, int start, int end) {
    }

    private final String fullText;
    private final List<Segment> segments;

    private BlockTextIndex(String fullText, List<Segment> segments) {
        this.fullText = fullText;
        this.segments = segments;
    }

    public static BlockTextIndex of(P paragraph) {
        StringBuilder sb = new StringBuilder();
        List<Segment> segments = new ArrayList<>();
        for (Object child : paragraph.getContent()) {
            Object unwrapped = XmlUtils.unwrap(child);
            if (!(unwrapped instanceof R run)) {
                continue;
            }
            for (Object runChild : run.getContent()) {
                Object unwrappedRunChild = XmlUtils.unwrap(runChild);
                if (unwrappedRunChild instanceof Text text) {
                    String value = text.getValue() == null ? "" : text.getValue();
                    int start = sb.length();
                    sb.append(value);
                    segments.add(new Segment(run, runChild, text, start, sb.length()));
                }
            }
        }
        return new BlockTextIndex(sb.toString(), List.copyOf(segments));
    }

    public String fullText() {
        return fullText;
    }

    public List<Segment> segments() {
        return segments;
    }

    /** @return start offset of the given occurrence (0-based) of needle, or -1. */
    public int findSpan(String needle, int occurrence) {
        if (needle == null || needle.isEmpty() || occurrence < 0) {
            return -1;
        }
        int from = 0;
        for (int seen = 0; ; seen++) {
            int at = fullText.indexOf(needle, from);
            if (at < 0) {
                return -1;
            }
            if (seen == occurrence) {
                return at;
            }
            from = at + 1;
        }
    }

    /** Segments whose text range intersects [start, end). */
    public List<Segment> segmentsOverlapping(int start, int end) {
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.end() > start && segment.start() < end) {
                result.add(segment);
            }
        }
        return result;
    }
}
