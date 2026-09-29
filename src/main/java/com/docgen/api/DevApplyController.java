package com.docgen.api;

import com.docgen.document.DocumentLoader;
import com.docgen.document.DocumentSession;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.ApplyResult;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationApplier;
import jakarta.servlet.http.HttpServletRequest;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

/**
 * Dev-only direct batch apply, bypassing the proposal workflow.
 * Enabled only when {@code app.dev-mode=true} (local default); production
 * traffic must go through {@code /api/proposals}.
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.dev-mode", havingValue = "true")
@RequestMapping("/api/dev")
public class DevApplyController {

    private static final Logger log = LoggerFactory.getLogger(DevApplyController.class);

    private final DocumentLoader documentLoader;
    private final BookmarkIndexer bookmarkIndexer;
    private final StructuralIndexBuilder indexBuilder;
    private final MutationApplier mutationApplier;
    private final com.docgen.document.DocumentLibraryService libraryService;

    public DevApplyController(
            DocumentLoader documentLoader,
            BookmarkIndexer bookmarkIndexer,
            StructuralIndexBuilder indexBuilder,
            MutationApplier mutationApplier,
            com.docgen.document.DocumentLibraryService libraryService) {
        this.documentLoader = documentLoader;
        this.bookmarkIndexer = bookmarkIndexer;
        this.indexBuilder = indexBuilder;
        this.mutationApplier = mutationApplier;
        this.libraryService = libraryService;
    }

    @PostMapping("/apply/{name}")
    public Map<String, Object> apply(
            @PathVariable("name") String name,
            @RequestBody MutationBatch batch,
            HttpServletRequest request)
            throws Exception {

        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.TRACE_ID_ATTR));
        int mutationCount = batch.mutations() == null ? 0 : batch.mutations().size();
        log.info("[trace:{}] DEV apply request doc={} mutations={}", traceId, name, mutationCount);

        Path path = documentLoader.resolveDoc(name);
        WordprocessingMLPackage document = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(document);
        StructuralIndex index = indexBuilder.build(document, name);

        DocumentSession session = new DocumentSession(document);
        ApplyResult result = mutationApplier.apply(session, batch, index);

        documentLoader.save(session.document(), path);
        libraryService.touchAfterMutation(name);
        log.info("[trace:{}] DEV apply success doc={} changed_ids={}",
                traceId, name, result.changedIds());
        return Map.of(
                "status", "ok",
                "applied_count", result.appliedCount(),
                "changed_ids", result.changedIds(),
                "created_ids", result.createdIds(),
                "formatted_ids", result.formattedIds(),
                "trace_id", traceId);
    }
}
