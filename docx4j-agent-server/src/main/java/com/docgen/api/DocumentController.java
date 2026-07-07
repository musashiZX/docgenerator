package com.docgen.api;

import com.docgen.document.DocumentIndexService;
import com.docgen.document.DocumentLibraryService;
import com.docgen.document.DocumentLoader;
import com.docgen.document.DocumentMetadata;
import com.docgen.document.DocumentMetadataStore;
import com.docgen.model.StructuralIndex;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIndexService documentIndexService;
    private final DocumentLoader documentLoader;
    private final DocumentLibraryService libraryService;
    private final DocumentMetadataStore metadataStore;
    private final com.docgen.document.DocumentPreviewService previewService;

    public DocumentController(
            DocumentIndexService documentIndexService,
            DocumentLoader documentLoader,
            DocumentLibraryService libraryService,
            DocumentMetadataStore metadataStore,
            com.docgen.document.DocumentPreviewService previewService) {
        this.documentIndexService = documentIndexService;
        this.documentLoader = documentLoader;
        this.libraryService = libraryService;
        this.metadataStore = metadataStore;
        this.previewService = previewService;
    }

    @GetMapping
    public List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> documents = new ArrayList<>();
        try (Stream<Path> files = Files.list(documentLoader.docsDirectory())) {
            files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".docx"))
                    .filter(path -> !path.getFileName().toString().startsWith("~$"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        try {
                            String name = path.getFileName().toString();
                            long size = Files.size(path);
                            DocumentMetadata meta = metadataStore.read(name);
                            Instant modified = meta != null
                                    ? meta.updatedAt()
                                    : Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis());
                            Instant uploaded = meta != null
                                    ? meta.uploadedAt()
                                    : modified;
                            Map<String, Object> row = new HashMap<>();
                            row.put("name", name);
                            row.put("size", size);
                            row.put("modified_at", modified.toString());
                            row.put("uploaded_at", uploaded.toString());
                            if (meta != null) {
                                row.put("source", meta.source());
                            }
                            documents.add(row);
                        } catch (IOException ignored) {
                            // Skip files that vanish or are unreadable mid-listing.
                        }
                    });
        }
        return documents;
    }

    @GetMapping("/{name}/index")
    public StructuralIndex index(@PathVariable("name") String name) throws Exception {
        return documentIndexService.buildIndex(name);
    }

    @GetMapping(value = "/{name}/preview", produces = "text/html;charset=UTF-8")
    public String preview(@PathVariable("name") String name) throws Exception {
        return previewService.renderHtml(name);
    }

    /** Raw .docx bytes (inline). Same payload as {@link #download} without attachment header. */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> getBytes(@PathVariable("name") String name) throws IOException {
        Path path = documentLoader.resolveDoc(name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(Files.readAllBytes(path));
    }

    @GetMapping("/{name}/download")
    public ResponseEntity<byte[]> download(@PathVariable("name") String name) throws IOException {
        Path path = documentLoader.resolveDoc(name);
        String encoded = URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(Files.readAllBytes(path));
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Empty file.");
        }
        DocumentLibraryService.StoredDocument stored = libraryService.storeUpload(
                file.getOriginalFilename(), file.getBytes());
        return Map.of(
                "status", "ok",
                "name", stored.name(),
                "size", stored.size(),
                "uploaded_at", stored.uploadedAt().toString());
    }
}
