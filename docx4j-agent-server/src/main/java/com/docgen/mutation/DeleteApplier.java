package com.docgen.mutation;

import com.docgen.index.BookmarkResolver;
import com.docgen.model.DeleteMutation;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.springframework.stereotype.Component;

import java.util.List;

/** Removes one body paragraph by bookmark. Table cells cannot be deleted in v1. */
@Component
public class DeleteApplier {

    private final BookmarkResolver bookmarkResolver;

    public DeleteApplier(BookmarkResolver bookmarkResolver) {
        this.bookmarkResolver = bookmarkResolver;
    }

    public void apply(WordprocessingMLPackage document, DeleteMutation mutation) {
        P target = bookmarkResolver.resolve(document, mutation.targetId());
        List<Object> body = document.getMainDocumentPart().getJaxbElement().getBody().getContent();
        int index = InsertApplier.indexOfBodyParagraph(body, target);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Delete supports body paragraphs only (not table cells): " + mutation.targetId());
        }
        body.remove(index);
    }
}
