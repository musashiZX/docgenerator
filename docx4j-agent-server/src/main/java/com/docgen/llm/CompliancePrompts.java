package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.FocusBlock;
import com.docgen.model.StructuralIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prompts for the mutation-proposing LLM call. */
public final class CompliancePrompts {

    private CompliancePrompts() {
    }

    public static final String SYSTEM = """
            You are a Word-document editing assistant. You NEVER edit documents \
            directly. You output a JSON mutation batch; a deterministic engine applies it.

            You receive the document as a list of blocks. Each block has:
            - target_id: its stable address (e.g. dg_p4, dg_tbl0_r1_c2)
            - type: "paragraph" (body paragraph) or "table_cell"
            - text: its EXACT current plain text

            HARD RULES
            1. Use ONLY target_ids from the provided list. Never invent ids.
            2. modify: old_text must be copied VERBATIM from that block's text \
            (character-for-character). Choose the SMALLEST substring that captures the \
            change (e.g. change `1.0`→`1.1`, not the whole `Version: 1.0` line). \
            Whole-line old_text on multi-run paragraphs destroys formatting. \
            If it appears more than once in the block, set occurrence (0-based). \
            new_text must differ from old_text.
            3. At most ONE modify or delete per target_id per batch.
            4. insert paragraph: anchor_id must be type "paragraph". node_type \
            "paragraph". Set text; set cells to null.
            5. insert table_row: clone an EXAMPLE row. anchor_id = any type=table_cell \
            in that example row. The engine deep-copies the whole row (merges, spans, \
            formatting). Set text and style to null. Set cells to the new texts for the \
            non-blank content cells only (e.g. ["Is this product Chinese food?", "Yes"]). \
            Do NOT pad with "" to match physical column count — that mis-maps into spacer \
            cells. Prefer (question, yes/no) for Q&A rows.
            6. insert table_column: ONLY for simple grid/dataframe tables (no merges, \
            same cell count every row). Prefer for building/extending grid tables. \
            anchor_id = a cell in the template column; cells = one string per row (or null).
            7. delete paragraph: node_type "paragraph", target_id a body paragraph.
            8. delete table_row: node_type "table_row"; target_id ANY cell in that row. \
            Works on merged/layout rows — removes the whole example-style row.
            9. delete table_column: node_type "table_column"; dataframe tables only; \
            target_id any cell in that column. Do not delete the last remaining column.
            10. Prefer minimal edits: change only what the user asked for.
            11. When USER FOCUSED THESE BLOCKS lists multiple target_ids, treat them as \
            the primary edit scope (but you may touch adjacent blocks if the recipe requires).
            12. Match the document's existing language unless told otherwise.
            13. You CAN combine modify, insert, and delete in ONE batch. Multiple \
            deletes and multiple inserts are allowed.
            14. Return an empty mutations array ONLY when the request is truly \
            impossible (e.g. editing a PDF, or no matching blocks). Do NOT refuse \
            table-row inserts on merged layout tables — clone the example row instead.
            15. For find-and-replace (e.g. change "Food Safety" to "Food Safe"): emit \
            a modify ONLY for blocks whose text actually contains the search string. \
            Copy old_text verbatim from THAT block's text — never reuse text from a \
            different block or truncate old_text.

            RECIPES (common patterns — use these instead of refusing)

            A) Replace a whole section with new multi-line content
               - Identify the paragraph ids to remove (type=paragraph).
               - Add one delete per removed paragraph (each needs its own target_id, \
            node_type "paragraph").
               - Pick the paragraph BEFORE the section (or AFTER, if clearer) as anchor.
               - Add one insert per new line, ALL with the same anchor_id and \
            position "after", listed consecutively in the batch. The engine chains \
            them into separate paragraphs automatically.
               - Put deletes BEFORE inserts. Do not anchor inserts on a paragraph \
            you delete in the same batch.

            B) Reword one paragraph
               - Single modify on that target_id with a verbatim old_text substring.

            C) Change bullets to numbered lines (or vice versa)
               - One modify per bullet paragraph (change the leading character/text).

            D) Add a comma / punctuation at end of many blocks
               - One modify per block; skip empty blocks.

            E) Add a table row relative to an existing row
               - Look at TABLE ROWS below (grouped cells). Find the ONE row whose \
            text the user named (match the distinctive question / label — not a \
            different Yes/No row in another table).
               - anchor_id = any cell id from THAT row. position = before|after as asked.
               - cells = new values for the non-blank content cells of that example \
            (or one string per physical cell, using "" for spacers). text=null, style=null.
               - Prefer cloning that example row's layout over inventing paragraphs.

            F) Add a table column (dataframe / grid tables only)
               - node_type "table_column"; cells has one string per row.

            G) Delete a table row or column
               - table_row: any cell in the row (works with merges).
               - table_column: dataframe only; any cell in the column.

            Output only the JSON object conforming to the schema.
            """;

    public static String userMessage(String request, StructuralIndex index,
                                     List<FocusBlock> focusBlocks, String legacySelectedText) {
        StringBuilder sb = new StringBuilder();
        List<FocusBlock> focused = normalizeFocusBlocks(focusBlocks, legacySelectedText);
        if (!focused.isEmpty()) {
            sb.append("USER FOCUSED THESE BLOCKS (prefer edits here when possible):\n");
            for (FocusBlock block : focused) {
                if (block.targetId() != null && !block.targetId().isBlank()) {
                    sb.append("- ").append(block.targetId()).append(": ");
                } else {
                    sb.append("- ");
                }
                sb.append(block.text() == null ? "" : block.text().trim()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("DOCUMENT BLOCKS (id | type | exact text):\n");
        for (BlockDescriptor block : index.blocks()) {
            sb.append(block.targetId())
                    .append(" | ")
                    .append(block.type())
                    .append(" | ")
                    .append(block.text() == null ? "" : block.text())
                    .append('\n');
        }
        appendTableRowSummaries(sb, index);
        sb.append("\nUSER REQUEST:\n").append(request.trim()).append('\n');
        return sb.toString();
    }

    /** Groups table cells into rows so the LLM can pick a whole-row anchor reliably. */
    static void appendTableRowSummaries(StringBuilder sb, StructuralIndex index) {
        Map<String, List<BlockDescriptor>> rows = new LinkedHashMap<>();
        for (BlockDescriptor block : index.blocks()) {
            if (!"table_cell".equals(block.type())
                    || block.tableIndex() == null
                    || block.row() == null) {
                continue;
            }
            String key = block.tableIndex() + ":" + block.row();
            rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(block);
        }
        if (rows.isEmpty()) {
            return;
        }
        sb.append("\nTABLE ROWS (use these when inserting/deleting a table_row — "
                + "anchor_id must be a cell id from the named row):\n");
        for (Map.Entry<String, List<BlockDescriptor>> entry : rows.entrySet()) {
            List<BlockDescriptor> cells = entry.getValue();
            cells.sort(Comparator.comparing(b -> b.col() == null ? 0 : b.col()));
            StringBuilder texts = new StringBuilder();
            String sampleId = cells.getFirst().targetId();
            for (BlockDescriptor cell : cells) {
                String text = cell.text() == null ? "" : cell.text().trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (!texts.isEmpty()) {
                    texts.append(" | ");
                }
                texts.append(text);
                sampleId = cell.targetId();
            }
            if (texts.isEmpty()) {
                continue;
            }
            String[] parts = entry.getKey().split(":");
            sb.append("tbl").append(parts[0]).append("/r").append(parts[1])
                    .append(" example_cell=").append(sampleId)
                    .append(" :: ").append(texts).append('\n');
        }
    }

    /** Backward-compatible single-excerpt form (tests / callers without block ids). */
    public static String userMessage(String request, StructuralIndex index, String selectedText) {
        return userMessage(request, index, List.of(), selectedText);
    }

    static List<FocusBlock> normalizeFocusBlocks(
            List<FocusBlock> focusBlocks, String legacySelectedText) {
        List<FocusBlock> result = new ArrayList<>();
        if (focusBlocks != null) {
            for (FocusBlock block : focusBlocks) {
                if (block == null) {
                    continue;
                }
                boolean hasId = block.targetId() != null && !block.targetId().isBlank();
                boolean hasText = block.text() != null && !block.text().isBlank();
                if (hasId || hasText) {
                    result.add(block);
                }
            }
        }
        if (result.isEmpty() && legacySelectedText != null && !legacySelectedText.isBlank()) {
            result.add(new FocusBlock(null, legacySelectedText.trim()));
        }
        return result;
    }

    public static String retryMessage(String validationErrors) {
        return """
                Your previous batch failed server-side validation. Fix ONLY the listed \
                problems and return the corrected full batch. Remember: old_text must be \
                copied verbatim from the block text you were given. For OLD_TEXT_NOT_FOUND, \
                either fix old_text to match that block's actual text, or REMOVE that \
                mutation if the block does not contain the string you are replacing.

                VALIDATION ERRORS:
                """ + validationErrors;
    }

    public static String emptyBatchRetryMessage(String explanation) {
        return """
                You returned no mutations. The engine CAN apply complex edits in one \
                batch (multiple deletes + consecutive "after" inserts on the same anchor \
                for multiple new lines). Re-read the RECIPES in the system prompt and \
                try again. Only refuse if truly impossible.

                Your previous explanation was: """
                + (explanation == null || explanation.isBlank() ? "(none)" : explanation);
    }
}
