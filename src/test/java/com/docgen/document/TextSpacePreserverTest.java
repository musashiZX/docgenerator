package com.docgen.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSpacePreserverTest {

    @Test
    void needsPreserveForWhitespaceOnlyRun() {
        assertTrue(TextSpacePreserver.needsPreserve(" "));
        assertTrue(TextSpacePreserver.needsPreserve("  "));
    }

    @Test
    void needsPreserveForLeadingOrTrailingWhitespace() {
        assertTrue(TextSpacePreserver.needsPreserve(" word"));
        assertTrue(TextSpacePreserver.needsPreserve("word "));
        assertTrue(TextSpacePreserver.needsPreserve(" word "));
    }

    @Test
    void doesNotNeedPreserveForPlainWord() {
        assertFalse(TextSpacePreserver.needsPreserve("Product"));
        assertFalse(TextSpacePreserver.needsPreserve("name:"));
    }
}
