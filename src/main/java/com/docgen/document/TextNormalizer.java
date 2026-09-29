package com.docgen.document;

import java.text.Normalizer;

/** Shared whitespace and Unicode rules for text compare and hashing. */
public final class TextNormalizer {

    private TextNormalizer() {}

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        return nfc.strip().replaceAll("\\s+", " ");
    }
}
