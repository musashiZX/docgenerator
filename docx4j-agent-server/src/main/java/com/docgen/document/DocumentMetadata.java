package com.docgen.document;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Sidecar metadata stored beside each .docx in {@code docs/.meta/}. */
public record DocumentMetadata(
        String filename,
        @JsonProperty("original_filename") String originalFilename,
        @JsonProperty("uploaded_at") Instant uploadedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("size_bytes") long sizeBytes,
        String source
) {
    public DocumentMetadata withUpdated(Instant updatedAt, long sizeBytes) {
        return new DocumentMetadata(filename, originalFilename, uploadedAt, updatedAt, sizeBytes, source);
    }

    public static DocumentMetadata uploaded(String filename, String originalFilename, long sizeBytes) {
        Instant now = Instant.now();
        return new DocumentMetadata(filename, originalFilename, now, now, sizeBytes, "upload");
    }

    public static DocumentMetadata created(String filename, long sizeBytes) {
        Instant now = Instant.now();
        return new DocumentMetadata(filename, filename, now, now, sizeBytes, "create");
    }
}
