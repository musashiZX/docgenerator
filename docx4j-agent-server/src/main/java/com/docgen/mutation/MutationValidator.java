package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.DeleteMutation;
import com.docgen.model.InsertMutation;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rejects invalid batches before any document mutation. Closed-world target ids. */
@Component
public class MutationValidator {

    public record ValidationError(int mutationIndex, String code, String message) {
    }

    public List<ValidationError> validate(MutationBatch batch, StructuralIndex index) {
        List<ValidationError> errors = new ArrayList<>();

        if (batch.schemaVersion() != 1) {
            errors.add(new ValidationError(-1, "UNSUPPORTED_SCHEMA",
                    "schema_version must be 1, got " + batch.schemaVersion()));
        }
        if (batch.mutations() == null || batch.mutations().isEmpty()) {
            errors.add(new ValidationError(-1, "EMPTY_BATCH", "mutations must be non-empty"));
            return errors;
        }

        Map<String, BlockDescriptor> blocks = new HashMap<>();
        for (BlockDescriptor block : index.blocks()) {
            blocks.put(block.targetId(), block);
        }

        // One content mutation (modify/delete) per target id; inserts have their
        // own anchor+position uniqueness and may not anchor on a deleted block.
        Set<String> seenTargets = new HashSet<>();
        Set<String> deletedTargets = new HashSet<>();
        Set<String> seenAnchors = new HashSet<>();

        for (int i = 0; i < batch.mutations().size(); i++) {
            Mutation mutation = batch.mutations().get(i);
            switch (mutation) {
                case ModifyMutation modify -> validateModify(i, modify, blocks, seenTargets, errors);
                case InsertMutation insert ->
                        validateInsert(i, insert, batch.mutations(), blocks, seenAnchors, deletedTargets, errors);
                case DeleteMutation delete ->
                        validateDelete(i, delete, blocks, seenTargets, deletedTargets, errors);
            }
        }
        return errors;
    }

    private static void validateModify(
            int i,
            ModifyMutation modify,
            Map<String, BlockDescriptor> blocks,
            Set<String> seenTargets,
            List<ValidationError> errors) {

        String targetId = modify.targetId();
        if (targetId == null || targetId.isBlank()) {
            errors.add(new ValidationError(i, "MISSING_TARGET", "target_id is required"));
            return;
        }
        if (!blocks.containsKey(targetId)) {
            errors.add(new ValidationError(i, "UNKNOWN_TARGET",
                    "target_id not in this document's index: " + targetId));
            return;
        }
        if (!seenTargets.add(targetId)) {
            errors.add(new ValidationError(i, "DUPLICATE_TARGET",
                    "At most one mutation per target_id per batch: " + targetId));
            return;
        }
        if (modify.oldText() == null || modify.oldText().isEmpty()) {
            errors.add(new ValidationError(i, "EMPTY_OLD_TEXT", "old_text must be non-empty"));
            return;
        }
        if (modify.newText() == null) {
            errors.add(new ValidationError(i, "MISSING_NEW_TEXT", "new_text is required"));
            return;
        }
        if (modify.newText().equals(modify.oldText())) {
            errors.add(new ValidationError(i, "NO_OP_MUTATION",
                    "new_text equals old_text for " + targetId
                            + " — a modify must change the text (the hash guard rejects no-ops)"));
            return;
        }
        int occurrence = modify.occurrenceOrDefault();
        if (occurrence < 0) {
            errors.add(new ValidationError(i, "BAD_OCCURRENCE", "occurrence must be >= 0"));
            return;
        }
        String blockText = blocks.get(targetId).text();
        if (countOccurrences(blockText, modify.oldText()) <= occurrence) {
            errors.add(new ValidationError(i, "OLD_TEXT_NOT_FOUND",
                    "old_text (occurrence " + occurrence + ") not found in " + targetId
                            + ": \"" + modify.oldText() + "\""));
        }
    }

    private static void validateInsert(
            int i,
            InsertMutation insert,
            List<Mutation> mutations,
            Map<String, BlockDescriptor> blocks,
            Set<String> seenAnchors,
            Set<String> deletedTargets,
            List<ValidationError> errors) {

        String anchorId = insert.anchorId();
        if (anchorId == null || anchorId.isBlank()) {
            errors.add(new ValidationError(i, "MISSING_ANCHOR", "anchor_id is required"));
            return;
        }
        BlockDescriptor anchor = blocks.get(anchorId);
        if (anchor == null) {
            errors.add(new ValidationError(i, "UNKNOWN_TARGET",
                    "anchor_id not in this document's index: " + anchorId));
            return;
        }
        if (!"paragraph".equals(anchor.type())) {
            errors.add(new ValidationError(i, "UNSUPPORTED_ANCHOR",
                    "Insert anchors must be body paragraphs, not " + anchor.type() + ": " + anchorId));
            return;
        }
        if (deletedTargets.contains(anchorId)) {
            errors.add(new ValidationError(i, "ANCHOR_DELETED",
                    "Cannot anchor an insert on a block deleted in the same batch: " + anchorId));
            return;
        }
        if (!"before".equals(insert.position()) && !"after".equals(insert.position())) {
            errors.add(new ValidationError(i, "BAD_POSITION",
                    "position must be \"before\" or \"after\", got: " + insert.position()));
            return;
        }
        if (insert.nodeType() != null && !"paragraph".equals(insert.nodeType())) {
            errors.add(new ValidationError(i, "UNSUPPORTED_NODE_TYPE",
                    "Only node_type \"paragraph\" is supported in v1, got: " + insert.nodeType()));
            return;
        }
        if (insert.text() == null) {
            errors.add(new ValidationError(i, "MISSING_TEXT", "text is required for insert"));
            return;
        }
        // Consecutive "after" inserts on the same anchor chain into multiple
        // paragraphs (line 1, line 2, …). Only the first needs a unique slot.
        String anchorKey = anchorId + "#" + insert.position();
        if ("after".equals(insert.position()) && i > 0) {
            Mutation prev = mutations.get(i - 1);
            if (prev instanceof InsertMutation prevInsert
                    && anchorId.equals(prevInsert.anchorId())
                    && "after".equals(prevInsert.position())) {
                return;
            }
        }
        if (!seenAnchors.add(anchorKey)) {
            errors.add(new ValidationError(i, "DUPLICATE_ANCHOR",
                    "At most one insert per anchor+position per batch: "
                            + anchorId + " " + insert.position()
                            + " (use consecutive \"after\" inserts to add multiple lines)"));
        }
    }

    private static void validateDelete(
            int i,
            DeleteMutation delete,
            Map<String, BlockDescriptor> blocks,
            Set<String> seenTargets,
            Set<String> deletedTargets,
            List<ValidationError> errors) {

        String targetId = delete.targetId();
        if (targetId == null || targetId.isBlank()) {
            errors.add(new ValidationError(i, "MISSING_TARGET", "target_id is required"));
            return;
        }
        BlockDescriptor block = blocks.get(targetId);
        if (block == null) {
            errors.add(new ValidationError(i, "UNKNOWN_TARGET",
                    "target_id not in this document's index: " + targetId));
            return;
        }
        if (!"paragraph".equals(block.type())) {
            errors.add(new ValidationError(i, "UNSUPPORTED_DELETE",
                    "Only body paragraphs can be deleted in v1, not " + block.type() + ": " + targetId));
            return;
        }
        if (!seenTargets.add(targetId)) {
            errors.add(new ValidationError(i, "DUPLICATE_TARGET",
                    "At most one mutation per target_id per batch: " + targetId));
            return;
        }
        deletedTargets.add(targetId);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + 1;
        }
    }

    public static class MutationValidationException extends RuntimeException {
        private final List<ValidationError> errors;

        public MutationValidationException(List<ValidationError> errors) {
            super("Mutation batch failed validation: " + errors);
            this.errors = errors;
        }

        public List<ValidationError> errors() {
            return errors;
        }
    }
}
