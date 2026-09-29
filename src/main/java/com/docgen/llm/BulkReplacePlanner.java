package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.ModifyMutation;
import com.docgen.model.Mutation;
import com.docgen.model.MutationBatch;
import com.docgen.model.StructuralIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects simple find-and-replace requests and builds mutations deterministically
 * from the structural index — no LLM round-trip.
 */
public final class BulkReplacePlanner {

    private static final Pattern CHANGE_TO = Pattern.compile(
            "(?is)change\\s+(?:all\\s+)?(?:the\\s+)?['\"]([^'\"]+)['\"]\\s*(?:in\\s+the\\s+tables?\\s*)?to\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern REPLACE_WITH = Pattern.compile(
            "(?is)replace\\s+(?:all\\s+)?(?:the\\s+)?['\"]([^'\"]+)['\"]\\s*(?:in\\s+the\\s+tables?\\s*)?with\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern TABLES_ONLY = Pattern.compile("(?i)\\bin\\s+the?\\s+tables?\\b");

    private BulkReplacePlanner() {
    }

    public record ReplaceSpec(String search, String replace, boolean tablesOnly) {
    }

    public static Optional<ReplaceSpec> parse(String request) {
        if (request == null || request.isBlank()) {
            return Optional.empty();
        }
        Matcher change = CHANGE_TO.matcher(request.trim());
        if (change.find()) {
            boolean tablesOnly = TABLES_ONLY.matcher(request).find();
            return Optional.of(new ReplaceSpec(change.group(1), change.group(2), tablesOnly));
        }
        Matcher replace = REPLACE_WITH.matcher(request.trim());
        if (replace.find()) {
            boolean tablesOnly = TABLES_ONLY.matcher(request).find();
            return Optional.of(new ReplaceSpec(replace.group(1), replace.group(2), tablesOnly));
        }
        return Optional.empty();
    }

    public static Optional<MutationBatch> tryPlan(String request, StructuralIndex index) {
        return parse(request).map(spec -> plan(spec, index));
    }

    public static MutationBatch plan(ReplaceSpec spec, StructuralIndex index) {
        List<Mutation> mutations = new ArrayList<>();
        for (BlockDescriptor block : index.blocks()) {
            if (spec.tablesOnly() && !"table_cell".equals(block.type())) {
                continue;
            }
            String text = block.text();
            if (text == null || text.isEmpty() || !text.contains(spec.search())) {
                continue;
            }
            if (spec.search().equals(spec.replace())) {
                continue;
            }
            int occurrence = 0;
            if (countOccurrences(text, spec.search()) <= occurrence) {
                continue;
            }
            mutations.add(new ModifyMutation(
                    "modify",
                    block.targetId(),
                    spec.search(),
                    occurrence,
                    spec.replace()));
        }
        String scope = spec.tablesOnly() ? "table cells" : "document";
        String explanation = "Replace \"" + spec.search() + "\" with \""
                + spec.replace() + "\" in " + scope + " (" + mutations.size() + " block(s)).";
        return new MutationBatch(1, explanation, mutations);
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
