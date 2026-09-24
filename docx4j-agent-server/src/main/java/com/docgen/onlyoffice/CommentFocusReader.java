package com.docgen.onlyoffice;

import com.docgen.index.PlainTextExtractor;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.Body;
import org.docx4j.wml.CommentRangeEnd;
import org.docx4j.wml.CommentRangeStart;
import org.docx4j.wml.Comments;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads native OOXML comments as AI-focus requests: the free, no-custom-plugin
 * alternative to OnlyOffice's paid Automation API (createConnector) for
 * "select text in the live editor, tell the AI what to do with it". The user
 * selects text, right-click &gt; Add comment (built into every OnlyOffice
 * edition), types the request; we read the comment's anchor text (via
 * commentRangeStart/End, standard OOXML) as the focus and its body as the
 * instruction — the same shape the AI chat already accepts from a
 * Ctrl+click block selection, just sourced from the live editor instead of
 * the Blocks table.
 */
public final class CommentFocusReader {

    public record CommentFocus(String commentId, String author, String commentText, String anchorText) {
    }

    private CommentFocusReader() {
    }

    public static List<CommentFocus> read(WordprocessingMLPackage document) throws Exception {
        MainDocumentPart main = document.getMainDocumentPart();
        if (main == null || main.getJaxbElement() == null) {
            return List.of();
        }
        Body body = main.getJaxbElement().getBody();
        if (body == null) {
            return List.of();
        }

        Map<BigInteger, StringBuilder> anchors = new LinkedHashMap<>();
        collectAnchorText(body.getContent(), anchors, new HashSet<>());

        Map<BigInteger, String> commentText = new LinkedHashMap<>();
        Map<BigInteger, String> authors = new LinkedHashMap<>();
        CommentsPart commentsPart = main.getCommentsPart();
        if (commentsPart != null && commentsPart.getContents() != null) {
            Comments comments = commentsPart.getContents();
            for (Comments.Comment comment : comments.getComment()) {
                commentText.put(comment.getId(), extractText(comment.getContent()));
                authors.put(comment.getId(), comment.getAuthor());
            }
        }

        List<CommentFocus> result = new ArrayList<>();
        for (Map.Entry<BigInteger, String> entry : commentText.entrySet()) {
            BigInteger id = entry.getKey();
            String anchor = anchors.containsKey(id) ? anchors.get(id).toString().trim() : "";
            result.add(new CommentFocus(id.toString(), authors.get(id), entry.getValue(), anchor));
        }
        return result;
    }

    /**
     * Walks content in document order, appending run text to every comment
     * id whose {@code commentRangeStart}...{@code commentRangeEnd} currently
     * encloses it. Always recurses into paragraphs/tables (never treats them
     * as atomic) so a start/end pair nested inside one paragraph — the
     * common case — is caught precisely; {@code activeIds} threads any still
     * -open range across sibling paragraphs for the rarer multi-paragraph
     * selection.
     */
    private static Set<BigInteger> collectAnchorText(
            List<Object> nodes, Map<BigInteger, StringBuilder> anchors, Set<BigInteger> activeIds) {
        for (Object node : nodes) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof CommentRangeStart start) {
                activeIds.add(start.getId());
                anchors.computeIfAbsent(start.getId(), k -> new StringBuilder());
            } else if (unwrapped instanceof CommentRangeEnd end) {
                activeIds.remove(end.getId());
            } else if (unwrapped instanceof R run) {
                if (!activeIds.isEmpty()) {
                    String text = runText(run);
                    for (BigInteger id : activeIds) {
                        anchors.get(id).append(text);
                    }
                }
            } else if (unwrapped instanceof P paragraph) {
                boolean openBefore = !activeIds.isEmpty();
                activeIds = collectAnchorText(paragraph.getContent(), anchors, activeIds);
                if (openBefore && !activeIds.isEmpty()) {
                    for (BigInteger id : activeIds) {
                        anchors.get(id).append(" ");
                    }
                }
            } else if (unwrapped instanceof Tbl table) {
                activeIds = collectAnchorText(table.getContent(), anchors, activeIds);
            } else if (unwrapped instanceof Tr row) {
                activeIds = collectAnchorText(row.getContent(), anchors, activeIds);
            } else if (unwrapped instanceof Tc cell) {
                activeIds = collectAnchorText(cell.getContent(), anchors, activeIds);
            }
        }
        return activeIds;
    }

    private static String runText(R run) {
        StringBuilder sb = new StringBuilder();
        for (Object node : run.getContent()) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof Text text) {
                sb.append(text.getValue());
            }
        }
        return sb.toString();
    }

    private static String extractText(List<Object> commentContent) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Object node : commentContent) {
            Object unwrapped = XmlUtils.unwrap(node);
            if (unwrapped instanceof P paragraph) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(PlainTextExtractor.extractFromParagraph(paragraph));
            }
        }
        return sb.toString();
    }
}
