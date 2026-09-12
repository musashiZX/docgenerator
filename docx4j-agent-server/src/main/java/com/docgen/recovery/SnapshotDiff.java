package com.docgen.recovery;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.StructuralIndex;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Block-level diff between two structural indexes (e.g. two commits, or a
 * commit and a checkpoint) — the "trace the changes" half of document
 * history: not just "here are two snapshots", but "here is what changed
 * between them", keyed by the same stable {@code target_id}s the rest of
 * the app already uses for mutations.
 */
public final class SnapshotDiff {

    private SnapshotDiff() {
    }

    public record ChangedBlock(
            @JsonProperty("target_id") String targetId,
            String type,
            @JsonProperty("before_text") String beforeText,
            @JsonProperty("after_text") String afterText) {
    }

    public record Result(
            List<ChangedBlock> added,
            List<ChangedBlock> removed,
            List<ChangedBlock> modified,
            @JsonProperty("unchanged_count") int unchangedCount) {
    }

    public static Result compute(StructuralIndex from, StructuralIndex to) {
        Map<String, BlockDescriptor> fromById = new LinkedHashMap<>();
        for (BlockDescriptor b : from.blocks()) {
            fromById.put(b.targetId(), b);
        }
        Map<String, BlockDescriptor> toById = new LinkedHashMap<>();
        for (BlockDescriptor b : to.blocks()) {
            toById.put(b.targetId(), b);
        }

        List<ChangedBlock> added = new ArrayList<>();
        List<ChangedBlock> removed = new ArrayList<>();
        List<ChangedBlock> modified = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<String, BlockDescriptor> entry : toById.entrySet()) {
            String id = entry.getKey();
            BlockDescriptor toBlock = entry.getValue();
            BlockDescriptor fromBlock = fromById.get(id);
            if (fromBlock == null) {
                added.add(new ChangedBlock(id, toBlock.type(), null, toBlock.text()));
            } else if (!fromBlock.text().equals(toBlock.text())) {
                modified.add(new ChangedBlock(id, toBlock.type(), fromBlock.text(), toBlock.text()));
            } else {
                unchanged++;
            }
        }
        for (Map.Entry<String, BlockDescriptor> entry : fromById.entrySet()) {
            String id = entry.getKey();
            if (!toById.containsKey(id)) {
                BlockDescriptor fromBlock = entry.getValue();
                removed.add(new ChangedBlock(id, fromBlock.type(), fromBlock.text(), null));
            }
        }

        return new Result(added, removed, modified, unchanged);
    }
}
