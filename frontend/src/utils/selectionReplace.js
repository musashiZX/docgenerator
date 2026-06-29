/**
 * Format-preserving batch replace for Syncfusion Document Editor.
 *
 * Public editor.insertText() always passes isReplace=false, which collapses
 * mixed character formatting (bold headings, highlights, etc.) when replacing
 * a selection.  Syncfusion's own search/replace UI uses insertTextInternal
 * with isReplace=true instead — we mirror that here.
 */

/** Normalise Syncfusion paragraph separators to Unix newlines. */
export function normalizeSelectionText(raw) {
  return (raw || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
}

/** Capture the current editor selection as plain text + hierarchical offsets. */
export function captureSelectionRange(documentEditor) {
  if (!documentEditor?.selection) {
    return { text: '', startOffset: null, endOffset: null };
  }
  const { selection } = documentEditor;
  return {
    text: normalizeSelectionText(selection.text || ''),
    startOffset: selection.startOffset || null,
    endOffset: selection.endOffset || null,
  };
}

function parseHierarchicalIndex(offset) {
  if (!offset) return [];
  return offset.split(';').map((part) => parseInt(part, 10) || 0);
}

/** Lexicographic compare of Syncfusion hierarchical indices (section;block;offset…). */
export function compareHierarchicalIndex(a, b) {
  const pa = parseHierarchicalIndex(a);
  const pb = parseHierarchicalIndex(b);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const va = pa[i] ?? 0;
    const vb = pb[i] ?? 0;
    if (va !== vb) return va - vb;
  }
  return 0;
}

/** True when [matchStart, matchEnd] lies fully inside [selStart, selEnd]. */
export function isWithinSelectionRange(matchStart, matchEnd, selStart, selEnd) {
  if (!matchStart || !matchEnd || !selStart || !selEnd) return false;
  return (
    compareHierarchicalIndex(matchStart, selStart) >= 0 &&
    compareHierarchicalIndex(matchEnd, selEnd) <= 0
  );
}

/** Word often stores regular spaces as NBSP — try both for search. */
function buildFindVariants(find) {
  const variants = [find];
  const nbsp = find.replace(/ /g, '\u00a0');
  if (nbsp !== find) variants.push(nbsp);
  const regular = find.replace(/\u00a0/g, ' ');
  if (regular !== find && !variants.includes(regular)) variants.push(regular);
  return variants;
}

function dedupeMatches(matches) {
  const seen = new Set();
  return matches.filter(({ startOffset, endOffset }) => {
    const key = `${startOffset}|${endOffset}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function filterScopedMatches(matches, selStart, selEnd, hasScope) {
  if (!hasScope) return matches;
  return matches.filter(({ startOffset, endOffset }) =>
    isWithinSelectionRange(startOffset, endOffset, selStart, selEnd),
  );
}

/**
 * Collect document matches via findAll + offset API and/or searchResults.index.
 */
function collectViaFindAll(documentEditor, needle) {
  const searchModule = documentEditor.searchModule;
  const results = searchModule.searchResults;
  const matches = [];

  searchModule.findAll(needle, 'None');

  if (typeof results.getTextSearchResultsOffset === 'function') {
    const offsets = results.getTextSearchResultsOffset() || [];
    for (const { startOffset, endOffset } of offsets) {
      matches.push({ startOffset, endOffset });
    }
  }

  if (!matches.length && results.length > 0) {
    for (let i = 0; i < results.length; i++) {
      results.index = i;
      const { selection } = documentEditor;
      if (selection.isEmpty && !selection.text) continue;
      matches.push({
        startOffset: selection.startOffset,
        endOffset: selection.endOffset,
      });
    }
  }

  results.clear();
  return matches;
}

/**
 * Walk forward with find() from a start cursor — reliable for list paragraphs
 * where findAll/getTextSearchResultsOffset sometimes returns nothing.
 */
function collectViaFindWalk(documentEditor, needle, selStart, selEnd, hasScope) {
  const searchModule = documentEditor.searchModule;
  const matches = [];
  const seen = new Set();
  const startCursor = hasScope ? selStart : documentEditor.selection.startOffset;

  documentEditor.selection.select(startCursor, startCursor);

  for (let guard = 0; guard < 500; guard++) {
    searchModule.find(needle, 'None');
    const { selection } = documentEditor;
    const hitText = selection.text || '';

    if ((!hitText || selection.isEmpty) && guard > 0) break;
    if (!hitText && guard === 0) break;

    const key = `${selection.startOffset}|${selection.endOffset}`;
    if (seen.has(key)) break;
    seen.add(key);

    if (hasScope && compareHierarchicalIndex(selection.startOffset, selEnd) > 0) {
      break;
    }

    if (
      !hasScope
      || isWithinSelectionRange(selection.startOffset, selection.endOffset, selStart, selEnd)
    ) {
      matches.push({
        startOffset: selection.startOffset,
        endOffset: selection.endOffset,
      });
    }

    documentEditor.selection.select(selection.endOffset, selection.endOffset);
  }

  searchModule.searchResults.clear();
  return matches;
}

/**
 * Find all in-scope occurrences of `find`, trying exact + NBSP variants.
 */
export function collectMatches(documentEditor, find, selStart, selEnd, hasScope) {
  const debug = { find, methods: [], inSelectionText: false };
  let allMatches = [];

  if (hasScope && selStart && selEnd) {
    documentEditor.selection.select(selStart, selEnd);
    const scopedText = normalizeSelectionText(documentEditor.selection.text || '');
    debug.inSelectionText = scopedText.includes(find)
      || buildFindVariants(find).some((v) => scopedText.includes(v));
  }

  for (const needle of buildFindVariants(find)) {
    const viaAll = collectViaFindAll(documentEditor, needle);
    debug.methods.push({ needle, findAll: viaAll.length });

    let scoped = filterScopedMatches(viaAll, selStart, selEnd, hasScope);
    if (scoped.length) {
      allMatches = dedupeMatches([...allMatches, ...scoped]);
      break;
    }

    if (hasScope && debug.inSelectionText) {
      const viaWalk = collectViaFindWalk(documentEditor, needle, selStart, selEnd, hasScope);
      debug.methods.push({ needle, findWalk: viaWalk.length });
      scoped = filterScopedMatches(viaWalk, selStart, selEnd, hasScope);
      if (scoped.length) {
        allMatches = dedupeMatches([...allMatches, ...scoped]);
        break;
      }
    }
  }

  debug.matchCount = allMatches.length;
  return { matches: allMatches, debug };
}

/**
 * Replace the exact range [start, end] while preserving character formatting.
 * Returns true when a replacement was applied.
 */
export function replaceRangePreservingFormat(documentEditor, start, end, replaceText) {
  const { selection, editorModule } = documentEditor;
  selection.select(start, end);
  if (selection.isEmpty) return false;
  // isReplace=true → same code path as Syncfusion's built-in Find & Replace.
  editorModule.insertTextInternal(replaceText ?? '', true);
  return true;
}

/**
 * Apply a list of { find, replace } edits scoped to a captured selection range.
 * Matches are collected first, then applied end→start so earlier offsets stay valid.
 */
export function applyBatchReplace(documentEditor, edits, selectionRange) {
  const { startOffset, endOffset, text: selectionText } = selectionRange || {};
  const hasScope = Boolean(startOffset && endOffset);

  if (!Array.isArray(edits) || edits.length === 0) {
    return { applied: 0, notFound: 0, skippedFinds: [], debug: [] };
  }

  const pending = [];
  const debug = [];

  for (const edit of edits) {
    if (edit.action !== 'replace_exact_text' || !edit.find) continue;

    const { matches, debug: searchDebug } = collectMatches(
      documentEditor,
      edit.find,
      startOffset,
      endOffset,
      hasScope,
    );

    debug.push({
      ...searchDebug,
      replace: edit.replace,
      inCapturedSelectionText: (selectionText || '').includes(edit.find),
    });

    if (!matches.length) {
      pending.push({ unmatched: true, find: edit.find });
      continue;
    }

    for (const { startOffset: matchStart, endOffset: matchEnd } of matches) {
      pending.push({
        start: matchStart,
        end: matchEnd,
        replace: edit.replace ?? '',
        find: edit.find,
      });
    }
  }

  const unmatchedFinds = pending.filter((p) => p.unmatched);
  const replacements = pending.filter((p) => !p.unmatched);

  replacements.sort((a, b) => compareHierarchicalIndex(b.start, a.start));

  const appliedFinds = new Set();
  let applied = 0;

  for (const { start, end, replace, find } of replacements) {
    try {
      if (replaceRangePreservingFormat(documentEditor, start, end, replace)) {
        applied++;
        appliedFinds.add(find);
      }
    } catch (err) {
      console.error('[batch-replace] error on find=%o', find, err);
    }
  }

  const skippedFinds = unmatchedFinds
    .filter((u) => !appliedFinds.has(u.find))
    .map((u) => u.find);

  return {
    applied,
    notFound: skippedFinds.length,
    skippedFinds,
    debug,
  };
}

/** Replace the first document occurrence of `find` (agent-mode mirror). */
export function applySingleReplace(documentEditor, find, replace) {
  if (!find) return false;
  const { matches } = collectMatches(documentEditor, find, null, null, false);
  if (!matches.length) return false;
  const { startOffset, endOffset } = matches[0];
  return replaceRangePreservingFormat(documentEditor, startOffset, endOffset, replace ?? '');
}
