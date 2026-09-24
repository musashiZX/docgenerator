package com.docgen.onlyoffice;

import com.docgen.config.OnlyOfficeProperties;
import com.docgen.document.DocumentLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hands the frontend everything it needs to embed the OnlyOffice editor for
 * one document: the Document Server's own base URL (the browser loads its
 * JS API directly from there) plus a signed config pointing back at this
 * app's document/callback endpoints.
 */
@RestController
@RequestMapping("/api/onlyoffice")
public class OnlyOfficeEditorController {

    private final OnlyOfficeProperties properties;
    private final OnlyOfficeJwtService jwtService;
    private final DocumentLoader documentLoader;

    public OnlyOfficeEditorController(
            OnlyOfficeProperties properties,
            OnlyOfficeJwtService jwtService,
            DocumentLoader documentLoader) {
        this.properties = properties;
        this.jwtService = jwtService;
        this.documentLoader = documentLoader;
    }

    @GetMapping("/editor-config/{name}")
    public Map<String, Object> editorConfig(@PathVariable("name") String name) throws IOException {
        Path path = documentLoader.resolveDoc(name);
        String encodedName = UriUtils.encodePathSegment(path.getFileName().toString(), StandardCharsets.UTF_8);
        String key = editorKey(name, path);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", "docx");
        document.put("key", key);
        document.put("title", path.getFileName().toString());
        document.put("url", properties.callbackBaseUrl() + "/api/documents/" + encodedName);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "docgen-user");
        user.put("name", "DocGen");

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", "edit");
        editorConfig.put("lang", "en");
        editorConfig.put("callbackUrl", properties.callbackBaseUrl() + "/api/onlyoffice/callback/" + encodedName);
        editorConfig.put("user", user);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("document", document);
        config.put("documentType", "word");
        config.put("editorConfig", editorConfig);
        config.put("width", "100%");
        config.put("height", "100%");

        String token = jwtService.signConfig(config);
        if (token != null) {
            config.put("token", token);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentServerUrl", properties.documentServerUrl());
        response.put("config", config);
        return response;
    }

    /**
     * Same doc, unchanged on disk -> same key, so OnlyOffice can resume a
     * live co-editing session. Any change to the file (an AI-approved edit,
     * a previous OnlyOffice save) changes mtime/size -> new key -> forces a
     * fresh load instead of serving a stale cached copy.
     */
    private static String editorKey(String name, Path path) throws IOException {
        long mtime = Files.getLastModifiedTime(path).toMillis();
        long size = Files.size(path);
        int nameHash = name.hashCode();
        return Integer.toHexString(nameHash) + "_" + mtime + "_" + size;
    }
}
