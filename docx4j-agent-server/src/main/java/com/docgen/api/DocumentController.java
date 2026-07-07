package com.docgen.api;

import com.docgen.document.DocumentIndexService;
import com.docgen.document.DocumentLoader;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIndexService documentIndexService;
    private final DocumentLoader documentLoader;

    public DocumentController(DocumentIndexService documentIndexService, DocumentLoader documentLoader) {
        this.documentIndexService = documentIndexService;
        this.documentLoader = documentLoader;
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
                            documents.add(Map.of(
                                    "name", path.getFileName().toString(),
                                    "size", Files.size(path),
                                    "modified_at", Instant.ofEpochMilli(
                                            Files.getLastModifiedTime(path).toMillis()).toString()));
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
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Empty file.");
        }
        String name = DocumentLoader.sanitizeFilename(file.getOriginalFilename());
        if (!name.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("Only .docx files are supported.");
        }
        byte[] content = file.getBytes();
        documentLoader.validate(content);

        Path target = documentLoader.docsDirectory().resolve(name);
        Files.write(target, content);
        return Map.of("status", "ok", "name", name, "size", content.length);
    }
}
