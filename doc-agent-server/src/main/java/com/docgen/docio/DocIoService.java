package com.docgen.docio;

import com.docgen.config.AppProperties;
import com.docgen.trace.OperationRecorder;
import com.docgen.trace.OperationType;
import com.syncfusion.docio.FormatType;
import com.syncfusion.docio.WordDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class DocIoService {

    private static final Logger log = LoggerFactory.getLogger(DocIoService.class);

    private final Path docsDir;
    private final DocumentStore documentStore;
    private final OperationRecorder operationRecorder;

    public DocIoService(AppProperties appProperties, OperationRecorder operationRecorder) throws IOException {
        Path configured = Path.of(appProperties.docsDir());
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir")).resolve(configured);
        }
        this.docsDir = configured.toAbsolutePath().normalize();
        this.documentStore = new DocumentStore(this.docsDir);
        this.operationRecorder = operationRecorder;
        log.info("Document folder database: {}", this.docsDir);
    }

    public Path docsDirectory() {
        return docsDir;
    }

    public Path resolveDoc(String name) {
        String safe = sanitizeFilename(name);
        Path path = docsDir.resolve(safe).normalize();
        if (!path.startsWith(docsDir)) {
            throw new IllegalArgumentException("Invalid document path.");
        }
        if (!Files.exists(path)) {
            throw new DocumentNotFoundException(safe);
        }
        return path;
    }

    public List<Map<String, Object>> listDocumentsWithMeta() {
        try (Stream<Path> stream = Files.list(docsDir)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".docx"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(name -> rows.add(documentRow(name)));
            return rows;
        } catch (IOException e) {
            throw new DocIoException("Could not list documents.", e);
        }
    }

    public List<String> listDocuments() {
        return listDocumentsWithMeta().stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    private Map<String, Object> documentRow(String name) {
        DocumentMetadata meta = documentStore.readMetadata(name);
        if (meta != null) {
            return Map.of(
                    "name", meta.filename(),
                    "original_filename", meta.originalFilename(),
                    "uploaded_at", meta.uploadedAt().toString(),
                    "updated_at", meta.updatedAt().toString(),
                    "size_bytes", meta.sizeBytes(),
                    "source", meta.source());
        }
        try {
            long size = Files.size(docsDir.resolve(name));
            return Map.of("name", name, "size_bytes", size, "source", "legacy");
        } catch (IOException e) {
            return Map.of("name", name, "source", "legacy");
        }
    }

    /**
     * Save an uploaded .docx into the folder database after validating it opens in DocIO.
     *
     * @return saved filename (may differ from original if a duplicate existed)
     */
    public UploadResult saveUpload(String originalFilename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Empty file.");
        }
        validateDocxBytes(content);

        String desired = originalFilename == null || originalFilename.isBlank()
                ? "upload.docx"
                : originalFilename;
        String savedAs = documentStore.uniqueFilename(desired);
        Path target = docsDir.resolve(savedAs).normalize();
        if (!target.startsWith(docsDir)) {
            throw new IllegalArgumentException("Invalid document path.");
        }

        try {
            Files.write(target, content);
            DocumentMetadata meta = DocumentMetadata.uploaded(
                    savedAs,
                    originalFilename == null ? savedAs : originalFilename,
                    content.length);
            documentStore.saveMetadata(meta);
            log.info("Uploaded document saved as {} ({} bytes)", savedAs, content.length);
            operationRecorder.record(OperationType.DOCUMENT_UPLOAD, "Uploaded " + savedAs, Map.of(
                    "saved_as", savedAs,
                    "original_filename", originalFilename == null ? savedAs : originalFilename,
                    "bytes", content.length));
            String normalizedDesired = stripToDocxName(DocIoService.sanitizeFilename(desired));
            return new UploadResult(savedAs, desired, savedAs.equalsIgnoreCase(normalizedDesired));
        } catch (IOException e) {
            throw new DocIoException("Could not save upload.", e);
        }
    }

    public Path createEmpty(String name) {
        String safe = documentStore.uniqueFilename(name == null || name.isBlank() ? "new-document.docx" : name);
        Path target = docsDir.resolve(safe).normalize();
        if (!target.startsWith(docsDir)) {
            throw new IllegalArgumentException("Invalid document path.");
        }
        try (WordDocument document = new WordDocument()) {
            document.ensureMinimal();
            document.getLastParagraph().appendText("New document");
            document.save(target.toString(), FormatType.Docx);
            long size = Files.size(target);
            documentStore.saveMetadata(DocumentMetadata.created(safe, size));
            log.info("Created empty document {}", safe);
            operationRecorder.record(OperationType.DOCUMENT_CREATE, "Created " + safe,
                    Map.of("name", safe, "bytes", size));
            return target;
        } catch (Exception e) {
            throw new DocIoException("Could not create document.", e);
        }
    }

    public void markUpdated(String filename) {
        documentStore.touchUpdated(filename);
    }

    public int replaceText(Path docPath, String find, String replace) {
        try (WordDocument document = new WordDocument(docPath.toString())) {
            int count = document.replace(find, replace, false, false);
            document.save(docPath.toString(), FormatType.Docx);
            markUpdated(docPath.getFileName().toString());
            return count;
        } catch (Exception e) {
            throw new DocIoException("Replace failed.", e);
        }
    }

    public byte[] toHtmlBytes(Path docPath) {
        try (WordDocument document = new WordDocument(docPath.toString());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.save(out, FormatType.Html);
            return out.toByteArray();
        } catch (Exception e) {
            throw new DocIoException("HTML export failed.", e);
        }
    }

    public byte[] readBytes(Path docPath) {
        try {
            return Files.readAllBytes(docPath);
        } catch (IOException e) {
            throw new DocIoException("Could not read document.", e);
        }
    }

    private static void validateDocxBytes(byte[] content) {
        try (WordDocument document = new WordDocument(new ByteArrayInputStream(content))) {
            document.getSections();
        } catch (Exception e) {
            throw new IllegalArgumentException("File is not a valid .docx Word document.");
        }
    }

    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Filename is required.");
        }
        String base = Path.of(name).getFileName().toString().trim();
        if (base.contains("..") || base.contains("/") || base.contains("\\")) {
            throw new IllegalArgumentException("Invalid filename.");
        }
        return base;
    }

    private static String stripToDocxName(String name) {
        return name.toLowerCase().endsWith(".docx") ? name : name + ".docx";
    }

    public record UploadResult(String savedAs, String originalFilename, boolean nameUnchanged) {}

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(String name) {
            super("Document not found: " + name);
        }
    }

    public static class DocIoException extends RuntimeException {
        public DocIoException(String message, Throwable cause) {
            super(message, cause);
        }

        public DocIoException(String message) {
            super(message);
        }
    }
}
