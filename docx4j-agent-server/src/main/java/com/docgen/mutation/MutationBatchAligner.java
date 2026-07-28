package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Drops LLM mutations whose old_text is not present in the indexed block text. */
public final class MutationBatchAligner {

    private MutationBatchAligner() {
    }

    public static MutationBatch alignToIndex(MutationBatch batch, StructuralIndex index) {
        if (batch == null || batch.mutations() == null || batch.mutations().isEmpty()) {
            return batch;
        }
        Map<String, String> texts = new HashMap<>();
        for (BlockDescriptor block : index.blocks()) {
            texts.put(block.targetId(), block.text() == null ? "" : block.text());
        }

        List<Mutation> kept = new ArrayList<>();
        int dropped = 0;
        for (Mutation mutation : batch.mutations()) {
            if (mutation instanceof ModifyMutation modify) {
                if (!texts.containsKey(modify.targetId())) {
                    kept.add(mutation);
                    continue;
                }
                String text = texts.get(modify.targetId());
                if (countOccurrences(text, modify.oldText()) <= modify.occurrenceOrDefault()) {
                    dropped++;
                    continue;
                }
            }
            kept.add(mutation);
        }
        if (dropped == 0) {
            return batch;
        }
        String base = batch.explanation() == null ? "" : batch.explanation().trim();
        String note = "Skipped " + dropped + " mutation(s) with mismatched old_text.";
        String explanation = base.isEmpty() ? note : base + " (" + note + ")";
        return new MutationBatch(batch.schemaVersion(), explanation, kept);
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
}
