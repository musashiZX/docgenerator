package com.docgen.document;

import com.docgen.index.BookmarkIndexer;
import org.docx4j.Docx4J;
import org.docx4j.convert.out.HTMLSettings;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Renders a document as HTML for the console preview. Bookmarks (dg_*) come
 * through as anchors, which the UI uses to show block ids on hover.
 * Fidelity is approximate — the authoritative artifact is the .docx itself.
 */
@Service
public class DocumentPreviewService {

    /**
     * The visitor exporter closes the {@code <p>} right after bookmarks/list
     * markers and writes the paragraph's text spans as SIBLINGS after it, which
     * breaks per-paragraph layout. Rewrite: move the dg bookmark onto the p as
     * {@code data-dg-id} and drop the premature {@code </p>} so the browser's
     * parser re-adopts the following spans (p auto-closes at the next block
     * element). Content groups must not cross into another paragraph.
     */
    private static final Pattern BOOKMARK_PARAGRAPH = Pattern.compile(
            "<p([^>]*)>((?:(?!</?p[ >]).)*?)<a name=\"(dg_[^\"]+)\"\\s*/>((?:(?!</?p[ >]).)*?)</p>",
            Pattern.DOTALL);

    /**
     * Fallback for anchors the first pass could not safely rewrite (e.g. a
     * paragraph whose bookmark is immediately followed by a nested table):
     * only move the id onto the opening p tag, leaving structure untouched.
     */
    private static final Pattern BOOKMARK_LEFTOVER = Pattern.compile(
            "<p([^>]*)>((?:(?!</?p[ >])(?!<table).)*?)<a name=\"(dg_[^\"]+)\"\\s*/>",
            Pattern.DOTALL);

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
        String html = out.toString(StandardCharsets.UTF_8);
        html = BOOKMARK_PARAGRAPH.matcher(html).replaceAll("<p$1 data-dg-id=\"$3\">$2$4");
        return BOOKMARK_LEFTOVER.matcher(html).replaceAll("<p$1 data-dg-id=\"$3\">$2");
    }
}
