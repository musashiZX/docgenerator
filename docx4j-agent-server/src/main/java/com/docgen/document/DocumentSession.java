package com.docgen.document;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Holds one in-memory document for a propose/apply cycle and supports
 * byte-level snapshot/restore for whole-batch rollback.
 */
public class DocumentSession {

    private WordprocessingMLPackage document;

    public DocumentSession(WordprocessingMLPackage document) {
        this.document = document;
    }

    /** Live document. Re-fetch after {@link #restore(byte[])} — the instance is replaced. */
    public WordprocessingMLPackage document() {
        return document;
    }

    public byte[] snapshot() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not snapshot document.", e);
        }
    }

    public void restore(byte[] snapshot) {
        try {
            this.document = WordprocessingMLPackage.load(new ByteArrayInputStream(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException("Could not restore document snapshot.", e);
        }
    }
}
