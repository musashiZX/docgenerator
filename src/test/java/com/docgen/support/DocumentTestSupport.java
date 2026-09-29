package com.docgen.support;

import org.docx4j.TextUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;

import java.util.List;

public final class DocumentTestSupport {

    private DocumentTestSupport() {}

    public static String firstParagraphPlainText(WordprocessingMLPackage document) throws Exception {
        List<Object> content = document.getMainDocumentPart().getContent();
        for (Object node : content) {
            if (node instanceof P paragraph) {
                return TextUtils.getText(paragraph).trim();
            }
        }
        throw new IllegalStateException("Document has no paragraph.");
    }
}
