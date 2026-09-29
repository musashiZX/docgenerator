package com.docgen.onlyoffice;

import com.docgen.document.DocumentLoader;
import com.docgen.index.BookmarkIndexer;
import com.docgen.recovery.DocumentWorkspace;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * OnlyOffice's save-status callback protocol: it POSTs here whenever a
 * document is force-saved or every editor leaves. Status codes (from
 * https://api.onlyoffice.com/docs/docs-api/additional-api/callback-handler/):
 * 0 no doc, 1 still editing, 2 ready to save, 3 save error, 4 closed with no
 * changes, 6 force-saved while still editing, 7 force-save error. Only 2/6
 * carry a download URL for the new file.
 */
@RestController
@RequestMapping("/api/onlyoffice")
public class OnlyOfficeCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OnlyOfficeCallbackController.class);

    private final OnlyOfficeJwtService jwtService;
    private final DocumentLoader documentLoader;
    private final DocumentWorkspace workspace;
    private final BookmarkIndexer bookmarkIndexer;
    private final OnlyOfficeSaveCoordinator saveCoordinator;
    private final RestClient restClient = RestClient.create();

    public OnlyOfficeCallbackController(
            OnlyOfficeJwtService jwtService,
            DocumentLoader documentLoader,
            DocumentWorkspace workspace,
            BookmarkIndexer bookmarkIndexer,
            OnlyOfficeSaveCoordinator saveCoordinator) {
        this.jwtService = jwtService;
        this.documentLoader = documentLoader;
        this.workspace = workspace;
        this.bookmarkIndexer = bookmarkIndexer;
        this.saveCoordinator = saveCoordinator;
    }

    @PostMapping("/callback/{name}")
    public Map<String, Object> callback(
            @PathVariable("name") String name,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        String token = authHeader != null ? authHeader : String.valueOf(body.get("token"));
        if (!jwtService.verifyCallbackToken(token)) {
            log.warn("Rejected OnlyOffice callback for {}: bad/missing JWT.", name);
            return Map.of("error", 1);
        }

        Object statusObj = body.get("status");
        int status = statusObj instanceof Number n ? n.intValue() : -1;
        String downloadUrl = (String) body.get("url");

        if ((status == 2 || status == 6) && downloadUrl != null) {
            try {
                saveIncomingDocument(name, downloadUrl);
                saveCoordinator.complete(name);
            } catch (Exception e) {
                log.error("Failed to persist OnlyOffice save for {}: {}", name, e.toString());
                saveCoordinator.completeExceptionally(name, e);
                return Map.of("error", 1);
            }
        } else if (status == 4) {
            // Closed with no changes to save — nothing to persist, but a
            // pending forcesave-and-wait should still unblock.
            saveCoordinator.complete(name);
        } else if (status == 3 || status == 7) {
            saveCoordinator.completeExceptionally(name, new IllegalStateException("OnlyOffice reported save status " + status));
        }
        // status 1 (still editing) needs no action.
        return Map.of("error", 0);
    }

    private void saveIncomingDocument(String name, String downloadUrl) {
        Path path = documentLoader.resolveDoc(name);
        byte[] before = readBytes(path);

        byte[] incoming = restClient.get().uri(downloadUrl)
                .retrieve()
                .body(byte[].class);
        if (incoming == null || incoming.length == 0) {
            throw new IllegalStateException("OnlyOffice download URL returned no content.");
        }

        workspace.createCheckpointBeforeManualEdit(name, before);
        writeBytes(path, incoming);

        // Any paragraph/cell the user typed from scratch has no dg_ bookmark
        // yet — backfill those so the AI-mutation system can still address
        // the new content, exactly as propose() does after an AI edit.
        WordprocessingMLPackage document = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(document);
        documentLoader.save(document, path);
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw new RuntimeException("Could not read " + path, e);
        }
    }

    private static void writeBytes(Path path, byte[] bytes) {
        try {
            Files.write(path, bytes);
        } catch (Exception e) {
            throw new RuntimeException("Could not write " + path, e);
        }
    }
}
