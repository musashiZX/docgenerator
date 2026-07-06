package com.docgen.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextNormalizerTest {

    @Test
    void collapsesWhitespace() {
        assertEquals("a b", TextNormalizer.normalize("a  b"));
        assertEquals("a b", TextNormalizer.normalize("  a \n b  "));
    }

    @Test
    void appliesNfc() {
        String composed = "e\u0301";
        String expected = "\u00e9";
        assertEquals(TextNormalizer.normalize(expected), TextNormalizer.normalize(composed));
    }
}
