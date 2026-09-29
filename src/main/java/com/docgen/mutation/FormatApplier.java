package com.docgen.mutation;

import com.docgen.index.BlockTextIndex;
import com.docgen.index.BookmarkResolver;
import com.docgen.model.FormatMutation;
import org.docx4j.jaxb.Context;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.Jc;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.U;
import org.docx4j.wml.UnderlineEnumeration;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.List;

/**
 * Applies bold/italic/underline/font-size to a verbatim text span (splitting
 * runs at the span boundaries so unrelated text keeps its formatting) and/or
 * paragraph alignment to the whole block. Text-only; never touches plain
 * text, so it deliberately does not feed the (text-hash) NodeHashGuard.
 */
@Component
public class FormatApplier {

    private static final ObjectFactory FACTORY = Context.getWmlObjectFactory();

    private final BookmarkResolver bookmarkResolver;

    public FormatApplier(BookmarkResolver bookmarkResolver) {
        this.bookmarkResolver = bookmarkResolver;
    }

    public void apply(WordprocessingMLPackage document, FormatMutation mutation) {
        P paragraph = bookmarkResolver.resolve(document, mutation.targetId());

        if (mutation.hasAlignment()) {
            applyAlignment(paragraph, mutation.align());
        }
        if (mutation.hasRunFormatting()) {
            applyRunFormatting(paragraph, mutation);
        }
    }

    private void applyAlignment(P paragraph, String align) {
        JcEnumeration value = switch (align.toLowerCase()) {
            case "left" -> JcEnumeration.LEFT;
            case "center", "centre" -> JcEnumeration.CENTER;
            case "right" -> JcEnumeration.RIGHT;
            case "justify", "both" -> JcEnumeration.BOTH;
            default -> throw new IllegalArgumentException(
                    "align must be left|center|right|justify, got: " + align);
        };
        PPr pPr = paragraph.getPPr();
        if (pPr == null) {
            pPr = FACTORY.createPPr();
            paragraph.setPPr(pPr);
        }
        Jc jc = FACTORY.createJc();
        jc.setVal(value);
        pPr.setJc(jc);
    }

    private void applyRunFormatting(P paragraph, FormatMutation mutation) {
        int start;
        int end;
        if (mutation.text() != null && !mutation.text().isEmpty()) {
            BlockTextIndex index = BlockTextIndex.of(paragraph);
            start = index.findSpan(mutation.text(), mutation.occurrenceOrDefault());
            if (start < 0) {
                throw new StaleTargetException(
                        "text not found in " + mutation.targetId()
                                + " (occurrence " + mutation.occurrenceOrDefault() + "): \""
                                + mutation.text() + "\"");
            }
            end = start + mutation.text().length();
        } else {
            start = 0;
            end = BlockTextIndex.of(paragraph).fullText().length();
        }
        if (start == end) {
            return; // empty paragraph — nothing to format
        }

        RunEditor.splitAt(paragraph, start);
        RunEditor.splitAt(paragraph, end);

        BlockTextIndex index = BlockTextIndex.of(paragraph);
        List<BlockTextIndex.Segment> affected = index.segmentsOverlapping(start, end);
        for (BlockTextIndex.Segment segment : affected) {
            if (segment.start() < start || segment.end() > end) {
                continue; // split above guarantees this shouldn't happen; be defensive anyway
            }
            applyRPr(segment.run(), mutation);
        }
    }

    private void applyRPr(R run, FormatMutation mutation) {
        RPr rPr = run.getRPr();
        if (rPr == null) {
            rPr = FACTORY.createRPr();
            run.setRPr(rPr);
        }
        if (mutation.bold() != null) {
            rPr.setB(booleanDefaultTrue(mutation.bold()));
        }
        if (mutation.italic() != null) {
            rPr.setI(booleanDefaultTrue(mutation.italic()));
        }
        if (mutation.underline() != null) {
            U u = FACTORY.createU();
            u.setVal(mutation.underline() ? UnderlineEnumeration.SINGLE : UnderlineEnumeration.NONE);
            rPr.setU(u);
        }
        if (mutation.fontSize() != null) {
            HpsMeasure sz = FACTORY.createHpsMeasure();
            sz.setVal(BigInteger.valueOf(mutation.fontSize() * 2L)); // points -> half-points
            rPr.setSz(sz);
            rPr.setSzCs(sz);
        }
    }

    private static BooleanDefaultTrue booleanDefaultTrue(boolean value) {
        BooleanDefaultTrue b = FACTORY.createBooleanDefaultTrue();
        b.setVal(value);
        return b;
    }
}
