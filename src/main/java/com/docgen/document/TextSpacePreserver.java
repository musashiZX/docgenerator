package com.docgen.document;

import org.docx4j.TraversalUtil;
import org.docx4j.finders.ClassFinder;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.Text;

/**
 * docx4j drops {@code w:t} nodes whose text is whitespace-only (or has leading /
 * trailing spaces) unless {@code xml:space="preserve"} is set. PDF-converted docs
 * often put inter-word spaces in dedicated runs — mark those before every save.
 */
public final class TextSpacePreserver {

    private TextSpacePreserver() {}

    public static void ensurePreserved(WordprocessingMLPackage document) {
        MainDocumentPart main = document.getMainDocumentPart();
        if (main != null && main.getJaxbElement() != null) {
            markTextNodes(main.getJaxbElement());
        }
        for (Part part : document.getParts().getParts().values()) {
            if (part instanceof HeaderPart header && header.getJaxbElement() != null) {
                markTextNodes(header.getJaxbElement());
            } else if (part instanceof FooterPart footer && footer.getJaxbElement() != null) {
                markTextNodes(footer.getJaxbElement());
            }
        }
    }

    static boolean needsPreserve(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.chars().allMatch(Character::isWhitespace)) {
            return true;
        }
        return Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1));
    }

    private static void markTextNodes(Object root) {
        ClassFinder finder = new ClassFinder(Text.class);
        new TraversalUtil(root, finder);
        for (Object found : finder.results) {
            Text text = (Text) found;
            if (needsPreserve(text.getValue())) {
                text.setSpace("preserve");
            }
        }
    }
}
