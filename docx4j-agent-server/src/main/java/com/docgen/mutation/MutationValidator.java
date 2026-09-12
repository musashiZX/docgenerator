package com.docgen.mutation;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.DeleteMutation;
import com.docgen.model.FormatMutation;
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

        Set<String> seenTargets = new HashSet<>();
        Set<String> deletedTargets = new HashSet<>();
        Set<String> seenAnchors = new HashSet<>();
        Set<String> seenTableRowOps = new HashSet<>();
        Set<String> seenTableColOps = new HashSet<>();

        for (int i = 0; i < batch.mutations().size(); i++) {
            Mutation mutation = batch.mutations().get(i);
            switch (mutation) {
                case ModifyMutation modify -> validateModify(i, modify, blocks, seenTargets, errors);
                case InsertMutation insert ->
                        validateInsert(i, insert, batch.mutations(), blocks, seenAnchors,
                                deletedTargets, seenTableRowOps, seenTableColOps, errors);
                case DeleteMutation delete ->
                        validateDelete(i, delete, blocks, seenTargets, deletedTargets,
                                seenTableRowOps, seenTableColOps, errors);
                case FormatMutation format -> validateFormat(i, format, blocks, errors);
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
        if (modify.oldText() == null) {
            errors.add(new ValidationError(i, "MISSING_OLD_TEXT", "old_text is required"));
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
        String blockText = blocks.get(targetId).text();
        // old_text="" is a legal special case: it asserts "this block is
        // currently empty" (there's nothing to find/lock onto otherwise) —
        // used to type new content into an empty paragraph/cell. occurrence
        // is meaningless here since there's no substring search.
        if (modify.oldText().isEmpty()) {
            if (blockText != null && !blockText.isEmpty()) {
                errors.add(new ValidationError(i, "OLD_TEXT_NOT_FOUND",
                        "old_text is empty (asserts " + targetId + " is currently empty) but it actually contains: \""
                                + abbreviate(blockText) + "\""));
            }
            return;
        }
        int occurrence = modify.occurrenceOrDefault();
        if (occurrence < 0) {
            errors.add(new ValidationError(i, "BAD_OCCURRENCE", "occurrence must be >= 0"));
            return;
        }
        if (countOccurrences(blockText, modify.oldText()) <= occurrence) {
            errors.add(new ValidationError(i, "OLD_TEXT_NOT_FOUND",
                    "old_text (occurrence " + occurrence + ") not found in " + targetId
                            + ": \"" + abbreviate(modify.oldText()) + "\""
                            + ". Actual block text: \"" + abbreviate(blockText) + "\""));
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 117) + "...";
    }

    private static void validateInsert(
            int i,
            InsertMutation insert,
            List<Mutation> mutations,
            Map<String, BlockDescriptor> blocks,
            Set<String> seenAnchors,
            Set<String> deletedTargets,
            Set<String> seenTableRowOps,
            Set<String> seenTableColOps,
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
        if (!"before".equals(insert.position()) && !"after".equals(insert.position())) {
            errors.add(new ValidationError(i, "BAD_POSITION",
                    "position must be \"before\" or \"after\", got: " + insert.position()));
            return;
        }
        if (deletedTargets.contains(anchorId)) {
            errors.add(new ValidationError(i, "ANCHOR_DELETED",
                    "Cannot anchor an insert on a block deleted in the same batch: " + anchorId));
            return;
        }

        if (insert.isTableRow() || insert.isTableColumn()) {
            if (!"table_cell".equals(anchor.type())) {
                errors.add(new ValidationError(i, "UNSUPPORTED_ANCHOR",
                        insert.nodeType() + " insert requires a table_cell anchor, got "
                                + anchor.type() + ": " + anchorId));
                return;
            }
            Integer tableIndex = anchor.tableIndex();
            Integer row = anchor.row();
            Integer col = anchor.col();
            if (tableIndex == null || row == null || col == null) {
                errors.add(new ValidationError(i, "BAD_TABLE_ANCHOR",
                        "table cell anchor is missing table/row/col metadata: " + anchorId));
                return;
            }
            String structuralKey = insert.isTableRow()
                    ? "row:" + tableIndex + ":" + row
                    : "col:" + tableIndex + ":" + col;
            Set<String> seen = insert.isTableRow() ? seenTableRowOps : seenTableColOps;
            // Consecutive "after" row inserts on the SAME anchor are how you
            // add several new rows in one batch — the applier chains them
            // (each after the first lands on the row the previous one just
            // created), mirroring the identical exemption for paragraph
            // inserts below. Only rows chain this way; columns don't need it
            // (a column insert only ever needs to happen once per batch).
            boolean chainedAfterRowInsert = insert.isTableRow() && "after".equals(insert.position())
                    && i > 0 && mutations.get(i - 1) instanceof InsertMutation prevInsert
                    && prevInsert.isTableRow()
                    && anchorId.equals(prevInsert.anchorId())
                    && "after".equals(prevInsert.position());
            if (!chainedAfterRowInsert && !seen.add(structuralKey + "#" + insert.position())) {
                errors.add(new ValidationError(i, "DUPLICATE_TABLE_OP",
                        "At most one " + insert.nodeType() + " insert per "
                                + (insert.isTableRow() ? "row" : "column")
                                + "+position per batch for " + anchorId
                                + (insert.isTableRow()
                                        ? " (use consecutive \"after\" inserts on the same anchor to add multiple rows)"
                                        : "")));
            }
            if (insert.cells() != null) {
                int physical = expectedCellCount(blocks, insert);
                int content = expectedContentCellCount(blocks, insert);
                int provided = countNonBlank(insert.cells());
                if (physical > 0
                        && insert.cells().size() != physical
                        && (content <= 0 || insert.cells().size() != content)
                        && (content <= 0 || provided != content)) {
                    errors.add(new ValidationError(i, "BAD_CELLS_LENGTH",
                            "cells length " + insert.cells().size()
                                    + " must equal physical cells (" + physical + ")"
                                    + " or non-blank content cells (" + content + ")"
                                    + " for " + insert.nodeType() + " at " + anchorId));
                }
            }
            return;
        }

        if (!insert.isParagraph()) {
            errors.add(new ValidationError(i, "UNSUPPORTED_NODE_TYPE",
                    "node_type must be \"paragraph\", \"table_row\", or \"table_column\", got: "
                            + insert.nodeType()));
            return;
        }
        if (!"paragraph".equals(anchor.type())) {
            errors.add(new ValidationError(i, "UNSUPPORTED_ANCHOR",
                    "Paragraph insert anchors must be body paragraphs, not "
                            + anchor.type() + ": " + anchorId));
            return;
        }
        if (insert.text() == null) {
            errors.add(new ValidationError(i, "MISSING_TEXT", "text is required for paragraph insert"));
            return;
        }
        String anchorKey = anchorId + "#" + insert.position();
        if ("after".equals(insert.position()) && i > 0) {
            Mutation prev = mutations.get(i - 1);
            if (prev instanceof InsertMutation prevInsert
                    && prevInsert.isParagraph()
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

    private static int expectedCellCount(Map<String, BlockDescriptor> blocks, InsertMutation insert) {
        BlockDescriptor anchor = blocks.get(insert.anchorId());
        if (anchor == null || anchor.tableIndex() == null) {
            return -1;
        }
        int tableIndex = anchor.tableIndex();
        if (insert.isTableRow()) {
            Integer row = anchor.row();
            if (row == null) {
                return -1;
            }
            int cols = 0;
            for (BlockDescriptor block : blocks.values()) {
                if ("table_cell".equals(block.type())
                        && Integer.valueOf(tableIndex).equals(block.tableIndex())
                        && Integer.valueOf(row).equals(block.row())) {
                    cols++;
                }
            }
            return cols;
        }
        if (insert.isTableColumn()) {
            Integer col = anchor.col();
            if (col == null) {
                return -1;
            }
            int rows = 0;
            for (BlockDescriptor block : blocks.values()) {
                if ("table_cell".equals(block.type())
                        && Integer.valueOf(tableIndex).equals(block.tableIndex())
                        && Integer.valueOf(col).equals(block.col())) {
                    rows++;
                }
            }
            return rows;
        }
        return -1;
    }

    /** Non-blank cells in the template row (example content slots). */
    private static int expectedContentCellCount(
            Map<String, BlockDescriptor> blocks, InsertMutation insert) {
        if (!insert.isTableRow()) {
            return -1;
        }
        BlockDescriptor anchor = blocks.get(insert.anchorId());
        if (anchor == null || anchor.tableIndex() == null || anchor.row() == null) {
            return -1;
        }
        int count = 0;
        for (BlockDescriptor block : blocks.values()) {
            if ("table_cell".equals(block.type())
                    && Integer.valueOf(anchor.tableIndex()).equals(block.tableIndex())
                    && Integer.valueOf(anchor.row()).equals(block.row())
                    && block.text() != null
                    && !block.text().isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static int countNonBlank(List<String> cells) {
        int count = 0;
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static void validateDelete(
            int i,
            DeleteMutation delete,
            Map<String, BlockDescriptor> blocks,
            Set<String> seenTargets,
            Set<String> deletedTargets,
            Set<String> seenTableRowOps,
            Set<String> seenTableColOps,
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
        boolean evidenceMissing = delete.evidenceText() == null || delete.evidenceText().isBlank();
        // An empty paragraph has no text to quote as proof — verified against
        // the block's OWN indexed text (never just trusting the caller), so
        // this can't be used to skip evidence on a block that actually has
        // content. Table row/column deletes always span multiple cells, some
        // of which may be non-empty, so this exception is paragraph-only.
        boolean targetIsVerifiablyEmptyParagraph = "paragraph".equals(block.type())
                && !(delete.isTableRow() || delete.isTableColumn())
                && (block.text() == null || block.text().isBlank());
        if (evidenceMissing && !targetIsVerifiablyEmptyParagraph) {
            errors.add(new ValidationError(i, "MISSING_EVIDENCE_TEXT",
                    "evidence_text is required and must be a verbatim quote from the block(s) being deleted: "
                            + targetId));
            return;
        }

        if (delete.isTableRow() || delete.isTableColumn()) {
            if (!"table_cell".equals(block.type())) {
                errors.add(new ValidationError(i, "UNSUPPORTED_DELETE",
                        delete.nodeType() + " delete requires a table_cell target, got "
                                + block.type() + ": " + targetId));
                return;
            }
            Integer tableIndex = block.tableIndex();
            Integer row = block.row();
            Integer col = block.col();
            if (tableIndex == null || row == null || col == null) {
                errors.add(new ValidationError(i, "BAD_TABLE_TARGET",
                        "table cell target is missing table/row/col metadata: " + targetId));
                return;
            }
            String structuralKey = delete.isTableRow()
                    ? "del-row:" + tableIndex + ":" + row
                    : "del-col:" + tableIndex + ":" + col;
            Set<String> seen = delete.isTableRow() ? seenTableRowOps : seenTableColOps;
            if (!seen.add(structuralKey)) {
                errors.add(new ValidationError(i, "DUPLICATE_TABLE_OP",
                        "At most one " + delete.nodeType() + " delete per "
                                + (delete.isTableRow() ? "row" : "column")
                                + " per batch for " + targetId));
                return;
            }
            // Mark all cells in that row/column as deleted so modify/insert cannot target them.
            boolean evidenceFound = false;
            for (BlockDescriptor candidate : blocks.values()) {
                if (!"table_cell".equals(candidate.type())
                        || !Integer.valueOf(tableIndex).equals(candidate.tableIndex())) {
                    continue;
                }
                boolean match = delete.isTableRow()
                        ? Integer.valueOf(row).equals(candidate.row())
                        : Integer.valueOf(col).equals(candidate.col());
                if (match) {
                    deletedTargets.add(candidate.targetId());
                    seenTargets.add(candidate.targetId());
                    String cellText = candidate.text();
                    if (cellText != null && cellText.contains(delete.evidenceText())) {
                        evidenceFound = true;
                    }
                }
            }
            if (!evidenceFound) {
                errors.add(new ValidationError(i, "EVIDENCE_TEXT_NOT_FOUND",
                        "evidence_text \"" + abbreviate(delete.evidenceText())
                                + "\" was not found in any cell of the " + (delete.isTableRow() ? "row" : "column")
                                + " being deleted (" + targetId + "). Refusing to delete unrelated content."));
            }
            return;
        }

        if (!delete.isParagraph()) {
            errors.add(new ValidationError(i, "UNSUPPORTED_NODE_TYPE",
                    "delete node_type must be \"paragraph\", \"table_row\", or \"table_column\", got: "
                            + delete.nodeType()));
            return;
        }
        if (!"paragraph".equals(block.type())) {
            errors.add(new ValidationError(i, "UNSUPPORTED_DELETE",
                    "Only body paragraphs can be deleted without node_type table_row/table_column, not "
                            + block.type() + ": " + targetId));
            return;
        }
        if (!seenTargets.add(targetId)) {
            errors.add(new ValidationError(i, "DUPLICATE_TARGET",
                    "At most one mutation per target_id per batch: " + targetId));
            return;
        }
        if (evidenceMissing) {
            // Reaching here without erroring already proved the block is
            // genuinely empty (targetIsVerifiablyEmptyParagraph) — nothing to
            // match against, and nothing to accidentally delete unrelated
            // content from.
            deletedTargets.add(targetId);
            return;
        }
        String blockText = block.text();
        if (blockText == null || !blockText.contains(delete.evidenceText())) {
            errors.add(new ValidationError(i, "EVIDENCE_TEXT_NOT_FOUND",
                    "evidence_text \"" + abbreviate(delete.evidenceText()) + "\" not found in " + targetId
                            + ". Actual block text: \"" + abbreviate(blockText) + "\". "
                            + "Refusing to delete unrelated content."));
            return;
        }
        deletedTargets.add(targetId);
    }

    private static void validateFormat(
            int i,
            FormatMutation format,
            Map<String, BlockDescriptor> blocks,
            List<ValidationError> errors) {

        String targetId = format.targetId();
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
        if (!format.hasRunFormatting() && !format.hasAlignment()) {
            errors.add(new ValidationError(i, "EMPTY_FORMAT",
                    "format mutation must set at least one of bold/italic/underline/font_size/align"));
            return;
        }
        if (format.hasAlignment()) {
            String a = format.align().toLowerCase();
            if (!a.equals("left") && !a.equals("center") && !a.equals("centre")
                    && !a.equals("right") && !a.equals("justify") && !a.equals("both")) {
                errors.add(new ValidationError(i, "BAD_ALIGN",
                        "align must be left|center|right|justify, got: " + format.align()));
                return;
            }
        }
        if (format.hasRunFormatting()) {
            if (format.fontSize() != null && (format.fontSize() < 1 || format.fontSize() > 400)) {
                errors.add(new ValidationError(i, "BAD_FONT_SIZE",
                        "font_size must be between 1 and 400 points, got: " + format.fontSize()));
                return;
            }
            int occurrence = format.occurrenceOrDefault();
            if (occurrence < 0) {
                errors.add(new ValidationError(i, "BAD_OCCURRENCE", "occurrence must be >= 0"));
                return;
            }
            if (format.text() != null && !format.text().isEmpty()) {
                String blockText = block.text();
                if (countOccurrences(blockText, format.text()) <= occurrence) {
                    errors.add(new ValidationError(i, "TEXT_NOT_FOUND",
                            "text (occurrence " + occurrence + ") not found in " + targetId
                                    + ": \"" + abbreviate(format.text()) + "\""
                                    + ". Actual block text: \"" + abbreviate(blockText) + "\""));
                }
            }
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
