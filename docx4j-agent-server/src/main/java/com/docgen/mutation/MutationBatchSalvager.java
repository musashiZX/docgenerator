package com.docgen.mutation;

import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Removes individually invalid mutations so a mostly-correct LLM batch can still
 * be proposed (e.g. one wrong table cell should not block seven good edits).
 */
public final class MutationBatchSalvager {

    private static final Set<String> DROPPABLE_CODES = Set.of(
            "OLD_TEXT_NOT_FOUND",
            "UNKNOWN_TARGET",
            "NO_OP_MUTATION",
            "EMPTY_OLD_TEXT",
            "MISSING_NEW_TEXT");

    private MutationBatchSalvager() {
    }

    public static Optional<MutationBatch> dropInvalid(MutationBatch batch,
                                                      List<MutationValidator.ValidationError> errors) {
        if (batch == null || batch.mutations() == null || batch.mutations().isEmpty()) {
            return Optional.empty();
        }
        Set<Integer> drop = errors.stream()
                .filter(e -> e.mutationIndex() >= 0 && DROPPABLE_CODES.contains(e.code()))
                .map(MutationValidator.ValidationError::mutationIndex)
                .collect(java.util.stream.Collectors.toSet());
        if (drop.isEmpty()) {
            return Optional.empty();
        }

        List<Mutation> kept = new ArrayList<>();
        for (int i = 0; i < batch.mutations().size(); i++) {
            if (!drop.contains(i)) {
                kept.add(batch.mutations().get(i));
            }
        }
        if (kept.isEmpty()) {
            return Optional.empty();
        }

        String base = batch.explanation() == null ? "" : batch.explanation().trim();
        String note = "Dropped " + drop.size() + " invalid mutation(s) that could not be applied.";
        String explanation = base.isEmpty() ? note : base + " (" + note + ")";
        return Optional.of(new MutationBatch(batch.schemaVersion(), explanation, kept));
    }
}
