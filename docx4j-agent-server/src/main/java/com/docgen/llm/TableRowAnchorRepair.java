package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.InsertMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Post-LLM repair for {@code table_row} inserts: if the user request mentions
 * existing table-row text, ensure the insert anchors on that row — without
 * inventing the mutation itself (the LLM still chooses op/cells/position).
 */
public final class TableRowAnchorRepair {

    private TableRowAnchorRepair() {
    }

    public static MutationBatch repair(String request, MutationBatch batch, StructuralIndex index) {
        if (request == null || batch == null || batch.mutations() == null || index == null) {
            return batch;
        }
        Map<String, List<BlockDescriptor>> rows = groupRows(index);
        if (rows.isEmpty()) {
            return batch;
        }

        boolean changed = false;
        List<Mutation> out = new ArrayList<>();
        for (Mutation mutation : batch.mutations()) {
            if (!(mutation instanceof InsertMutation insert) || !insert.isTableRow()) {
                out.add(mutation);
                continue;
            }
            Optional<String> better = suggestAnchor(request, insert.anchorId(), rows);
            if (better.isPresent() && !better.get().equals(insert.anchorId())) {
                changed = true;
                out.add(new InsertMutation(
                        insert.op(),
                        better.get(),
                        insert.position(),
                        insert.nodeType(),
                        insert.text(),
                        insert.style(),
                        insert.cells()));
            } else {
                out.add(mutation);
            }
        }
        if (!changed) {
            return batch;
        }
        String base = batch.explanation() == null ? "" : batch.explanation().trim();
        String note = "Repaired table_row anchor to the row referenced in the user request.";
        String explanation = base.isEmpty() ? note : base + " (" + note + ")";
        return new MutationBatch(batch.schemaVersion(), explanation, out);
    }

    /**
     * Hint lines for LLM retries: which indexed rows contain text from the request.
     */
    public static String retryHint(String request, StructuralIndex index) {
        Map<String, List<BlockDescriptor>> rows = groupRows(index);
        List<ScoredRow> matches = scoreAllRows(request, rows);
        if (matches.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "The user named an existing table row. For table_row insert, anchor_id MUST be a cell in that row:\n");
        int n = 0;
        for (ScoredRow match : matches) {
            if (n++ >= 5) {
                break;
            }
            sb.append("- row ").append(match.key())
                    .append(" (e.g. ").append(match.anchorId()).append("): ")
                    .append(match.summary()).append('\n');
        }
        return sb.toString();
    }

    static Optional<String> suggestAnchor(
            String request, String currentAnchor, Map<String, List<BlockDescriptor>> rows) {
        List<ScoredRow> scored = scoreAllRows(request, rows);
        if (scored.isEmpty()) {
            return Optional.empty();
        }
        ScoredRow best = scored.getFirst();
        // Keep the LLM's choice when it already targets a top-scoring named row.
        if (currentAnchor != null) {
            for (ScoredRow row : scored) {
                if (row.score() < best.score()) {
                    break;
                }
                if (rowContainsId(row.cells(), currentAnchor)) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(best.anchorId());
    }

    private static List<ScoredRow> scoreAllRows(String request, Map<String, List<BlockDescriptor>> rows) {
        String haystack = normalize(request);
        List<ScoredRow> scored = new ArrayList<>();
        for (Map.Entry<String, List<BlockDescriptor>> entry : rows.entrySet()) {
            int score = scoreRowAgainstRequest(entry.getValue(), haystack);
            if (score > 0) {
                scored.add(new ScoredRow(entry.getKey(), entry.getValue(), score,
                        preferredAnchor(entry.getValue()), rowSummary(entry.getValue())));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredRow::score).reversed());
        return scored;
    }

    private static int scoreRowAgainstRequest(List<BlockDescriptor> cells, String request) {
        int score = 0;
        StringBuilder concat = new StringBuilder();
        for (BlockDescriptor cell : cells) {
            String text = cell.text() == null ? "" : cell.text().trim();
            if (text.isEmpty() || isWeakToken(text)) {
                continue;
            }
            String normalized = normalize(text);
            if (normalized.length() >= 8 && request.contains(normalized)) {
                score = Math.max(score, 40 + Math.min(40, normalized.length()));
            }
            if (!concat.isEmpty()) {
                concat.append(' ');
            }
            concat.append(normalized);
        }
        String rowText = concat.toString();
        if (rowText.length() >= 12 && request.contains(rowText)) {
            score = Math.max(score, 90 + Math.min(10, rowText.length() / 10));
        }
        return score;
    }

    private static boolean isWeakToken(String text) {
        String t = text.trim();
        return t.length() <= 3
                || t.equalsIgnoreCase("yes")
                || t.equalsIgnoreCase("no")
                || t.equalsIgnoreCase("true")
                || t.equalsIgnoreCase("false");
    }

    private static boolean rowContainsId(List<BlockDescriptor> cells, String targetId) {
        for (BlockDescriptor cell : cells) {
            if (targetId.equals(cell.targetId())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<BlockDescriptor>> groupRows(StructuralIndex index) {
        Map<String, List<BlockDescriptor>> rows = new LinkedHashMap<>();
        for (BlockDescriptor block : index.blocks()) {
            if (!"table_cell".equals(block.type())
                    || block.tableIndex() == null
                    || block.row() == null) {
                continue;
            }
            String key = "tbl" + block.tableIndex() + "/r" + block.row();
            rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(block);
        }
        for (List<BlockDescriptor> cells : rows.values()) {
            cells.sort(Comparator.comparing(b -> b.col() == null ? 0 : b.col()));
        }
        return rows;
    }

    private static String preferredAnchor(List<BlockDescriptor> cells) {
        for (int i = cells.size() - 1; i >= 0; i--) {
            String text = cells.get(i).text();
            if (text != null && !text.isBlank() && !isWeakToken(text)) {
                return cells.get(i).targetId();
            }
        }
        for (int i = cells.size() - 1; i >= 0; i--) {
            String text = cells.get(i).text();
            if (text != null && !text.isBlank()) {
                return cells.get(i).targetId();
            }
        }
        return cells.getFirst().targetId();
    }

    private static String rowSummary(List<BlockDescriptor> cells) {
        List<String> parts = new ArrayList<>();
        for (BlockDescriptor cell : cells) {
            String text = cell.text() == null ? "" : cell.text().trim();
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        return String.join(" | ", parts);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record ScoredRow(
            String key, List<BlockDescriptor> cells, int score, String anchorId, String summary) {
    }
}
