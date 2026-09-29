package com.docgen.mutation;

import com.docgen.index.BookmarkIndexer;
import com.docgen.index.BookmarkResolver;
import com.docgen.model.InsertMutation;
import org.docx4j.XmlUtils;
import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase;
import org.docx4j.wml.R;
import org.docx4j.wml.Text;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Inserts a new body paragraph before/after an anchor paragraph.
 * The new paragraph clones the anchor's pPr (optionally overriding the
 * style) and receives a fresh dg_p* bookmark so it is addressable.
 */
@Component
public class InsertApplier {

    private final BookmarkResolver bookmarkResolver;
    private final BookmarkIndexer bookmarkIndexer;

    public InsertApplier(BookmarkResolver bookmarkResolver, BookmarkIndexer bookmarkIndexer) {
        this.bookmarkResolver = bookmarkResolver;
        this.bookmarkIndexer = bookmarkIndexer;
    }

    /** @return the target_id of the newly created paragraph */
    public String apply(WordprocessingMLPackage document, InsertMutation mutation) {
        return apply(document, mutation, mutation.anchorId());
    }

    /**
     * Like {@link #apply} but resolves {@code effectiveAnchorId} instead of
     * {@code mutation.anchorId()}. Used when several consecutive "after"
     * inserts on the same anchor must become separate paragraphs.
     */
    public String apply(WordprocessingMLPackage document, InsertMutation mutation, String effectiveAnchorId) {
        P anchor = bookmarkResolver.resolve(document, effectiveAnchorId);
        List<Object> body = document.getMainDocumentPart().getJaxbElement().getBody().getContent();
        int anchorIndex = indexOfBodyParagraph(body, anchor);
        if (anchorIndex < 0) {
            throw new IllegalArgumentException(
                    "Insert anchor must be a body paragraph (not inside a table): " + mutation.anchorId());
        }

        ObjectFactory factory = Context.getWmlObjectFactory();
        P paragraph = factory.createP();
        if (anchor.getPPr() != null) {
            paragraph.setPPr(XmlUtils.deepCopy(anchor.getPPr()));
        }
        if (mutation.style() != null && !mutation.style().isBlank()) {
            PPr pPr = paragraph.getPPr() != null ? paragraph.getPPr() : factory.createPPr();
            PPrBase.PStyle pStyle = factory.createPPrBasePStyle();
            pStyle.setVal(mutation.style());
            pPr.setPStyle(pStyle);
            paragraph.setPPr(pPr);
        }

        R run = factory.createR();
        Text text = factory.createText();
        text.setValue(mutation.text());
        text.setSpace("preserve");
        run.getContent().add(text);
        paragraph.getContent().add(run);

        int insertAt = "before".equals(mutation.position()) ? anchorIndex : anchorIndex + 1;
        body.add(insertAt, paragraph);

        return bookmarkIndexer.bookmarkNewParagraph(document, paragraph);
    }

    static int indexOfBodyParagraph(List<Object> body, P paragraph) {
        for (int i = 0; i < body.size(); i++) {
            if (XmlUtils.unwrap(body.get(i)) == paragraph) {
                return i;
            }
        }
        return -1;
    }
}
