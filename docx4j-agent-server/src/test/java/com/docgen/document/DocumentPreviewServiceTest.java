package com.docgen.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPreviewServiceTest {

    @Test
    void doesNotWrapTableInsideBookmarkParagraph() {
        String raw = """
                <p class="BodyText"><a name="dg_p48"/></p>\
                <table id="docx4j_tbl_4"><tr><td><a name="dg_tbl4_r0_c0"/>German</td></tr></table>\
                """;

        String html = DocumentPreviewService.normalizeBookmarkHtml(raw);

        assertTrue(html.contains("data-dg-id=\"dg_p48\""));
        assertTrue(html.contains("data-dg-id=\"dg_tbl4_r0_c0\""));
        assertTrue(html.contains("<td") && html.contains("dg_tbl4_r0_c0"));
        assertFalse(html.matches("(?s).*<p[^>]*data-dg-id=\"dg_p48\"[^>]*>\\s*<table.*"),
                "table must not be nested inside dg_p48 paragraph: " + html);
        assertTrue(html.contains("</p><table") || html.contains("</p>\n<table")
                || html.matches("(?s).*</p>\\s*<table.*"));
    }

    @Test
    void foldsOrphanSpansIntoParagraphButKeepsTableOutside() {
        String raw = """
                <p class="x"><a name="dg_p1"/></p><span>Hello</span>\
                <p class="y"><a name="dg_p2"/></p><table><tr><td>T</td></tr></table>\
                """;

        String html = DocumentPreviewService.normalizeBookmarkHtml(raw);

        assertTrue(html.contains("data-dg-id=\"dg_p1\""));
        assertTrue(html.contains("Hello"));
        assertFalse(html.matches("(?s).*<p[^>]*dg_p2[^>]*>\\s*<table.*"));
    }

    @Test
    void closesEmptyNestedBookmarkParagraphsBeforeTable() {
        String raw = """
                <p data-dg-id="dg_p46"><p data-dg-id="dg_p47"><p data-dg-id="dg_p48"><table id="t1"></table>\
                """;

        String html = DocumentPreviewService.normalizeBookmarkHtml(raw);

        assertFalse(html.contains("<p data-dg-id=\"dg_p48\"><table"));
        assertTrue(html.contains("</p><table") || html.matches("(?s).*</p>\\s*<table.*"));
    }
}
