package com.docgen.document;

import com.docgen.config.AppProperties;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DocumentLoader {

    private final Path docsDir;

    public DocumentLoader(AppProperties appProperties) throws IOException {
        Path configured = Path.of(appProperties.docsDir());
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir")).resolve(configured);
        }
        this.docsDir = configured.toAbsolutePath().normalize();
        Files.createDirectories(this.docsDir);
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

    public static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Filename is required.");
        }
        String base = Path.of(name).getFileName().toString().trim();
        if (base.contains("..") || base.contains("/") || base.contains("\\")) {
            throw new IllegalArgumentException("Invalid filename.");
        }
        return base;
    }

    public WordprocessingMLPackage load(Path path) {
        try {
            return WordprocessingMLPackage.load(path.toFile());
        } catch (Exception e) {
            throw new DocumentLoadException("Could not load document: " + path, e);
        }
    }

    public void save(WordprocessingMLPackage document, Path path) {
        try {
            Files.createDirectories(path.getParent());
            document.save(path.toFile());
        } catch (Exception e) {
            throw new DocumentLoadException("Could not save document: " + path, e);
        }
    }

    public void validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Empty file.");
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            WordprocessingMLPackage.load(in);
        } catch (Exception e) {
            throw new IllegalArgumentException("File is not a valid .docx Word document.", e);
        }
    }

    public static class DocumentLoadException extends RuntimeException {
        public DocumentLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(String name) {
            super("Document not found: " + name);
        }
    }
}
