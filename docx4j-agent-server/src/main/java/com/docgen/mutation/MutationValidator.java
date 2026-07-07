package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
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

        Map<String, String> blockTexts = new HashMap<>();
        for (BlockDescriptor block : index.blocks()) {
            blockTexts.put(block.targetId(), block.text());
        }

        Set<String> seenTargets = new HashSet<>();
        for (int i = 0; i < batch.mutations().size(); i++) {
            Mutation mutation = batch.mutations().get(i);
            if (mutation instanceof ModifyMutation modify) {
                validateModify(i, modify, blockTexts, seenTargets, errors);
            } else {
                errors.add(new ValidationError(i, "UNSUPPORTED_OP",
                        "Unsupported op: " + mutation.op()));
            }
        }
        return errors;
    }

    private static void validateModify(
            int i,
            ModifyMutation modify,
            Map<String, String> blockTexts,
            Set<String> seenTargets,
            List<ValidationError> errors) {

        String targetId = modify.targetId();
        if (targetId == null || targetId.isBlank()) {
            errors.add(new ValidationError(i, "MISSING_TARGET", "target_id is required"));
            return;
        }
        if (!blockTexts.containsKey(targetId)) {
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
        int occurrence = modify.occurrenceOrDefault();
        if (occurrence < 0) {
            errors.add(new ValidationError(i, "BAD_OCCURRENCE", "occurrence must be >= 0"));
            return;
        }
        String blockText = blockTexts.get(targetId);
        if (countOccurrences(blockText, modify.oldText()) <= occurrence) {
            errors.add(new ValidationError(i, "OLD_TEXT_NOT_FOUND",
                    "old_text (occurrence " + occurrence + ") not found in " + targetId
                            + ": \"" + modify.oldText() + "\""));
        }
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
