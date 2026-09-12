import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>");
global.document = dom.window.document;
global.Node = dom.window.Node;

// Exact copies of the functions from app.js (kept in sync manually — this
// is a standalone sanity test, not part of the build).
function htmlToRuns(html) {
  const container = document.createElement("div");
  container.innerHTML = html;
  const root = container.querySelector("p") || container;
  const align = (root.style && root.style.textAlign) || "left";

  const runs = [];
  function walk(node, fmt) {
    if (node.nodeType === Node.TEXT_NODE) {
      if (node.nodeValue) runs.push({ text: node.nodeValue, ...fmt });
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const tag = node.tagName.toLowerCase();
    if (tag === "br") {
      runs.push({ text: "\n", ...fmt });
      return;
    }
    const next = { ...fmt };
    if (tag === "strong" || tag === "b") next.bold = true;
    if (tag === "em" || tag === "i") next.italic = true;
    if (tag === "u") next.underline = true;
    if (node.style && node.style.fontSize) next.fontSize = node.style.fontSize;
    node.childNodes.forEach((child) => walk(child, next));
  }
  root.childNodes.forEach((child) =>
    walk(child, { bold: false, italic: false, underline: false, fontSize: null }));

  const merged = [];
  for (const r of runs) {
    const last = merged[merged.length - 1];
    if (last && last.bold === r.bold && last.italic === r.italic
        && last.underline === r.underline && last.fontSize === r.fontSize) {
      last.text += r.text;
    } else {
      merged.push({ ...r });
    }
  }
  return { fullText: merged.map((r) => r.text).join(""), runs: merged, align };
}

function buildFormatMutations(targetId, fullText, runs, align) {
  const mutations = [];
  let cursor = 0;
  for (const r of runs) {
    const hasFormatting = r.bold || r.italic || r.underline || r.fontSize;
    if (r.text.length > 0 && (hasFormatting || /\S/.test(r.text))) {
      let count = 0;
      let from = 0;
      while (true) {
        const at = fullText.indexOf(r.text, from);
        if (at < 0 || at >= cursor) break;
        count++;
        from = at + 1;
      }
      mutations.push({
        op: "format", target_id: targetId, text: r.text, occurrence: count,
        bold: r.bold, italic: r.italic, underline: r.underline,
        font_size: r.fontSize ? parseInt(r.fontSize, 10) : null, align: null,
      });
    }
    cursor += r.text.length;
  }
  mutations.push({
    op: "format", target_id: targetId, text: null, occurrence: 0,
    bold: null, italic: null, underline: null, font_size: null, align,
  });
  return mutations;
}

// Test-only helper composing the two, mirroring what saveBlockEditor does
// end to end (for assertions that care about the combined result).
function fullBatch(targetId, originalText, html) {
  const { fullText, runs, align } = htmlToRuns(html);
  const mutations = [];
  if (fullText !== originalText) {
    mutations.push({ op: "modify", target_id: targetId, old_text: originalText, occurrence: 0, new_text: fullText });
  }
  mutations.push(...buildFormatMutations(targetId, fullText, runs, align));
  return mutations;
}

// ---- tests ----
let failures = 0;
function check(name, cond, detail) {
  if (!cond) {
    failures++;
    console.log(`FAIL: ${name}` + (detail ? ` -- ${detail}` : ""));
  } else {
    console.log(`ok:   ${name}`);
  }
}

// 1. Plain text, no formatting, unchanged: still asserts bold/italic/
//    underline=false explicitly (not omitted) — required for "un-bolding
//    previously-bold text" (test 3b) to actually take effect server-side.
{
  const mutations = fullBatch("dg_p1", "Hello world", "<p>Hello world</p>");
  const textFmt = mutations.find((m) => m.op === "format" && m.text === "Hello world");
  check("no modify when text unchanged", !mutations.some((m) => m.op === "modify"));
  check("plain run still asserts false explicitly (not just omitted)",
    textFmt && textFmt.bold === false && textFmt.italic === false && textFmt.underline === false,
    JSON.stringify(textFmt));
}

// 2. Text changed, no formatting.
{
  const mutations = fullBatch("dg_p1", "Hello world", "<p>Hello there</p>");
  const modify = mutations.find((m) => m.op === "modify");
  check("modify emitted when text changes", !!modify && modify.new_text === "Hello there");
}

// 3a. Bold substring — matches the real "Document Title:" case from this
// session: expect TWO text-bearing runs (bold "Document", plain rest), each
// asserting its own state explicitly.
{
  const mutations = fullBatch("dg_p1", "Document Title: Hygiene", "<p><strong>Document</strong> Title: Hygiene</p>");
  const fmts = mutations.filter((m) => m.op === "format" && m.text);
  check("two text-bearing format mutations (bold run + plain rest)", fmts.length === 2, JSON.stringify(fmts));
  check("first run is bold 'Document'", fmts[0] && fmts[0].text === "Document" && fmts[0].bold === true);
  check("second run is plain ' Title: Hygiene'",
    fmts[1] && fmts[1].text === " Title: Hygiene" && fmts[1].bold === false);
}

// 3b. Un-bolding: the run is no longer wrapped in <strong> — must produce
// an explicit bold:false, not silence (silence would leave pre-existing
// bold formatting untouched server-side).
{
  const mutations = fullBatch("dg_p1", "Document Title: Hygiene", "<p>Document Title: Hygiene</p>");
  const fmt = mutations.find((m) => m.op === "format" && m.text === "Document Title: Hygiene");
  check("un-bold asserted explicitly for the whole (now-uniform) run", fmt && fmt.bold === false, JSON.stringify(fmt));
}

// 4. Occurrence correctness: same word appears twice, only the SECOND is bold.
{
  const original = "cat and cat but only the second cat is bold";
  const html = "<p>cat and cat but only the second <strong>cat</strong> is bold</p>";
  const mutations = fullBatch("dg_p1", original, html);
  const catFmt = mutations.find((m) => m.op === "format" && m.text === "cat" && m.bold);
  check("bold 'cat' targets occurrence 2 (0-based third instance)", catFmt && catFmt.occurrence === 2,
    JSON.stringify(catFmt));
}

// 5. Alignment change only, text unchanged.
{
  const mutations = fullBatch("dg_p1", "Same text", '<p style="text-align: center">Same text</p>');
  const alignMut = mutations.find((m) => m.op === "format" && m.text === null);
  check("no modify when text unchanged (align only)", !mutations.some((m) => m.op === "modify"));
  check("alignment mutation reflects center", alignMut && alignMut.align === "center");
}

// 6. Underline + font-size combined on a sub-range.
{
  const html = '<p>Plain <u><span style="font-size: 14pt">Important</span></u> text</p>';
  const mutations = fullBatch("dg_p1", "Plain Important text", html);
  const fmt = mutations.find((m) => m.op === "format" && m.text === "Important");
  check("underline+fontSize run detected", fmt && fmt.underline === true && fmt.font_size === 14, JSON.stringify(fmt));
}

// 7. Mixed formatting runs stay separate (no incorrect merge).
{
  const { runs } = htmlToRuns("<p><strong>Bold</strong><em>Italic</em>Plain</p>");
  check("three distinct runs, not merged", runs.length === 3, JSON.stringify(runs));
}

// 8. Plain whitespace-only run between two formatted runs is skipped (no
//    pointless format mutation for a lone space).
{
  const mutations = fullBatch("dg_p1", "A B", "<p><strong>A</strong> <strong>B</strong></p>");
  const spaceFmt = mutations.find((m) => m.op === "format" && m.text === " ");
  check("whitespace-only unformatted run produces no format mutation", !spaceFmt, JSON.stringify(mutations));
}

// 9. THE TWO-PHASE SAVE BUG this refactor fixes: when text changes AND the
// NEWLY TYPED text is what gets formatted, buildFormatMutations must be
// called with the NEW fullText/runs (post-modify), not derived from a
// batch that mixes stale occurrence math with the old text. This test
// simulates exactly what saveBlockEditor now does: htmlToRuns() once,
// modify uses originalText -> fullText, format mutations are built from
// the SAME fullText/runs — so occurrence for newly-typed+formatted text
// is computed correctly regardless of what the OLD text looked like.
{
  const originalText = "Prepared by: Anna de Vries, Quality Assurance Manager";
  const html = '<p style="text-align: center">Prepared by: <strong>Maria Jansen</strong>, Quality Assurance Manager</p>';
  const { fullText, runs, align } = htmlToRuns(html);
  const formatMutations = buildFormatMutations("dg_p5", fullText, runs, align);
  const boldFmt = formatMutations.find((m) => m.text === "Maria Jansen");
  check("format mutation for newly-typed+bolded text uses the NEW fullText's occurrence",
    boldFmt && boldFmt.occurrence === 0 && boldFmt.bold === true, JSON.stringify(boldFmt));
  check("fullText reflects the new name", fullText.includes("Maria Jansen") && !fullText.includes("Anna de Vries"));
}

console.log(failures === 0 ? "\nALL PASSED" : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
