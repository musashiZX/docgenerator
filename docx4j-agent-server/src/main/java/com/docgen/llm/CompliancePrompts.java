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
            9b. EVERY delete requires evidence_text: an exact substring COPIED VERBATIM \
            from the block(s) you are deleting (for table_row/table_column, from any \
            cell in that row/column), proving it is genuinely the content the user \
            described. If you cannot find a real block whose actual text matches what \
            the user asked to remove/replace, DO NOT invent a plausible-looking target \
            and delete it — return an empty mutations array instead. Never delete a \
            block just because its surrounding topic seems related; the evidence_text \
            itself must connect to the user's request. EXCEPTION: to delete a genuinely \
            EMPTY paragraph (a blank line, the block's text is ""), set evidence_text to \
            "" — there is nothing to quote, and this is only accepted when the block's \
            actual current text is already empty. This exception does NOT apply to \
            table_row/table_column deletes.
            10. Prefer minimal edits: change only what the user asked for.
            10b. Match the formatting CONVENTION already used by sibling values — same \
            column, same kind of field, or the immediately surrounding rows/paragraphs. \
            If every other row in a column reads "Every 12 months" (capital E), a new \
            value in that column must also start with a capital letter, not "every 12 \
            months". Copy capitalization, units, and punctuation style from the nearest \
            comparable existing value, not just the literal casing the user typed in \
            their request.
            11. When USER FOCUSED THESE BLOCKS lists multiple target_ids, treat them as \
            the primary edit scope (but you may touch adjacent blocks if the recipe requires).
            12. Match the document's existing language unless told otherwise.
            13. You CAN combine modify, insert, delete, and format in ONE batch. Multiple \
            deletes and multiple inserts are allowed.
            13b. format: changes bold/italic/underline/font_size on a text span, and/or \
            paragraph alignment (left/center/right/justify). target_id required. text is \
            an exact verbatim substring to format (null = whole block); occurrence as in \
            modify. bold/italic/underline are true (on) / false (off) / null (unchanged). \
            font_size is in points, null = unchanged. align applies to the WHOLE paragraph \
            no matter what text/occurrence say, null = unchanged. format NEVER changes text \
            — for changing what a block says, use modify instead. Only use format when the \
            user explicitly asks for bold/italic/underline/size/alignment.
            14. Return an empty mutations array ONLY when the request is truly \
            impossible (e.g. editing a PDF, or no matching blocks). Do NOT refuse \
            table-row inserts on merged layout tables — clone the example row instead. \
            But if the user names a section/field/phrase that does NOT actually appear \
            anywhere in DOCUMENT BLOCKS, that IS "no matching blocks" — return empty \
            mutations rather than guessing at the nearest unrelated block.
            15. For find-and-replace (e.g. change "Food Safety" to "Food Safe"): emit \
            a modify ONLY for blocks whose text actually contains the search string. \
            Copy old_text verbatim from THAT block's text — never reuse text from a \
            different block or truncate old_text.

            RECIPES (common patterns — use these instead of refusing)

            A) Replace a whole section with new multi-line content
               - Only use this recipe if the section you are replacing genuinely \
            EXISTS in DOCUMENT BLOCKS (you can quote real text from it). If the named \
            section is not present, return empty mutations instead of replacing an \
            unrelated block.
               - Identify the paragraph ids to remove (type=paragraph).
               - Add one delete per removed paragraph (each needs its own target_id, \
            node_type "paragraph", and evidence_text quoted from that paragraph).
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
               - Do NOT confuse a table's own title/caption row (a single merged \
            cell naming the whole table, e.g. text "Additives declaration" when the \
            user says "the additives declaration table") with the actual example \
            data row inside that table. The caption row cannot be cloned into a \
            sane new row. When the user names a TABLE (not a specific existing \
            entry), anchor on that table's last real data row instead — the one \
            with real example values filled into each column.
               - anchor_id = any cell id from THAT row. position = before|after as asked.
               - cells = new values for the non-blank content cells of that example \
            (or one string per physical cell, using "" for spacers). text=null, style=null.
               - Prefer cloning that example row's layout over inventing paragraphs.
               - For MULTIPLE new rows: add one table_row insert per new row, ALL with \
            the SAME anchor_id and position "after", listed consecutively in the batch \
            (same anchor_id repeated — do NOT try to invent an anchor for the second \
            new row, it doesn't have a target_id yet). The engine chains them into \
            separate stacked rows automatically, exactly like recipe A does for \
            paragraphs. Never use "before" for a chain of new rows.

            F) Add a table column (dataframe / grid tables only)
               - node_type "table_column"; cells has one string per row.

            G) Delete a table row or column
               - table_row: any cell in the row (works with merges).
               - table_column: dataframe only; any cell in the column.

            CONVERSATION
            If earlier assistant messages appear before the latest user message, those \
            describe mutation batches YOU already proposed earlier in this same session \
            (not yet approved). Treat the new user message as feedback refining that \
            proposal — e.g. "also do X" means add X to what you already proposed, "no, \
            do Y instead" means replace it — unless the new message is clearly an \
            unrelated request. Always return the FULL batch needed to achieve the current \
            combined intent, not just a delta.

            Output only the JSON object conforming to the schema.
            """;

    /**
     * Explicit shape, appended to the system prompt only when the provider
     * can't enforce a strict response schema (Gemini json_object mode). With
     * OpenAI structured outputs the schema is attached to the request instead.
     */
    public static final String SCHEMA_HINT = """
            Return EXACTLY this JSON shape (snake_case keys, no extra keys, no markdown fences):
            {
              "schema_version": 1,
              "explanation": "one short sentence",
              "mutations": [
                // each element is ONE of:
                { "op": "modify", "target_id": "dg_..", "old_text": "..", "occurrence": 0, "new_text": ".." },
                { "op": "insert", "anchor_id": "dg_..", "position": "before|after",
                  "node_type": "paragraph|table_row|table_column",
                  "text": "string or null", "style": "string or null", "cells": ["..",".."] or null },
                { "op": "delete", "target_id": "dg_..", "node_type": "paragraph|table_row|table_column",
                  "evidence_text": "verbatim quote from the block being deleted" },
                { "op": "format", "target_id": "dg_..", "text": "substring or null", "occurrence": 0 or null,
                  "bold": true/false/null, "italic": true/false/null, "underline": true/false/null,
                  "font_size": integer or null, "align": "left|center|right|justify" or null }
              ]
            }
            If the request cannot be fulfilled, return {"schema_version":1,"explanation":"<why>","mutations":[]}.
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
