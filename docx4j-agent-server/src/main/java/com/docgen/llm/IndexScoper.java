package com.docgen.llm;

import com.docgen.model.BlockDescriptor;
import com.docgen.model.StructuralIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shrinks the block list sent to the LLM when the request implies a narrow scope. */
public final class IndexScoper {

    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]{2,80})['\"]");
    private static final Pattern TABLES_ONLY = Pattern.compile("(?i)\\bin\\s+the?\\s+tables?\\b");
    private static final int MIN_SHRINK = 8;
    private static final int MAX_SCOPED_BLOCKS = 120;

    private IndexScoper() {
    }

    public static StructuralIndex scopeForLlm(String request, StructuralIndex index) {
        if (request == null || index == null || index.blocks() == null) {
            return index;
        }
        List<String> terms = extractQuotedTerms(request);
        boolean tablesOnly = TABLES_ONLY.matcher(request).find();

        if (terms.isEmpty() && !tablesOnly) {
            return index;
        }

        Set<String> seen = new LinkedHashSet<>();
        List<BlockDescriptor> scoped = new ArrayList<>();

        for (BlockDescriptor block : index.blocks()) {
            if (tablesOnly && !"table_cell".equals(block.type())) {
                continue;
            }
            if (!terms.isEmpty()) {
                String text = block.text() == null ? "" : block.text();
                boolean matches = terms.stream().anyMatch(text::contains);
                if (!matches) {
                    continue;
                }
            }
            if (seen.add(block.targetId())) {
                scoped.add(block);
            }
        }

        if (scoped.isEmpty()) {
            return index;
        }
        if (!tablesOnly && terms.isEmpty()) {
            return index;
        }
        // Safety floor applies regardless of how many terms were quoted. A
        // multi-edit request often quotes the NEW text for one edit (which
        // by definition matches no block yet) alongside the OLD text for
        // another — e.g. "change the name to 'X' and change 'Y' to 'Z'".
        // With multiple terms this used to skip the floor entirely, so a
        // small document could scope down to just the one block matched by
        // the OLD-text term and silently drop the block the other edit
        // needs (the LLM then sees an incomplete index and wrongly declines
        // "no such block"). Requiring the floor unconditionally means small
        // or ambiguous scopes always fall back to the full index — correctness
        // over prompt-size savings.
        if (!tablesOnly && scoped.size() < MIN_SHRINK) {
            return index;
        }
        if (scoped.size() >= index.blocks().size()) {
            return index;
        }
        if (scoped.size() > MAX_SCOPED_BLOCKS) {
            return new StructuralIndex(index.documentId(), scoped.subList(0, MAX_SCOPED_BLOCKS));
        }
        return new StructuralIndex(index.documentId(), scoped);
    }

    static List<String> extractQuotedTerms(String request) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = QUOTED.matcher(request);
        while (matcher.find()) {
            String term = matcher.group(1).trim();
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms;
    }
}
