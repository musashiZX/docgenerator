package com.docgen.api;

import com.docgen.docio.DocIoService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocIoService docIoService;

    public DocumentController(DocIoService docIoService) {
        this.docIoService = docIoService;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documents", docIoService.listDocumentsWithMeta());
        body.put("storage_dir", docIoService.docsDirectory().toString());
        return body;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "new-document");
        Path saved = docIoService.createEmpty(name);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("name", saved.getFileName().toString()));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Empty file.");
        }
        String original = file.getOriginalFilename();
        DocIoService.UploadResult result = docIoService.saveUpload(original, file.getBytes());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("saved_as", result.savedAs());
        response.put("original_filename", result.originalFilename());
        response.put("renamed", !result.nameUnchanged());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{name}/download")
    public ResponseEntity<byte[]> download(@PathVariable String name) {
        Path path = docIoService.resolveDoc(name);
        byte[] bytes = docIoService.readBytes(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }

    @GetMapping("/{name}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String name) {
        Path path = docIoService.resolveDoc(name);
        byte[] html = wrapPreviewWithSelectionBridge(docIoService.toHtmlBytes(path));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /** Lets the React parent read text the user highlights inside the preview iframe. */
    private static byte[] wrapPreviewWithSelectionBridge(byte[] htmlBytes) {
        String html = new String(htmlBytes, java.nio.charset.StandardCharsets.UTF_8);
        String script = """
                <script>
                (function () {
                  function publishSelection() {
                    var text = (window.getSelection && window.getSelection().toString() || '').trim();
                    if (!text || window.parent === window) return;
                    window.parent.postMessage({ type: 'docgen-selection', text: text }, '*');
                  }
                  document.addEventListener('mouseup', publishSelection);
                  document.addEventListener('keyup', publishSelection);
                })();
                </script>
                """;
        if (html.contains("</body>")) {
            html = html.replace("</body>", script + "</body>");
        } else {
            html = html + script;
        }
        return html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Dev helper — format-preserving find/replace via DocIO. */
    @PostMapping("/{name}/replace")
    public Map<String, Object> replace(
            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        String find = body.get("find");
        String replace = body.get("replace");
        if (find == null || find.isBlank()) {
            throw new IllegalArgumentException("'find' is required.");
        }
        Path path = docIoService.resolveDoc(name);
        int count = docIoService.replaceText(path, find, replace == null ? "" : replace);
        return Map.of(
                "document", name,
                "replacements", count,
                "preview_url", "/api/documents/" + name + "/preview");
    }
}
