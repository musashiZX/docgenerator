package com.docgen.onlyoffice;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * comment-focus.docx is a real file saved by the actual OnlyOffice Document
 * Server: a comment was added in the live editor (Falcon Aerospace
 * Components Ltd. selected, "AI: make this sound more formal" as the
 * comment) and persisted through this project's own forcesave/callback
 * path — not hand-built XML.
 */
class CommentFocusReaderTest {

    @Test
    void readsAnchorTextAndCommentFromARealOnlyOfficeComment() throws Exception {
        WordprocessingMLPackage document;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/comment-focus.docx")) {
            document = WordprocessingMLPackage.load(in);
        }

        List<CommentFocusReader.CommentFocus> comments = CommentFocusReader.read(document);

        assertEquals(1, comments.size());
        CommentFocusReader.CommentFocus comment = comments.get(0);
        assertEquals("DocGen", comment.author());
        assertEquals("AI: make this sound more formal", comment.commentText());
        assertTrue(comment.anchorText().contains("Falcon Aerospace Components Ltd."),
                "anchor text should contain the text the comment was attached to, was: " + comment.anchorText());
    }

    @Test
    void returnsEmptyListForADocumentWithNoComments() throws Exception {
        WordprocessingMLPackage document = com.docgen.support.FixtureFactory.table3x3();
        assertEquals(List.of(), CommentFocusReader.read(document));
    }
}
