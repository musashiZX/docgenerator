package com.docgen.document;

import com.docgen.index.BookmarkIndexer;
import org.docx4j.Docx4J;
import org.docx4j.convert.out.HTMLSettings;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a document as HTML for the console preview. Bookmarks (dg_*) come
 * through as anchors, which the UI uses to show block ids on hover.
 * Fidelity is approximate — the authoritative artifact is the .docx itself.
 */
@Service
public class DocumentPreviewService {

    /**
     * docx4j often emits {@code <p><a name="dg_…"/></p><span>text</span>} (bookmark
     * paragraph closed before its text). Fold following sibling spans into the p.
     * Must NOT swallow tables or other block elements.
     */
    private static final Pattern BOOKMARK_THEN_SPANS = Pattern.compile(
            "<p([^>]*data-dg-id=\"dg_[^\"]+\"[^>]*)>\\s*</p>"
                    + "((?:\\s*<span\\b[^>]*>.*?</span>)+)",
            Pattern.DOTALL);

    /** Move table-cell bookmarks onto the enclosing {@code <td>}. */
    private static final Pattern TD_WITH_BOOKMARK_ANCHOR = Pattern.compile(
            "<td(\\b[^>]*)>((?:(?!</td>).)*?)<a\\s+name=\"(dg_tbl[^\"]+)\"\\s*/>(?:(?!</td>).)*?</td>",
            Pattern.DOTALL);

    /** Paragraph that still has a raw dg bookmark anchor. */
    private static final Pattern P_WITH_BOOKMARK_ANCHOR = Pattern.compile(
            "<p(\\b[^>]*)>((?:(?!</p>).)*?)<a\\s+name=\"(dg_[^\"]+)\"\\s*/>(?:(?!</p>).)*?</p>",
            Pattern.DOTALL);

    /** Bookmark anchor inside an unclosed p that is immediately followed by a table. */
    private static final Pattern P_BOOKMARK_BEFORE_TABLE = Pattern.compile(
            "<p(\\b[^>]*)>((?:(?!</p>|<table).)*)<a\\s+name=\"(dg_[^\"]+)\"\\s*/>\\s*(<table\\b)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Empty bookmark paragraphs nested before the next p/table: close them. */
    private static final Pattern EMPTY_NESTED_P = Pattern.compile(
            "(<p\\b[^>]*data-dg-id=\"dg_[^\"]+\"[^>]*>)\\s*(?=<p\\b|<table\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Unclosed {@code <p data-dg-id>} immediately before a block element. */
    private static final Pattern UNCLOSED_P_BEFORE_BLOCK = Pattern.compile(
            "(<p\\b[^>]*data-dg-id=\"dg_[^\"]+\"[^>]*>)\\s*(<(?:table|div|h[1-6]|ul|ol|hr)\\b)",
            Pattern.CASE_INSENSITIVE);

    private final DocumentLoader documentLoader;
    private final BookmarkIndexer bookmarkIndexer;

    public DocumentPreviewService(DocumentLoader documentLoader, BookmarkIndexer bookmarkIndexer) {
        this.documentLoader = documentLoader;
        this.bookmarkIndexer = bookmarkIndexer;
    }

    public String renderHtml(String docName) throws Exception {
        Path path = documentLoader.resolveDoc(docName);
        WordprocessingMLPackage document = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(document);

        HTMLSettings settings = Docx4J.createHTMLSettings();
        settings.setOpcPackage(document);
        // Empty image dir path makes the exporter inline images as data URIs.
        settings.setImageDirPath("");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Docx4J.toHTML(settings, out, Docx4J.FLAG_EXPORT_PREFER_NONXSL);
        return normalizeBookmarkHtml(out.toString(StandardCharsets.UTF_8));
    }

    /**
     * Attaches {@code data-dg-id} without wrapping tables inside paragraphs.
     * Visible for tests.
     */
    static String normalizeBookmarkHtml(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        String out = html;
        out = replaceAll(TD_WITH_BOOKMARK_ANCHOR, out, m -> {
            String tdAttrs = m.group(1);
            String id = m.group(3);
            String whole = m.group(0);
            String body = whole.substring(whole.indexOf('>') + 1, whole.length() - "</td>".length())
                    .replaceFirst("<a\\s+name=\"" + Pattern.quote(id) + "\"\\s*/>", "");
            if (tdAttrs.contains("data-dg-id=")) {
                return "<td" + tdAttrs + ">" + body + "</td>";
            }
            return "<td" + tdAttrs + " data-dg-id=\"" + id + "\">" + body + "</td>";
        });
        out = replaceAll(P_BOOKMARK_BEFORE_TABLE, out, m -> {
            String pAttrs = m.group(1);
            String before = m.group(2);
            String id = m.group(3);
            String table = m.group(4);
            String open = pAttrs.contains("data-dg-id=")
                    ? "<p" + pAttrs + ">"
                    : "<p" + pAttrs + " data-dg-id=\"" + id + "\">";
            return open + before + "</p>" + table;
        });
        out = replaceAll(P_WITH_BOOKMARK_ANCHOR, out, m -> {
            String pAttrs = m.group(1);
            String id = m.group(3);
            String whole = m.group(0);
            String body = whole.substring(whole.indexOf('>') + 1, whole.length() - "</p>".length())
                    .replaceFirst("<a\\s+name=\"" + Pattern.quote(id) + "\"\\s*/>", "");
            if (pAttrs.contains("data-dg-id=")) {
                return "<p" + pAttrs + ">" + body + "</p>";
            }
            return "<p" + pAttrs + " data-dg-id=\"" + id + "\">" + body + "</p>";
        });
        // Any remaining anchors (safe to drop after ids are attached).
        out = Pattern.compile("<a\\s+name=\"dg_[^\"]+\"\\s*/>").matcher(out).replaceAll("");
        out = EMPTY_NESTED_P.matcher(out).replaceAll("$1</p>");
        out = UNCLOSED_P_BEFORE_BLOCK.matcher(out).replaceAll("$1</p>$2");
        // Fold orphan spans back into an empty bookmark paragraph (inline only).
        out = replaceAll(BOOKMARK_THEN_SPANS, out,
                m -> "<p" + m.group(1) + ">" + m.group(2) + "</p>");
        return out;
    }

    @FunctionalInterface
    private interface Replacer {
        String replace(Matcher matcher);
    }

    private static String replaceAll(Pattern pattern, String input, Replacer replacer) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.replace(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
