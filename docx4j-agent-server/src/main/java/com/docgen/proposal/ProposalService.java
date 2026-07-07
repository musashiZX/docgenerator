package com.docgen.proposal;

import com.docgen.document.DocumentLoader;
import com.docgen.document.DocumentSession;
import com.docgen.index.BookmarkIndexer;
import com.docgen.index.StructuralIndexBuilder;
import com.docgen.model.ApplyResult;
import com.docgen.model.BlockDescriptor;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import com.docgen.mutation.MutationApplier;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Propose → review → approve/reject workflow. A proposal is dry-run applied
 * in memory to produce reviewable before/after diffs; the working .docx on
 * disk is only rewritten when the proposal is approved.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private final DocumentLoader documentLoader;
    private final BookmarkIndexer bookmarkIndexer;
    private final StructuralIndexBuilder indexBuilder;
    private final MutationApplier mutationApplier;
    private final ProposalStore store;
    private final com.docgen.document.DocumentLibraryService libraryService;

    public ProposalService(
            DocumentLoader documentLoader,
            BookmarkIndexer bookmarkIndexer,
            StructuralIndexBuilder indexBuilder,
            MutationApplier mutationApplier,
            ProposalStore store,
            com.docgen.document.DocumentLibraryService libraryService) {
        this.documentLoader = documentLoader;
        this.bookmarkIndexer = bookmarkIndexer;
        this.indexBuilder = indexBuilder;
        this.mutationApplier = mutationApplier;
        this.store = store;
        this.libraryService = libraryService;
    }

    /**
     * Validate and dry-run the batch against the current document, persist a
     * PENDING proposal with per-block diffs. The working document is untouched.
     */
    public Proposal propose(String docName, MutationBatch batch,
                            String source, String prompt, String model) throws Exception {
        Path path = documentLoader.resolveDoc(docName);
        WordprocessingMLPackage document = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(document);
        documentLoader.save(document, path); // persist bookmarks so ids stay stable
        StructuralIndex index = indexBuilder.build(document, docName);

        DocumentSession dryRun = new DocumentSession(document);
        byte[] before = dryRun.snapshot();
        Map<String, String> beforeTexts = blockTexts(index);

        // Throws (validation/stale/invariant) if the batch is not applyable.
        ApplyResult result = mutationApplier.apply(dryRun, batch, index);

        StructuralIndex afterIndex = indexBuilder.build(dryRun.document(), docName);
        List<BlockDiff> diffs = buildDiffs(beforeTexts, afterIndex, result);

        Proposal proposal = new Proposal(
                UUID.randomUUID().toString(),
                docName,
                ProposalStatus.PENDING,
                source,
                prompt,
                model,
                Instant.now(),
                null,
                batch,
                diffs);
        store.save(proposal, before);
        log.info("Proposal {} created for {} ({} mutations, source={})",
                proposal.id(), docName, batch.mutations().size(), source);
        return proposal;
    }

    /**
     * Re-apply the batch against the CURRENT document on disk and save it.
     * Fails (409/500) if the document drifted since the proposal was created.
     */
    public ApplyResult approve(String proposalId) throws Exception {
        Proposal proposal = requirePending(proposalId);

        Path path = documentLoader.resolveDoc(proposal.docName());
        WordprocessingMLPackage document = documentLoader.load(path);
        bookmarkIndexer.ensureBookmarks(document);
        StructuralIndex index = indexBuilder.build(document, proposal.docName());

        DocumentSession session = new DocumentSession(document);
        ApplyResult result = mutationApplier.apply(session, proposal.batch(), index);

        documentLoader.save(session.document(), path);
        libraryService.touchAfterMutation(proposal.docName());
        store.update(proposal.withStatus(ProposalStatus.APPROVED, Instant.now()));
        log.info("Proposal {} approved; {} changed blocks in {}",
                proposalId, result.changedIds().size(), proposal.docName());
        return result;
    }

    public Proposal reject(String proposalId) {
        Proposal proposal = requirePending(proposalId);
        Proposal rejected = proposal.withStatus(ProposalStatus.REJECTED, Instant.now());
        store.update(rejected);
        log.info("Proposal {} rejected", proposalId);
        return rejected;
    }

    public Proposal get(String proposalId) {
        return store.load(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
    }

    public List<Proposal> list(String docName) {
        return store.list(docName);
    }

    private Proposal requirePending(String proposalId) {
        Proposal proposal = get(proposalId);
        if (proposal.status() != ProposalStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Proposal " + proposalId + " is already " + proposal.status());
        }
        return proposal;
    }

    /** Diffs in document order: changed/inserted blocks first, then deletions. */
    private static List<BlockDiff> buildDiffs(
            Map<String, String> beforeTexts, StructuralIndex afterIndex, ApplyResult result) {

        Map<String, String> afterTexts = new LinkedHashMap<>();
        for (BlockDescriptor block : afterIndex.blocks()) {
            afterTexts.put(block.targetId(), block.text());
        }

        List<BlockDiff> diffs = new ArrayList<>();
        for (Map.Entry<String, String> after : afterTexts.entrySet()) {
            String id = after.getKey();
            if (!result.changedIds().contains(id)) {
                continue;
            }
            String op = result.createdIds().contains(id) ? "insert" : "modify";
            diffs.add(new BlockDiff(id, op, beforeTexts.get(id), after.getValue()));
        }
        for (String id : result.changedIds()) {
            if (!afterTexts.containsKey(id)) {
                diffs.add(new BlockDiff(id, "delete", beforeTexts.get(id), null));
            }
        }
        return diffs;
    }

    private static Map<String, String> blockTexts(StructuralIndex index) {
        Map<String, String> texts = new HashMap<>();
        for (BlockDescriptor block : index.blocks()) {
            texts.put(block.targetId(), block.text());
        }
        return texts;
    }

    public static class ProposalNotFoundException extends RuntimeException {
        public ProposalNotFoundException(String id) {
            super("Proposal not found: " + id);
        }
    }
}
