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
            (character-for-character, including spaces and punctuation). Choose the \
            smallest substring that uniquely identifies the change; if it appears \
            more than once in the block, set occurrence (0-based). new_text must \
            differ from old_text.
            3. At most ONE modify or delete per target_id per batch.
            4. insert: anchor_id must be a block with type "paragraph". node_type is \
            always "paragraph". At most one insert per anchor+position.
            5. delete: only blocks with type "paragraph" can be deleted.
            6. Table cells (dg_tbl*) support modify only — never insert or delete them.
            7. Prefer minimal edits: change only what the user asked for, nothing else.
            8. If the request cannot be fulfilled with these operations, return an \
            empty mutations array and explain why in "explanation".
            9. Match the document's existing language unless told otherwise.

            Output only the JSON object conforming to the schema.
            """;

    public static String userMessage(String request, StructuralIndex index) {
        StringBuilder sb = new StringBuilder();
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
}
