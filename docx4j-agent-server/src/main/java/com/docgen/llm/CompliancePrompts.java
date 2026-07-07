package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.StructuralIndex;

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
            (character-for-character). Choose the smallest substring that uniquely \
            identifies the change; if it appears more than once in the block, set \
            occurrence (0-based). new_text must differ from old_text.
            3. At most ONE modify or delete per target_id per batch.
            4. insert: anchor_id must be a block with type "paragraph". node_type is \
            always "paragraph".
            5. delete: only blocks with type "paragraph" can be deleted.
            6. Table cells (dg_tbl*) support modify only — never insert or delete them.
            7. Prefer minimal edits: change only what the user asked for.
            8. Match the document's existing language unless told otherwise.
            9. You CAN combine modify, insert, and delete in ONE batch. Multiple \
            deletes and multiple inserts are allowed.
            10. Return an empty mutations array ONLY when the request is truly \
            impossible (e.g. table-row insert/delete, editing a PDF, or no matching \
            blocks). Do NOT refuse because the edit is "complex" or needs many steps.

            RECIPES (common patterns — use these instead of refusing)

            A) Replace a whole section with new multi-line content
               - Identify the paragraph ids to remove (type=paragraph).
               - Add one delete per removed paragraph (each needs its own target_id).
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

            Output only the JSON object conforming to the schema.
            """;

    public static String userMessage(String request, StructuralIndex index, String selectedText) {
        StringBuilder sb = new StringBuilder();
        if (selectedText != null && !selectedText.isBlank()) {
            sb.append("USER HIGHLIGHTED THIS EXCERPT (focus edits here when possible):\n");
            sb.append("---\n").append(selectedText.trim()).append("\n---\n\n");
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
        sb.append("\nUSER REQUEST:\n").append(request.trim()).append('\n');
        return sb.toString();
    }

    public static String retryMessage(String validationErrors) {
        return """
                Your previous batch failed server-side validation. Fix ONLY the listed \
                problems and return the corrected full batch. Remember: old_text must be \
                copied verbatim from the block text you were given.

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
