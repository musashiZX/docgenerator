package com.docgen.api;

import com.docgen.document.DocumentLoader;
import com.docgen.document.DocumentSession;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.ApplyResult;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationApplier;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

/**
 * Dev-only direct batch apply, bypassing the proposal workflow.
 *
 * @deprecated Temporary until the Stage 5 propose/approve endpoints exist;
 * remove or gate behind a dev flag when ProposalService lands.
 */
@Deprecated
@RestController
@RequestMapping("/api/dev")
public class DevApplyController {

    private final DocumentLoader documentLoader;
    private final BookmarkIndexer bookmarkIndexer;
    private final StructuralIndexBuilder indexBuilder;
    private final MutationApplier mutationApplier;

    public DevApplyController(
            DocumentLoader documentLoader,
            BookmarkIndexer bookmarkIndexer,
            StructuralIndexBuilder indexBuilder,
            MutationApplier mutationApplier) {
        this.documentLoader = documentLoader;
        this.bookmarkIndexer = bookmarkIndexer;
        this.indexBuilder = indexBuilder;
        this.mutationApplier = mutationApplier;
    }

    @PostMapping("/apply/{name}")
    public Map<String, Object> apply(@PathVariable("name") String name, @RequestBody MutationBatch batch)
            throws Exception {

        Path path = documentLoader.resolveDoc(name);
        WordprocessingMLPackage document = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(document);
        StructuralIndex index = indexBuilder.build(document, name);

        DocumentSession session = new DocumentSession(document);
        ApplyResult result = mutationApplier.apply(session, batch, index);

        documentLoader.save(session.document(), path);
        return Map.of(
                "status", "ok",
                "applied_count", result.appliedCount(),
                "changed_ids", result.changedIds());
    }
}
