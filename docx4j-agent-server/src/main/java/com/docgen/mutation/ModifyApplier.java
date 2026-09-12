package com.docgen.mutation;

import com.docgen.index.BlockTextIndex;
import com.docgen.index.BookmarkResolver;
import com.docgen.model.ModifyMutation;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.springframework.stereotype.Component;

/**
 * Applies one modify mutation: resolve target block by bookmark, verify
 * old_text at the requested occurrence (lock), replace the span run-aware.
 */
@Component
public class ModifyApplier {

    private final BookmarkResolver bookmarkResolver;

    public ModifyApplier(BookmarkResolver bookmarkResolver) {
        this.bookmarkResolver = bookmarkResolver;
    }

    public void apply(WordprocessingMLPackage document, ModifyMutation mutation) {
        P paragraph = bookmarkResolver.resolve(document, mutation.targetId());
        BlockTextIndex index = BlockTextIndex.of(paragraph);

        // old_text="" asserts "this block is currently empty" — there's no
        // substring to find (findSpan always returns -1 for an empty
        // needle), so check emptiness directly and insert at position 0.
        if (mutation.oldText().isEmpty()) {
            if (!index.fullText().isEmpty()) {
                throw new StaleTargetException(
                        "old_text is empty (expected " + mutation.targetId() + " to be empty) but it contains: \""
                                + index.fullText() + "\"");
            }
            RunEditor.replaceSpan(paragraph, 0, 0, mutation.newText());
            return;
        }

        int start = index.findSpan(mutation.oldText(), mutation.occurrenceOrDefault());
        if (start < 0) {
            throw new StaleTargetException(
                    "old_text not found in " + mutation.targetId()
                            + " (occurrence " + mutation.occurrenceOrDefault() + "): \""
                            + mutation.oldText() + "\"");
        }
        RunEditor.replaceSpan(paragraph, start, start + mutation.oldText().length(), mutation.newText());
    }
}
