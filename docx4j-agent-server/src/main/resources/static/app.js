"use strict";

/* ============ state ============ */
const state = {
  docs: [],
  currentDoc: null,
  blocks: [],
  // modify: { op:"modify", targetId, oldText, occurrence, newText }
  // insert: { op:"insert", anchorId, position, text, style }
  // delete: { op:"delete", targetId }
  mutations: [],
  view: "blocks", // "blocks" | "preview" | "history"
  previewLoadedFor: null, // doc name the iframe currently shows
  previewDiff: null, // last live-vs-HEAD diff (drives inline git-style markup)
  previewHistoryPanel: null, // RecoveryUI instance mounted in the Preview drawer
  proposals: [], // pending proposals for the current doc (excludes the active chat session's own proposal)
  session: null, // active ConversationSession for the current doc
  recoveryPanel: null,
  headCommitId: null,
  selectedBlockIds: [], // blocks focused for AI context (Ctrl+click to add/remove)
  testRunner: {
    active: false,
    phase: null, // null | "proposing" | "awaiting_approve" | "awaiting_verdict" | "error"
    catalog: null,
    tests: [],
    index: 0,
    passed: 0,
    failed: 0,
    skipped: 0,
    currentTest: null,
    currentProposalId: null,
    highlightIds: [],
    expectFailure: false,
  },
};

const $ = (id) => document.getElementById(id);
const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const markNumbers = (s) => esc(s).replace(/(\d+)/g, "<span class=\"num-underline\">$1</span>");

/* ============ activity log ============ */
function log(kind, html) {
  const entry = document.createElement("div");
  entry.className = `log-entry ${kind}`;
  entry.innerHTML =
    `<div class="when">${new Date().toLocaleTimeString()}</div>` +
    `<div class="msg">${html}</div>`;
  $("log").prepend(entry);
}

/* ============ health ============ */
async function checkHealth() {
  try {
    const res = await fetch("/api/health");
    const ok = res.ok;
    $("health-dot").className = `dot ${ok ? "up" : "down"}`;
    $("health-text").textContent = ok ? "server up" : `error ${res.status}`;
  } catch {
    $("health-dot").className = "dot down";
    $("health-text").textContent = "unreachable";
  }
}

/* ============ documents ============ */
async function loadDocs() {
  const res = await fetch("/api/documents");
  state.docs = await res.json();
  const list = $("doc-list");
  list.innerHTML = "";
  if (state.docs.length === 0) {
    list.innerHTML = `<li class="empty">No documents yet.<br>Upload one above.</li>`;
    return;
  }
  for (const doc of state.docs) {
    const li = document.createElement("li");
    li.className = doc.name === state.currentDoc ? "active" : "";
    li.innerHTML =
      `<span class="doc-name">${esc(doc.name)}</span>` +
      `<span class="doc-meta">${(doc.size / 1024).toFixed(1)} KB · ` +
      `${new Date(doc.modified_at).toLocaleString()}</span>`;
    li.onclick = () => openDoc(doc.name);
    list.appendChild(li);
  }
}

async function uploadFile(file) {
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  try {
    const res = await fetch("/api/documents/upload", { method: "POST", body: form });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || res.statusText);
    log("ok", `Uploaded <code>${esc(body.name)}</code> (${(body.size / 1024).toFixed(1)} KB)`);
    await loadDocs();
    await openDoc(body.name);
  } catch (e) {
    log("err", `Upload failed: ${esc(e.message)}`);
  }
}

/* ============ structural index ============ */
async function openDoc(name) {
  if (!closeBlockEditor()) return; // user has unsaved changes and chose to keep editing
  await closeWordEditor(); // save+close any Word edit on the doc we're leaving
  state.currentDoc = name;
  state.mutations = [];
  state.previewLoadedFor = null;
  state.previewDiff = null;
  state.selectedBlockIds = [];
  state.session = null;
  $("prompt-history-drawer").hidden = true;
  updateAiSelectionHint();
  renderBatch();
  await loadDocs(); // refresh active highlight
  $("doc-title").textContent = name;
  $("doc-actions").hidden = false;
  $("btn-download").href = `/api/documents/${encodeURIComponent(name)}/download`;
  $("btn-ai-propose").disabled = false;
  await loadIndex();
  await loadSession();
  await loadProposals();
  refreshRecoveryPanel();
  state.previewHistoryPanel?.refresh();
  updateHistoryNavLink();
  if (state.view === "preview") await loadPreview();
}

function updateHistoryNavLink() {
  const link = $("nav-history");
  if (!link || !state.currentDoc) {
    if (link) link.href = "history.html";
    return;
  }
  link.href = `history.html?doc=${encodeURIComponent(state.currentDoc)}`;
}

function updateHeadBadge(headCommitId) {
  state.headCommitId = headCommitId;
  const badge = $("head-badge");
  if (!badge) return;
  if (!state.currentDoc || !headCommitId) {
    badge.hidden = true;
    return;
  }
  const short = headCommitId.length > 10 ? headCommitId.slice(0, 10) + "…" : headCommitId;
  badge.hidden = false;
  badge.textContent = `HEAD ${short}`;
  badge.title = `Latest commit: ${headCommitId}`;
}

function refreshRecoveryPanel() {
  if (!window.RecoveryUI) return;
  const mount = $("history-panel-mount");
  if (!mount) return;
  if (state.recoveryPanel) state.recoveryPanel.destroy();
  state.recoveryPanel = RecoveryUI.mount(mount, {
    getDocName: () => state.currentDoc,
    log: (kind, msg) => log(kind, msg),
    onLoaded: (data) => updateHeadBadge(data.headCommitId),
    onRestored: async () => {
      state.previewLoadedFor = null;
      state.mutations = [];
      renderBatch();
      await loadIndex();
      await loadProposals();
      if (state.view === "preview") await loadPreview();
    },
    onCommitted: async () => {
      state.previewLoadedFor = null;
    },
  });
}

async function loadIndex(flashIds) {
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/index`);
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || res.statusText);
    state.blocks = body.blocks;
    renderBlocks(flashIds || []);
    updateAiSelectionHint();
    $("index-hint").hidden = true;
    setView(state.view);
  } catch (e) {
    log("err", `Could not load index: ${esc(e.message)}`);
  }
}

/* ============ preview ============ */
async function setView(view) {
  if (view !== "preview" && !closeBlockEditor()) return; // unsaved changes — stay put
  if (view !== "preview") await closeWordEditor(); // save+close before leaving the Word editor
  state.view = view;
  hidePreviewMenu();
  $("tab-blocks").classList.toggle("active", view === "blocks");
  $("tab-preview").classList.toggle("active", view === "preview");
  $("tab-history").classList.toggle("active", view === "history");
  $("table-wrap").hidden = !(view === "blocks" && state.currentDoc);
  $("preview-wrap").hidden = !(view === "preview" && state.currentDoc);
  $("history-wrap").hidden = !(view === "history" && state.currentDoc);
  if (view === "preview" && state.currentDoc) {
    // Always refresh — approve/restore can change the doc while the tab name stays the same.
    loadPreview();
  }
  if (view === "history" && state.currentDoc) {
    state.recoveryPanel?.refresh();
  }
}

// Injected into the preview iframe: the document is READ-ONLY here — no
// contenteditable, no hand-rolled selection tracking. Clicking a block opens
// a real editing session (TipTap, mounted in the parent page — see
// openBlockEditor) instead of typing directly into docx4j's rendered HTML.
// Still paints git-style diff markup and exposes the hover "more actions"
// button (insert/delete/focus — structural batch ops).
function buildPreviewAugment(diffMap) {
  return `
<style>
  table[id^="docx4j_tbl_"] {
    border-collapse: collapse;
    max-width: 100%;
  }
  table[id^="docx4j_tbl_"] td,
  table[id^="docx4j_tbl_"] th {
    border: 1px solid #94a3b8 !important;
    padding: 2px 6px;
    vertical-align: top;
  }
  [data-dg-id] { border-radius: 3px; transition: background 0.1s, box-shadow 0.1s; }
  .dg-diff-added { background: #ecfdf3 !important; box-shadow: inset 3px 0 0 #16a34a; }
  .dg-diff-modified { background: #eff6ff !important; box-shadow: inset 3px 0 0 #2563eb; }
  .dg-editable { cursor: pointer; }
  .dg-editable:hover { background: #eff6ff !important; box-shadow: 0 0 0 2px #bfdbfe; position: relative; }
  .dg-action-btn {
    position: absolute;
    z-index: 50;
    width: 20px;
    height: 20px;
    line-height: 18px;
    text-align: center;
    padding: 0;
    background: #1e3a8a;
    color: #fff;
    border: none;
    border-radius: 5px;
    font: 700 13px/18px "Segoe UI", sans-serif;
    cursor: pointer;
    display: none;
  }
  .dg-action-btn:hover { background: #1e40af; }
</style>
<script>
  var DIFF_MAP = ${JSON.stringify(diffMap || {})};
  document.querySelectorAll("[data-dg-id]").forEach(function (el) {
    var id = el.getAttribute("data-dg-id");
    var kind = DIFF_MAP[id];
    if (kind === "added") el.classList.add("dg-diff-added");
    else if (kind === "modified") el.classList.add("dg-diff-modified");
    if (el.querySelector("[data-dg-id]")) return;
    el.classList.add("dg-editable");
  });

  // Plain click opens the real block editor in the parent page.
  // Ctrl+click still toggles AI focus, matching the rest of the app.
  document.addEventListener("click", function (e) {
    var el = e.target.closest("[data-dg-id].dg-editable");
    if (!el) return;
    if (e.ctrlKey || e.metaKey) {
      parent.postMessage({
        dgBlockId: el.getAttribute("data-dg-id"), x: e.clientX, y: e.clientY, ctrlKey: true,
      }, "*");
      return;
    }
    var r = el.getBoundingClientRect();
    parent.postMessage({
      dgEditBlock: {
        targetId: el.getAttribute("data-dg-id"),
        html: el.innerHTML,
        rect: { top: r.top, left: r.left, right: r.right, bottom: r.bottom, width: r.width, height: r.height },
      },
    }, "*");
  });

  var actionBtn = document.createElement("button");
  actionBtn.type = "button";
  actionBtn.className = "dg-action-btn";
  actionBtn.textContent = "\\u22ee";
  actionBtn.title = "Insert / delete / focus for AI";
  document.body.appendChild(actionBtn);
  var hoverTarget = null;
  function dgPositionBtn(el) {
    var r = el.getBoundingClientRect();
    actionBtn.style.display = "block";
    actionBtn.style.top = (window.scrollY + r.top - 6) + "px";
    actionBtn.style.left = (window.scrollX + r.right - 16) + "px";
  }
  document.addEventListener("mouseover", function (e) {
    var el = e.target.closest("[data-dg-id]");
    if (!el || !el.classList.contains("dg-editable")) return;
    hoverTarget = el;
    dgPositionBtn(el);
  });
  document.addEventListener("mouseout", function (e) {
    if (e.relatedTarget === actionBtn) return;
    setTimeout(function () {
      if (!actionBtn.matches(":hover") && !(hoverTarget && hoverTarget.matches(":hover"))) {
        actionBtn.style.display = "none";
      }
    }, 80);
  });
  actionBtn.addEventListener("mousedown", function (e) { e.preventDefault(); e.stopPropagation(); });
  actionBtn.addEventListener("click", function (e) {
    e.stopPropagation();
    if (!hoverTarget) return;
    var r = hoverTarget.getBoundingClientRect();
    parent.postMessage({
      dgBlockId: hoverTarget.getAttribute("data-dg-id"), x: r.right, y: r.bottom, ctrlKey: false,
    }, "*");
  });
<\/script>`;
}

async function loadPreview() {
  // Reloading would otherwise destroy an in-progress, unsaved edit with no
  // warning — if the user has one open and chooses to keep it, skip this
  // reload rather than silently discarding their typing.
  if (!closeBlockEditor()) return;
  const doc = state.currentDoc;
  hidePreviewMenu();
  $("preview-loading").style.display = "flex";
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(doc)}/preview`);
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.error || res.statusText);
    }
    let html = await res.text();
    await refreshPreviewDiff();
    const diffMap = {};
    if (state.previewDiff) {
      for (const b of state.previewDiff.modified) diffMap[b.target_id] = "modified";
      for (const b of state.previewDiff.added) diffMap[b.target_id] = "added";
    }
    const augment = buildPreviewAugment(diffMap);
    html = html.includes("</body>")
      ? html.replace("</body>", `${augment}</body>`)
      : html + augment;
    $("preview-frame").srcdoc = html;
    state.previewLoadedFor = doc;
    updatePreviewDiffBadge();
  } catch (e) {
    log("err", `Could not render preview: ${esc(e.message)}`);
  } finally {
    $("preview-loading").style.display = "none";
  }
}

// Messages from the preview iframe: dgEditBlock opens the real editor;
// dgBlockId is the structural action menu (hover button or Ctrl+click-focus).
window.addEventListener("message", (e) => {
  if (!e.data) return;
  if ("dgEditBlock" in e.data) {
    openBlockEditor(e.data.dgEditBlock);
    return;
  }
  if (!("dgBlockId" in e.data)) return;
  const { dgBlockId: id, x, y, ctrlKey } = e.data;
  if (!id) {
    hidePreviewMenu();
    return;
  }
  const block = state.blocks.find((b) => b.target_id === id);
  if (!block) {
    log("err", `<code>${esc(id)}</code> not found in the index — try Re-index.`);
    return;
  }
  if (ctrlKey) {
    toggleFocusBlock(id, true);
    hidePreviewMenu();
    return;
  }
  showPreviewMenu(block, x, y);
});

/* ============ block editor overlay (real rich-text editor: TipTap) ============ */
// A genuine editor (ProseMirror via TipTap) instead of a hand-rolled
// contenteditable + regex toolbar — see block-editor.bundle.js (built from
// editor-build/, npm run build). Opens over the clicked block; Save converts
// the editor's HTML into modify/format mutations through the same tested
// propose/approve pipeline everything else in this app uses.
let blockEditorInstance = null;
let blockEditorTargetId = null;
let blockEditorOriginalText = null;
let blockEditorDirty = false; // true once the user has typed/formatted anything, reset on save

function openBlockEditor(info) {
  if (!window.BlockEditor) {
    log("err", "Block editor script did not load (block-editor.bundle.js) — reload the page.");
    return;
  }
  if (!closeBlockEditor()) return; // user chose to keep editing the currently-open block
  const block = state.blocks.find((b) => b.target_id === info.targetId);
  if (!block) {
    log("err", `<code>${esc(info.targetId)}</code> not found in the index — try Re-index.`);
    return;
  }
  blockEditorTargetId = info.targetId;
  blockEditorOriginalText = block.text || "";
  blockEditorDirty = false;

  const overlay = $("block-editor-overlay");
  const wrap = $("preview-wrap");
  const frameRect = $("preview-frame").getBoundingClientRect();
  const wrapRect = wrap.getBoundingClientRect();
  const width = Math.max(info.rect.width, 320);
  overlay.style.width = `${width}px`;
  overlay.hidden = false;

  blockEditorInstance = window.BlockEditor.mount($("block-editor-mount"), {
    html: info.html,
    onChange: () => { blockEditorDirty = true; },
  });
  blockEditorInstance.focus();

  const top = frameRect.top - wrapRect.top + info.rect.top;
  const left = frameRect.left - wrapRect.left + info.rect.left;
  overlay.style.left = `${Math.max(8, Math.min(left, wrap.clientWidth - width - 8))}px`;
  overlay.style.top = `${Math.max(8, Math.min(top, wrap.clientHeight - overlay.offsetHeight - 8))}px`;
}

// Returns false (and leaves the editor open) if the user has unsaved
// changes and chooses to keep editing — callers that can abort (opening a
// different block, switching tabs/docs) should check this and stop.
function closeBlockEditor() {
  if (!blockEditorInstance) return true;
  if (blockEditorDirty
      && !confirm("Discard unsaved changes in this block? This cannot be undone.")) {
    return false;
  }
  blockEditorInstance.destroy();
  blockEditorInstance = null;
  blockEditorTargetId = null;
  blockEditorOriginalText = null;
  blockEditorDirty = false;
  $("block-editor-overlay").hidden = true;
  return true;
}

// Two propose+approve round trips, not one: the server validates a whole
// batch against the document's state AT PROPOSE TIME, not incrementally as
// each mutation would apply — so a format mutation referencing brand-new
// text from a modify earlier in the SAME batch fails validation even though
// sequential application would work. Landing the modify first (and letting
// it actually apply) before computing/sending the format mutations sidesteps
// that entirely, using the exact same tested endpoints as everywhere else.
async function saveBlockEditor() {
  if (!blockEditorInstance || !blockEditorTargetId) return;
  const targetId = blockEditorTargetId;
  const originalText = blockEditorOriginalText;
  const { fullText, runs, align } = htmlToRuns(blockEditorInstance.getHTML());

  const btn = $("btn-block-editor-save");
  if (btn) { btn.disabled = true; btn.textContent = "Saving…"; }
  try {
    let lastResult = null;
    if (fullText !== originalText) {
      lastResult = await proposeAndApprove({
        schema_version: 1,
        explanation: "Block editor save (text)",
        mutations: [{ op: "modify", target_id: targetId, old_text: originalText, occurrence: 0, new_text: fullText }],
      });
    }
    const formatMutations = buildFormatMutations(targetId, fullText, runs, align);
    if (formatMutations.length > 0) {
      lastResult = await proposeAndApprove({
        schema_version: 1, explanation: "Block editor save (format)", mutations: formatMutations,
      });
    }
    log("ok", `Saved <code>${esc(targetId)}</code>.`);
    blockEditorDirty = false; // already persisted — closeBlockEditor should not prompt
    closeBlockEditor();
    state.previewLoadedFor = null;
    await loadIndex((lastResult && lastResult.changed_ids) || [targetId]);
    await loadPreview();
    state.recoveryPanel?.refresh();
    state.previewHistoryPanel?.refresh();
  } catch (e) {
    log("err", `Save failed: ${esc(e.message)}`);
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = "Save"; }
  }
}

async function proposeAndApprove(batch) {
  const res = await fetch("/api/proposals", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ doc_name: state.currentDoc, batch }),
  });
  const body = await res.json();
  if (!res.ok) {
    const detail = body.details
      ? body.details.map((d) => `[#${d.mutation_index}] ${d.code} — ${d.message}`).join("; ")
      : (body.message || body.error || res.statusText);
    throw new Error(detail);
  }
  const approveRes = await fetch(`/api/proposals/${encodeURIComponent(body.id)}/approve`, { method: "POST" });
  const approveBody = await approveRes.json();
  if (!approveRes.ok) {
    const detail = approveBody.details
      ? approveBody.details.map((d) => `[#${d.mutation_index}] ${d.code} — ${d.message}`).join("; ")
      : (approveBody.message || approveBody.error || approveRes.statusText);
    throw new Error(detail);
  }
  return approveBody;
}

/**
 * Walks TipTap's output HTML into {fullText, runs, align}. runs are
 * document-order, contiguous spans merged wherever bold/italic/underline/
 * fontSize are identical.
 */
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

/**
 * One format mutation per formatted run (occurrence computed the same way
 * the server counts it — literal substring position in the flattened text,
 * tracked via a running cursor so it stays correct even when a short run's
 * text recurs elsewhere), plus one alignment format for the whole block.
 * `fullText` must already match the block's CURRENT server-side text (i.e.
 * any modify has already landed) — see saveBlockEditor's two-phase save.
 */
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

/* ============ inline git-style diff + rollback (lives in the Preview tab) ============ */
async function refreshPreviewDiff() {
  if (!state.currentDoc || !state.headCommitId) {
    state.previewDiff = null;
    return;
  }
  try {
    const enc = encodeURIComponent(state.currentDoc);
    const params = new URLSearchParams({
      fromType: "commit", from: state.headCommitId, toType: "live", to: "current",
    });
    const res = await fetch(`/api/documents/${enc}/diff?${params}`);
    state.previewDiff = res.ok ? await res.json() : null;
  } catch {
    state.previewDiff = null;
  }
}

function updatePreviewDiffBadge() {
  const bar = $("preview-topbar");
  const summary = $("preview-diff-summary");
  if (!bar || !summary) return;
  const d = state.previewDiff;
  if (!d) {
    bar.hidden = !state.currentDoc;
    summary.textContent = "No baseline yet — commit to start tracking changes.";
    return;
  }
  bar.hidden = false;
  const total = d.modified.length + d.added.length + d.removed.length;
  summary.textContent = total === 0
    ? "No changes since HEAD"
    : `${d.modified.length} modified · ${d.added.length} added · ${d.removed.length} removed since HEAD`;
}

function renderPreviewDiffPanel(diff) {
  if (!diff) return `<div class="recovery-empty">No baseline yet — commit to start tracking changes.</div>`;
  const rows = (list, label, cls) => list.map((b) =>
    `<div class="recovery-diff-row ${cls}">` +
    `<span class="recovery-diff-badge ${cls}">${label}</span>` +
    `<code class="tid">${esc(b.target_id)}</code>` +
    (b.before_text != null
      ? `<span class="recovery-diff-before">${esc(truncatePreview(b.before_text))}</span>` : "") +
    (b.before_text != null && b.after_text != null
      ? `<span class="recovery-diff-arrow">&#8594;</span>` : "") +
    (b.after_text != null
      ? `<span class="recovery-diff-after">${esc(truncatePreview(b.after_text))}</span>` : "") +
    `</div>`
  ).join("");
  const total = diff.modified.length + diff.added.length + diff.removed.length;
  if (total === 0) return `<div class="recovery-empty">No changes since HEAD.</div>`;
  return rows(diff.modified, "modified", "modified")
    + rows(diff.added, "added", "added")
    + rows(diff.removed, "removed", "removed");
}

function truncatePreview(s, n = 90) {
  if (!s) return "";
  return s.length > n ? s.slice(0, n) + "…" : s;
}

async function commitFromPreview() {
  const msg = $("preview-commit-msg").value.trim();
  if (!msg) {
    log("err", "Enter a commit message.");
    return;
  }
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/commits`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: msg }),
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || body.message || res.statusText);
    log("ok", `Committed "${esc(msg)}" from Preview.`);
    $("preview-commit-msg").value = "";
    $("preview-commit-box").hidden = true;
    updateHeadBadge(body.commit_id);
    await loadPreview();
    state.recoveryPanel?.refresh();
    state.previewHistoryPanel?.refresh();
  } catch (e) {
    log("err", `Commit failed: ${esc(e.message)}`);
  }
}

function mountPreviewHistory() {
  if (!window.RecoveryUI || state.previewHistoryPanel) return;
  const mount = $("preview-history-mount");
  if (!mount) return;
  state.previewHistoryPanel = RecoveryUI.mount(mount, {
    getDocName: () => state.currentDoc,
    log: (kind, msg) => log(kind, msg),
    onLoaded: (data) => updateHeadBadge(data.headCommitId),
    onRestored: async () => {
      state.mutations = [];
      renderBatch();
      await loadIndex();
      await loadProposals();
      await loadPreview();
      state.recoveryPanel?.refresh();
    },
    onCommitted: async () => {
      state.recoveryPanel?.refresh();
      await loadPreview();
    },
  });
}

function showPreviewMenu(block, x, y) {
  const menu = $("preview-menu");
  $("preview-menu-id").textContent = block.target_id;

  const actions = $("preview-menu-actions");
  actions.innerHTML = "";
  const addBtn = (label, title, cls, fn) => {
    const btn = document.createElement("button");
    btn.className = `btn ghost sm ${cls || ""}`;
    btn.innerHTML = label;
    btn.title = title;
    btn.onclick = () => { fn(); hidePreviewMenu(); };
    actions.appendChild(btn);
  };
  const focused = isBlockFocused(block.target_id);
  addBtn(focused ? "Unfocus" : "Focus", "Toggle AI focus block", "focus-btn", () => {
    toggleFocusBlock(block.target_id, true);
  });
  addBtn("Edit", "Stage modify", "", () => stageModify(block));
  if (block.type === "paragraph") {
    addBtn("+&#8593;", "Insert paragraph before", "", () => stageInsert(block, "before"));
    addBtn("+&#8595;", "Insert paragraph after", "", () => stageInsert(block, "after"));
    addBtn("Del", "Stage delete", "danger-text", () => stageDelete(block));
  }

  // Position within the preview area; iframe click coords map 1:1 because the
  // iframe fills the wrapper. Keep the menu inside the visible box.
  const wrap = $("preview-wrap");
  menu.hidden = false;
  const left = Math.min(x, wrap.clientWidth - menu.offsetWidth - 8);
  const top = Math.min(y + 10, wrap.clientHeight - menu.offsetHeight - 8);
  menu.style.left = `${Math.max(8, left)}px`;
  menu.style.top = `${Math.max(8, top)}px`;
}

function hidePreviewMenu() {
  $("preview-menu").hidden = true;
}

function isBlockFocused(id) {
  return state.selectedBlockIds.includes(id);
}

function toggleFocusBlock(id, additive) {
  if (!id) return;
  if (additive) {
    const idx = state.selectedBlockIds.indexOf(id);
    if (idx >= 0) state.selectedBlockIds.splice(idx, 1);
    else state.selectedBlockIds.push(id);
  } else {
    state.selectedBlockIds = [id];
  }
  updateAiSelectionHint();
  renderBlocks([]);
}

function clearFocusBlocks() {
  state.selectedBlockIds = [];
  updateAiSelectionHint();
  renderBlocks([]);
}

function selectedBlocksForAi() {
  return state.selectedBlockIds
    .map((id) => state.blocks.find((b) => b.target_id === id))
    .filter(Boolean)
    .map((b) => ({ target_id: b.target_id, text: b.text || "" }));
}

function selectedTextForAi() {
  const blocks = selectedBlocksForAi();
  if (blocks.length === 0) return null;
  if (blocks.length === 1) return blocks[0].text || null;
  return blocks.map((b) => `${b.target_id}: ${b.text}`).join("\n");
}

function updateAiSelectionHint() {
  const el = $("ai-selection-hint");
  if (!el) return;
  if (state.selectedBlockIds.length === 0) {
    el.hidden = true;
    return;
  }
  const chips = state.selectedBlockIds.map((id) => {
    const block = state.blocks.find((b) => b.target_id === id);
    const preview = block && block.text
      ? esc(block.text.slice(0, 40)) + (block.text.length > 40 ? "…" : "")
      : "";
    return `<code class="tid">${esc(id)}</code>${preview ? ` <span class="muted">${preview}</span>` : ""}`;
  }).join("<br>");
  el.hidden = false;
  el.innerHTML =
    `<span class="focus-label">Focus blocks (${state.selectedBlockIds.length}):</span><br>${chips}` +
    `<br><span class="muted">Ctrl+click in Preview or Blocks to add/remove.</span> ` +
    `<button class="btn ghost sm" id="btn-clear-selection">Clear all</button>`;
  const btn = $("btn-clear-selection");
  if (btn) btn.onclick = () => clearFocusBlocks();
}

function renderBlocks(flashIds) {
  const tbody = $("blocks-body");
  tbody.innerHTML = "";
  const staged = new Set(
    state.mutations.map((m) => m.op === "insert" ? m.anchorId : m.targetId));
  for (const block of state.blocks) {
    const tr = document.createElement("tr");
    if (staged.has(block.target_id)) tr.classList.add("staged");
    if (flashIds.includes(block.target_id)) tr.classList.add("flash-changed");
    if (state.testRunner.highlightIds.includes(block.target_id)) tr.classList.add("test-highlight");
    if (isBlockFocused(block.target_id)) tr.classList.add("focus-block");
    const typeTag = block.type === "table_cell"
      ? `<span class="tag cell">cell ${block.row},${block.col}</span>`
      : `<span class="tag">para</span>`;
    const text = block.text
      ? `<span class="block-text">${markNumbers(block.text)}</span>`
      : `<span class="block-text empty">(empty)</span>`;
    const isParagraph = block.type === "paragraph";
    const actions =
      `<div class="row-actions">` +
      `<button class="btn ghost sm" data-act="modify" title="Stage modify">Edit</button>` +
      (isParagraph
        ? `<button class="btn ghost sm" data-act="insert-before" title="Insert paragraph before">+&#8593;</button>` +
          `<button class="btn ghost sm" data-act="insert-after" title="Insert paragraph after">+&#8595;</button>` +
          `<button class="btn ghost sm danger-text" data-act="delete" title="Stage delete">Del</button>`
        : "") +
      `</div>`;
    tr.innerHTML =
      `<td class="num">${block.ordinal}</td>` +
      `<td><span class="tid">${esc(block.target_id)}</span></td>` +
      `<td>${typeTag}</td>` +
      `<td><span class="tag">${esc(block.style)}</span></td>` +
      `<td class="num">${block.run_count}</td>` +
      `<td class="num">${block.char_count}</td>` +
      `<td>${text}</td>` +
      `<td>${actions}</td>`;
    tr.querySelector('[data-act="modify"]').onclick = (ev) => { ev.stopPropagation(); stageModify(block); };
    const before = tr.querySelector('[data-act="insert-before"]');
    if (before) before.onclick = (ev) => { ev.stopPropagation(); stageInsert(block, "before"); };
    const after = tr.querySelector('[data-act="insert-after"]');
    if (after) after.onclick = (ev) => { ev.stopPropagation(); stageInsert(block, "after"); };
    const del = tr.querySelector('[data-act="delete"]');
    if (del) del.onclick = (ev) => { ev.stopPropagation(); stageDelete(block); };
    tr.onclick = (ev) => {
      if (ev.target.closest(".row-actions")) return;
      toggleFocusBlock(block.target_id, ev.ctrlKey || ev.metaKey);
    };
    tbody.appendChild(tr);
  }
}

/* ============ mutation composer ============ */
function hasContentMutation(targetId) {
  return state.mutations.some(
    (m) => (m.op === "modify" || m.op === "delete") && m.targetId === targetId);
}

function stageModify(block) {
  if (hasContentMutation(block.target_id)) {
    log("err", `<code>${esc(block.target_id)}</code> is already staged — one modify/delete per block per batch.`);
    return;
  }
  if (!block.text) {
    log("err", `<code>${esc(block.target_id)}</code> has no text; modify needs a non-empty old_text.`);
    return;
  }
  state.mutations.push({
    op: "modify",
    targetId: block.target_id,
    oldText: block.text,
    occurrence: 0,
    newText: block.text,
  });
  renderBatch();
}

function stageInsert(block, position) {
  if (block.type !== "paragraph") {
    log("err", "Inserts can only anchor on body paragraphs in v1.");
    return;
  }
  if (state.mutations.some(
      (m) => m.op === "insert" && m.anchorId === block.target_id && m.position === position)) {
    log("err", `An insert ${position} <code>${esc(block.target_id)}</code> is already staged.`);
    return;
  }
  state.mutations.push({
    op: "insert",
    anchorId: block.target_id,
    position,
    text: "",
    style: "",
  });
  renderBatch();
}

function stageDelete(block) {
  if (block.type !== "paragraph") {
    log("err", "Only body paragraphs can be deleted in v1.");
    return;
  }
  if (hasContentMutation(block.target_id)) {
    log("err", `<code>${esc(block.target_id)}</code> is already staged — one modify/delete per block per batch.`);
    return;
  }
  state.mutations.push({ op: "delete", targetId: block.target_id });
  renderBatch();
}

function renderBatch() {
  const wrap = $("mutation-cards");
  wrap.innerHTML = "";
  $("composer-hint").hidden = state.mutations.length > 0;
  $("btn-apply").disabled = state.mutations.length === 0;
  $("btn-propose").disabled = state.mutations.length === 0;

  state.mutations.forEach((m, i) => {
    const card = document.createElement("div");
    card.className = `mutation-card op-${m.op}`;
    if (m.op === "modify") {
      card.innerHTML =
        `<div class="card-head"><span><span class="op-badge modify">modify</span> ` +
        `<span class="tid">${esc(m.targetId)}</span></span>` +
        `<button class="btn ghost sm danger-text" data-remove="${i}">Remove</button></div>` +
        `<div class="field"><label>old_text (must match current text — the lock)</label>` +
        `<textarea data-field="oldText" data-i="${i}">${esc(m.oldText)}</textarea></div>` +
        `<div class="field-row">` +
        `<div class="field narrow"><label>occurrence</label>` +
        `<input type="number" min="0" value="${m.occurrence}" data-field="occurrence" data-i="${i}"></div>` +
        `<div class="field"><label>new_text (replacement)</label>` +
        `<textarea data-field="newText" data-i="${i}">${esc(m.newText)}</textarea></div>` +
        `</div>`;
    } else if (m.op === "insert") {
      card.innerHTML =
        `<div class="card-head"><span><span class="op-badge insert">insert</span> ` +
        `<span class="muted">${esc(m.position)}</span> <span class="tid">${esc(m.anchorId)}</span></span>` +
        `<button class="btn ghost sm danger-text" data-remove="${i}">Remove</button></div>` +
        `<div class="field-row">` +
        `<div class="field narrow"><label>position</label>` +
        `<select data-field="position" data-i="${i}">` +
        `<option value="before"${m.position === "before" ? " selected" : ""}>before</option>` +
        `<option value="after"${m.position === "after" ? " selected" : ""}>after</option>` +
        `</select></div>` +
        `<div class="field"><label>style (optional, e.g. BodyText — empty = copy anchor)</label>` +
        `<input type="text" value="${esc(m.style)}" data-field="style" data-i="${i}"></div>` +
        `</div>` +
        `<div class="field"><label>text of the new paragraph</label>` +
        `<textarea data-field="text" data-i="${i}" placeholder="Type the new paragraph text…">${esc(m.text)}</textarea></div>`;
    } else {
      card.innerHTML =
        `<div class="card-head"><span><span class="op-badge delete">delete</span> ` +
        `<span class="tid">${esc(m.targetId)}</span></span>` +
        `<button class="btn ghost sm danger-text" data-remove="${i}">Remove</button></div>` +
        `<p class="hint">This paragraph will be removed entirely.</p>`;
    }
    wrap.appendChild(card);
  });

  wrap.querySelectorAll("[data-remove]").forEach((btn) => {
    btn.onclick = () => {
      state.mutations.splice(Number(btn.dataset.remove), 1);
      renderBatch();
      renderBlocks([]);
    };
  });
  wrap.querySelectorAll("[data-field]").forEach((input) => {
    const handler = () => {
      const m = state.mutations[Number(input.dataset.i)];
      m[input.dataset.field] =
        input.dataset.field === "occurrence" ? Number(input.value) : input.value;
      refreshJsonPreview();
    };
    input.oninput = handler;
    input.onchange = handler; // <select> fires change, not input, in some browsers
  });

  renderBlocks([]);
  refreshJsonPreview();
}

function buildBatch() {
  return {
    schema_version: 1,
    explanation: $("explanation").value || undefined,
    mutations: state.mutations.map((m) => {
      if (m.op === "insert") {
        return {
          op: "insert",
          anchor_id: m.anchorId,
          position: m.position,
          node_type: "paragraph",
          text: m.text,
          style: m.style || null,
          cells: null,
        };
      }
      if (m.op === "delete") {
        return { op: "delete", target_id: m.targetId, node_type: "paragraph" };
      }
      return {
        op: "modify",
        target_id: m.targetId,
        old_text: m.oldText,
        occurrence: m.occurrence,
        new_text: m.newText,
      };
    }),
  };
}

function stageCommaForAllBlocks() {
  if (!state.currentDoc) {
    log("err", "Select a document first.");
    return;
  }
  state.mutations = [];
  for (const block of state.blocks) {
    const text = block.text || "";
    if (!text.trim()) continue;
    if (text.endsWith(",")) continue; // avoid targeting blocks with no effective text change
    state.mutations.push({
      op: "modify",
      targetId: block.target_id,
      oldText: text,
      occurrence: 0,
      newText: `${text},`,
    });
  }
  renderBatch();
  log("ok", `Staged ${state.mutations.length} comma-appends across non-empty blocks.`);
}

function refreshJsonPreview() {
  if (!$("json-preview").hidden) {
    $("json-preview").textContent = JSON.stringify(buildBatch(), null, 2);
  }
}

/* ============ proposals ============ */
async function loadProposals() {
  if (!state.currentDoc) return;
  try {
    const res = await fetch(`/api/proposals?doc=${encodeURIComponent(state.currentDoc)}`);
    const all = await res.json();
    const sessionProposalId = sessionCurrentProposalId(state.session);
    state.proposals = all.filter((p) => p.status === "PENDING" && p.id !== sessionProposalId);
    renderProposals();
  } catch (e) {
    log("err", `Could not load proposals: ${esc(e.message)}`);
  }
}

function renderProposals() {
  const wrap = $("proposal-cards");
  wrap.innerHTML = "";
  $("proposals-hint").hidden = state.proposals.length > 0;

  for (const proposal of state.proposals) {
    const card = document.createElement("div");
    card.className = "proposal-card";

    const sourceBadge = proposal.source === "llm"
      ? `<span class="op-badge insert">AI</span>`
      : `<span class="op-badge modify">manual</span>`;
    const promptLine = proposal.prompt
      ? `<div class="proposal-prompt">&ldquo;${esc(proposal.prompt)}&rdquo;</div>`
      : "";
    const explanation = proposal.batch && proposal.batch.explanation
      ? `<div class="proposal-explanation">${esc(proposal.batch.explanation)}</div>`
      : "";

    const diffs = (proposal.diffs || []).map((d) => {
      const before = d.before_text != null
        ? `<div class="diff-line before"><span>&minus;</span>${esc(d.before_text) || "<i>(empty)</i>"}</div>`
        : "";
      const after = d.after_text != null
        ? `<div class="diff-line after"><span>+</span>${esc(d.after_text) || "<i>(empty)</i>"}</div>`
        : "";
      return `<div class="diff-block">` +
        `<div class="diff-head"><span class="tid">${esc(d.target_id)}</span>` +
        `<span class="op-badge ${esc(d.op)}">${esc(d.op)}</span></div>` +
        before + after + `</div>`;
    }).join("");

    card.innerHTML =
      `<div class="card-head"><span>${sourceBadge} ` +
      `<span class="muted">${new Date(proposal.created_at).toLocaleTimeString()}</span>` +
      (proposal.model ? ` <span class="muted">${esc(proposal.model)}</span>` : "") +
      `</span><span class="tid">${esc(proposal.id.slice(0, 8))}</span></div>` +
      promptLine + explanation +
      `<div class="proposal-diffs">${diffs}</div>` +
      `<div class="composer-actions">` +
      `<button class="btn primary" data-approve="${esc(proposal.id)}">Approve &amp; save</button>` +
      `<button class="btn ghost danger-text" data-reject="${esc(proposal.id)}">Reject</button>` +
      `</div>`;
    wrap.appendChild(card);
  }

  wrap.querySelectorAll("[data-approve]").forEach((btn) => {
    btn.onclick = () => decideProposal(btn.dataset.approve, "approve");
  });
  wrap.querySelectorAll("[data-reject]").forEach((btn) => {
    btn.onclick = () => decideProposal(btn.dataset.reject, "reject");
  });
}

/* ============ AI chat session ============ */
// "Keep reasoning with the AI": one active session per document. Each
// message regenerates the batch considering the whole conversation and
// supersedes the session's previous proposal, instead of creating an
// independent one per prompt.
function sessionCurrentProposalId(session) {
  if (!session || !session.turns) return null;
  for (let i = session.turns.length - 1; i >= 0; i--) {
    const t = session.turns[i];
    if (t.role === "assistant" && t.proposal_id) return t.proposal_id;
  }
  return null;
}

async function loadSession() {
  if (!state.currentDoc) {
    state.session = null;
    renderSessionPanel();
    return;
  }
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/session`);
    state.session = await res.json();
  } catch (e) {
    log("err", `Could not load AI session: ${esc(e.message)}`);
    state.session = null;
  }
  await renderSessionPanel();
}

async function renderSessionPanel() {
  const transcript = $("chat-transcript");
  const proposalWrap = $("chat-current-proposal");
  if (!transcript || !proposalWrap) return;
  const session = state.session;
  if (!session || !session.turns || session.turns.length === 0) {
    transcript.innerHTML = `<p class="hint">No messages yet in this session — describe an edit below.</p>`;
    proposalWrap.innerHTML = "";
    return;
  }
  transcript.innerHTML = session.turns.map((t) => {
    const who = t.role === "user" ? "You" : "AI";
    return `<div class="chat-turn ${esc(t.role)}"><div class="chat-role">${who}</div>` +
      `<div class="chat-msg">${esc(t.message).replace(/\n/g, "<br>")}</div></div>`;
  }).join("");
  transcript.scrollTop = transcript.scrollHeight;

  const proposalId = sessionCurrentProposalId(session);
  if (!proposalId) {
    proposalWrap.innerHTML = "";
    return;
  }
  try {
    const res = await fetch(`/api/proposals/${encodeURIComponent(proposalId)}`);
    const proposal = await res.json();
    if (!res.ok || proposal.status !== "PENDING") {
      proposalWrap.innerHTML = "";
      return;
    }
    const diffs = (proposal.diffs || []).map((d) => {
      const before = d.before_text != null
        ? `<div class="diff-line before"><span>&minus;</span>${esc(d.before_text) || "<i>(empty)</i>"}</div>` : "";
      const after = d.after_text != null
        ? `<div class="diff-line after"><span>+</span>${esc(d.after_text) || "<i>(empty)</i>"}</div>` : "";
      return `<div class="diff-block"><div class="diff-head"><span class="tid">${esc(d.target_id)}</span>` +
        `<span class="op-badge ${esc(d.op)}">${esc(d.op)}</span></div>${before}${after}</div>`;
    }).join("");
    proposalWrap.innerHTML =
      `<div class="proposal-card">` +
      `<div class="card-head"><span><span class="op-badge insert">AI</span> ` +
      `<span class="muted">current proposal — review, then Approve or keep chatting</span></span></div>` +
      `<div class="proposal-diffs">${diffs || '<p class="hint">No block changes.</p>'}</div>` +
      `<div class="composer-actions">` +
      `<button class="btn primary" id="btn-session-approve">Approve &amp; save</button>` +
      `<button class="btn ghost danger-text" id="btn-session-reject">Reject</button>` +
      `</div></div>`;
    $("btn-session-approve").onclick = approveSessionProposal;
    $("btn-session-reject").onclick = rejectSessionProposal;
  } catch (e) {
    proposalWrap.innerHTML = "";
  }
}

async function sendSessionMessage() {
  const prompt = $("ai-prompt").value.trim();
  if (!prompt) {
    log("err", "Describe the edit first.");
    return;
  }
  const selectedText = selectedTextForAi();
  const selectedBlocks = selectedBlocksForAi();
  const btn = $("btn-ai-propose");
  const prevLabel = btn.textContent;
  btn.disabled = true;
  btn.textContent = "Thinking…";
  try {
    const payload = { message: prompt, model: $("ai-model").value.trim() || undefined };
    if (selectedBlocks.length > 0) payload.selected_blocks = selectedBlocks;
    if (selectedText) payload.selected_text = selectedText;
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/session/message`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const body = await res.json();
    if (!res.ok) {
      const detail = body.details
        ? body.details.map((d) => `[#${d.mutation_index}] ${d.code} — ${d.message}`).join("; ")
        : (body.message || body.error || res.statusText);
      throw new Error(detail);
    }
    state.session = body;
    $("ai-prompt").value = "";
    await renderSessionPanel();
    await loadProposals();
    log("ok", "AI responded — review the diff above and keep refining or approve.");
  } catch (e) {
    log("err", `AI chat failed: ${esc(e.message)}`);
  } finally {
    btn.disabled = !state.currentDoc;
    btn.textContent = prevLabel;
  }
}

async function approveSessionProposal() {
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/session/approve`, { method: "POST" });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || body.message || res.statusText);
    log("ok", `Session approved — ${body.applied_count} mutation(s) saved.`);
    state.previewLoadedFor = null;
    await loadIndex(body.changed_ids || []);
    if (state.view === "preview") await loadPreview();
    state.recoveryPanel?.refresh();
    await loadSession();
    await loadProposals();
  } catch (e) {
    log("err", `Could not approve: ${esc(e.message)}`);
  }
}

async function rejectSessionProposal() {
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/session/reject`, { method: "POST" });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.error || body.message || res.statusText);
    }
    log("ok", "Rejected — describe what you'd like instead.");
    await loadSession();
    await loadProposals();
  } catch (e) {
    log("err", `Could not reject: ${esc(e.message)}`);
  }
}

async function startNewSession() {
  if (sessionCurrentProposalId(state.session)) {
    if (!confirm("Start a new session? The current pending proposal will be rejected "
        + "(it stays visible in Prompt History).")) {
      return;
    }
  }
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/session/new`, { method: "POST" });
    state.session = await res.json();
    await renderSessionPanel();
    await loadProposals();
    log("ok", "Started a new session — no memory of the previous conversation.");
  } catch (e) {
    log("err", `Could not start new session: ${esc(e.message)}`);
  }
}

async function loadPromptHistory() {
  const wrap = $("prompt-history-list");
  if (!wrap || !state.currentDoc) return;
  wrap.innerHTML = `<p class="hint">Loading…</p>`;
  try {
    const res = await fetch(`/api/proposals?doc=${encodeURIComponent(state.currentDoc)}`);
    const all = await res.json();
    const withPrompt = all
        .filter((p) => p.prompt)
        .sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
    if (withPrompt.length === 0) {
      wrap.innerHTML = `<p class="hint">No AI prompts yet for this document.</p>`;
      return;
    }
    const badgeClass = (status) =>
      status === "PENDING" ? "modified" : status === "APPROVED" ? "added" : "removed";
    wrap.innerHTML = withPrompt.map((p) =>
      `<div class="prompt-history-row">` +
      `<span class="recovery-diff-badge ${badgeClass(p.status)}">${esc(p.status)}</span>` +
      `<span class="prompt-history-text" title="${esc(p.prompt)}">${esc(p.prompt)}</span>` +
      `<span class="muted">${new Date(p.created_at).toLocaleDateString()}</span>` +
      `<button class="btn ghost sm" data-retry="${esc(p.id)}">Retry</button>` +
      `</div>`
    ).join("");
    wrap.querySelectorAll("[data-retry]").forEach((btn) => {
      btn.onclick = () => {
        const p = withPrompt.find((x) => x.id === btn.dataset.retry);
        if (!p) return;
        $("ai-prompt").value = p.prompt;
        $("prompt-history-drawer").hidden = true;
        $("ai-prompt").focus();
      };
    });
  } catch (e) {
    wrap.innerHTML = `<p class="hint">Could not load prompt history: ${esc(e.message)}</p>`;
  }
}

async function decideProposal(id, action) {
  try {
    const res = await fetch(`/api/proposals/${encodeURIComponent(id)}/${action}`, { method: "POST" });
    const body = await res.json();
    if (!res.ok) {
      const detail = body.details
        ? body.details.map((d) => `[#${d.mutation_index}] ${d.code} — ${d.message}`).join("; ")
        : (body.error || body.message || res.statusText);
      throw new Error(detail);
    }
    if (action === "approve") {
      const ids = body.changed_ids || [];
      log("ok",
        `Proposal approved — ${body.applied_count} mutation(s) saved. Changed: ` +
        ids.map((x) => `<code>${esc(x)}</code>`).join(", "));
      state.previewLoadedFor = null;
      await loadIndex(ids);
      if (state.view === "preview") await loadPreview();
      ftOnProposalApproved(id, ids);
      state.recoveryPanel?.refresh();
    } else {
      log("ok", "Proposal rejected — document unchanged.");
    }
    await loadProposals();
  } catch (e) {
    log("err", `Could not ${action} proposal: ${esc(e.message)}`);
    await loadProposals();
  }
}

async function proposeWithAi(options = {}) {
  const prompt = (options.promptOverride ?? $("ai-prompt").value).trim();
  if (!prompt) {
    if (!options.silent) log("err", "Describe the edit first.");
    throw new Error("empty prompt");
  }
  const selectedText = options.selectedText !== undefined
    ? options.selectedText
    : selectedTextForAi();
  const selectedBlocks = options.selectedBlocks !== undefined
    ? options.selectedBlocks
    : selectedBlocksForAi();
  const btn = $("btn-ai-propose");
  const prevLabel = btn.textContent;
  btn.disabled = true;
  btn.textContent = "Thinking…";
  try {
    const payload = {
      doc_name: state.currentDoc,
      message: prompt,
      model: $("ai-model").value.trim() || undefined,
    };
    if (selectedBlocks.length > 0) payload.selected_blocks = selectedBlocks;
    if (selectedText) payload.selected_text = selectedText;
    const res = await fetch("/api/proposals", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const body = await res.json();
    if (!res.ok) {
      const detail = body.details
        ? body.details.map((d) => `[#${d.mutation_index}] ${d.code} — ${d.message}`).join("; ")
        : (body.message || body.error || res.statusText);
      throw new Error(detail);
    }
    if (!options.silent) {
      const viaBulk = body.model === "bulk-replace";
      const dropped = body.batch?.explanation?.match(/Skipped \d+|Dropped \d+/)
        ? " <span class=\"muted\">(Some invalid edits were skipped automatically.)</span>"
        : "";
      log("ok",
        (viaBulk
          ? `Bulk replace: ${body.batch.mutations.length} mutation(s) — instant, no LLM.`
          : `AI proposed ${body.batch.mutations.length} mutation(s) — review below.`) +
        (body.batch.explanation ? `<br><i>${esc(body.batch.explanation)}</i>` : "") +
        dropped);
    }
    if (!options.skipClearPrompt) $("ai-prompt").value = "";
    await loadProposals();
    return body;
  } catch (e) {
    if (!options.silent) log("err", `AI proposal failed: ${esc(e.message)}`);
    throw e;
  } finally {
    btn.disabled = !state.currentDoc;
    btn.textContent = prevLabel;
  }
}

async function proposeManualBatch() {
  const batch = buildBatch();
  $("btn-propose").disabled = true;
  try {
    const res = await fetch("/api/proposals", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ doc_name: state.currentDoc, batch }),
    });
    const body = await res.json();
    if (!res.ok) {
      const detail = body.details
        ? body.details.map((d) => `[#${d.mutation_index}] ${d.code} — ${d.message}`).join("; ")
        : (body.message || body.error || res.statusText);
      throw new Error(detail);
    }
    log("ok", `Proposal created from ${batch.mutations.length} staged mutation(s) — review above.`);
    state.mutations = [];
    $("explanation").value = "";
    renderBatch();
    await loadProposals();
  } catch (e) {
    log("err", `Proposal failed: ${esc(e.message)}`);
  } finally {
    $("btn-propose").disabled = state.mutations.length === 0;
  }
}

/* ============ apply ============ */
async function applyBatch() {
  const batch = buildBatch();
  $("btn-apply").disabled = true;
  try {
    const res = await fetch(`/api/dev/apply/${encodeURIComponent(state.currentDoc)}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(batch),
    });
    const traceId = res.headers.get("X-Trace-Id");
    const body = await res.json();

    if (res.ok) {
      const ids = body.changed_ids || [];
      const created = body.created_ids || [];
      log("ok",
        `Applied ${body.applied_count} mutation(s). Changed: ` +
        ids.map((id) => `<code>${esc(id)}</code>`).join(", ") +
        (created.length
          ? `<br>New blocks: ${created.map((id) => `<code>${esc(id)}</code>`).join(", ")}`
          : "") +
        (traceId ? `<br><small>trace_id: <code>${esc(traceId)}</code></small>` : ""));
      state.mutations = [];
      $("explanation").value = "";
      renderBatch();
      state.previewLoadedFor = null; // document changed — preview is stale
      await loadIndex(ids);
      if (state.view === "preview") await loadPreview();
    } else if (body.details) {
      const items = body.details
        .map((d) => `<li>[#${d.mutation_index}] <b>${esc(d.code)}</b> — ${esc(d.message)}</li>`)
        .join("");
      log("err", `Validation failed (HTTP ${res.status}):<ul>${items}</ul>Document not modified.`);
    } else if (body.error === "invariant_violation") {
      const missing = body.missing_targeted_ids || [];
      const unexpected = body.unexpected_changed_ids || [];
      log("err",
        `Invariant violation (HTTP ${res.status}). Batch rolled back.<br>` +
        `Missing targeted changes: ${missing.length ? missing.map((id) => `<code>${esc(id)}</code>`).join(", ") : "(none)"}<br>` +
        `Unexpected changed ids: ${unexpected.length ? unexpected.map((id) => `<code>${esc(id)}</code>`).join(", ") : "(none)"}<br>` +
        (traceId ? `<small>trace_id: <code>${esc(traceId)}</code></small>` : ""));
      await loadIndex();
    } else {
      log("err",
        `Rejected (HTTP ${res.status}): ${esc(body.error || body.message || "unknown error")}. ` +
        `Batch rolled back — document unchanged.` +
        (traceId ? `<br><small>trace_id: <code>${esc(traceId)}</code></small>` : ""));
      await loadIndex();
    }
  } catch (e) {
    log("err", `Request failed: ${esc(e.message)}`);
  } finally {
    $("btn-apply").disabled = state.mutations.length === 0;
  }
}

/* ============ wiring ============ */
$("btn-refresh-docs").onclick = loadDocs;
$("btn-refresh-index").onclick = () => {
  state.previewLoadedFor = null;
  loadIndex();
  if (state.view === "preview") loadPreview();
};
$("btn-block-editor-save").onclick = saveBlockEditor;
$("btn-block-editor-cancel").onclick = () => closeBlockEditor();
async function closeWordEditor() {
  if (!window.OnlyOfficeEditor || !OnlyOfficeEditor.isOpen()) return true;
  const ok = await OnlyOfficeEditor.closeAndSave({
    log: (kind, msg) => log(kind, msg),
    onSaved: async () => {
      state.previewLoadedFor = null;
      await loadIndex();
      if (state.view === "preview") await loadPreview();
    },
  });
  $("btn-edit-in-word").textContent = "Edit in Word…";
  return ok;
}

$("btn-edit-in-word").onclick = async () => {
  if (!state.currentDoc || !window.OnlyOfficeEditor) return;
  const btn = $("btn-edit-in-word");
  if (OnlyOfficeEditor.isOpen()) {
    await closeWordEditor();
    return;
  }
  btn.disabled = true;
  const opened = await OnlyOfficeEditor.open(state.currentDoc, { log: (kind, msg) => log(kind, msg) });
  btn.disabled = false;
  if (opened) btn.textContent = "Back to AI Preview…";
};
$("btn-preview-commit-toggle").onclick = () => {
  const box = $("preview-commit-box");
  box.hidden = !box.hidden;
  if (!box.hidden) $("preview-commit-msg").focus();
};
$("btn-preview-commit-go").onclick = commitFromPreview;
$("btn-preview-history-toggle").onclick = () => {
  const drawer = $("preview-history-drawer");
  drawer.hidden = !drawer.hidden;
  if (!drawer.hidden) {
    mountPreviewHistory();
    state.previewHistoryPanel?.refresh();
  }
};
$("preview-diff-summary").onclick = () => {
  const panel = $("preview-diff-panel");
  if (!panel) return;
  if (!panel.hidden) { panel.hidden = true; return; }
  panel.innerHTML = renderPreviewDiffPanel(state.previewDiff);
  panel.hidden = false;
};
$("tab-blocks").onclick = () => setView("blocks");
$("tab-preview").onclick = () => setView("preview");
$("tab-history").onclick = () => setView("history");
// Clicking anywhere in the console outside the floating menu closes it.
document.addEventListener("click", (e) => {
  if (!e.target.closest("#preview-menu")) hidePreviewMenu();
});
$("btn-clear-batch").onclick = () => { state.mutations = []; renderBatch(); };
$("btn-apply").onclick = applyBatch;
$("btn-propose").onclick = proposeManualBatch;
$("btn-ai-propose").onclick = () => sendSessionMessage().catch(() => {});
$("btn-refresh-proposals").onclick = loadProposals;
$("btn-session-new").onclick = startNewSession;
$("btn-session-history").onclick = () => {
  const drawer = $("prompt-history-drawer");
  drawer.hidden = !drawer.hidden;
  if (!drawer.hidden) loadPromptHistory();
};
$("btn-prompt-history-close").onclick = () => { $("prompt-history-drawer").hidden = true; };
$("btn-clear-log").onclick = () => { $("log").innerHTML = ""; };
$("btn-stage-all-comma").onclick = stageCommaForAllBlocks;
$("btn-toggle-json").onclick = () => {
  const pre = $("json-preview");
  pre.hidden = !pre.hidden;
  $("btn-toggle-json").textContent = pre.hidden ? "View JSON" : "Hide JSON";
  refreshJsonPreview();
};

$("file-input").onchange = (e) => uploadFile(e.target.files[0]);
// Clicking the label opens the file picker natively (input is nested inside it).
const zone = $("upload-zone");
zone.ondragover = (e) => { e.preventDefault(); zone.classList.add("drag"); };
zone.ondragleave = () => zone.classList.remove("drag");
zone.ondrop = (e) => {
  e.preventDefault();
  zone.classList.remove("drag");
  uploadFile(e.dataTransfer.files[0]);
};

checkHealth();
setInterval(checkHealth, 15000);
loadDocs();

/* ============ functional test runner (UI) ============ */
function ftBind(id, fn) {
  const el = $(id);
  if (el) el.onclick = fn;
  else console.error("Functional tests: missing element #" + id);
}

function ftSetStatus(text) {
  const el = $("ft-status");
  if (el) el.textContent = text || "";
}

function ftSetVerdictButtons(mode) {
  // mode: hidden | awaiting_approve | awaiting_verdict | error
  const wrap = $("ft-verdict");
  if (!wrap) return;
  wrap.hidden = mode === "hidden";
  const tr = state.testRunner;
  if (mode === "error") {
    $("ft-pass").hidden = !tr.expectFailure;
    $("ft-fail").hidden = false;
    $("ft-skip").hidden = false;
    $("ft-reject-fail").hidden = true;
    return;
  }
  $("ft-pass").hidden = mode !== "awaiting_verdict";
  $("ft-fail").hidden = mode !== "awaiting_verdict";
  $("ft-skip").hidden = mode === "hidden" || mode === "awaiting_verdict";
  $("ft-reject-fail").hidden = mode !== "awaiting_approve";
}

function ftUpdateUi() {
  const tr = state.testRunner;
  const idle = $("ft-idle");
  const active = $("ft-active");
  if (idle) idle.hidden = tr.active;
  if (active) active.hidden = !tr.active;
  if ($("ft-btn-start")) $("ft-btn-start").disabled = tr.active;
  if ($("ft-btn-stop")) $("ft-btn-stop").hidden = !tr.active;
  if ($("ft-banner")) $("ft-banner").hidden = !tr.active || !tr.currentTest;
  if (!tr.active) {
    ftSetStatus("");
    ftSetVerdictButtons("hidden");
    return;
  }
  const total = tr.tests.length;
  const done = tr.passed + tr.failed + tr.skipped;
  if ($("ft-progress")) {
    $("ft-progress").textContent =
      `Progress: ${done}/${total} · ${tr.passed} pass · ${tr.failed} fail · ${tr.skipped} skip`;
  }
  const t = tr.currentTest;
  if (t) {
    if ($("ft-test-meta")) {
      $("ft-test-meta").innerHTML =
        `<span class="tid">Test ${esc(t.id)}</span> <strong>${esc(t.name || "")}</strong>`;
    }
    if ($("ft-expected")) {
      $("ft-expected").innerHTML =
        `<b>Expected blocks:</b> ${(t.expected_blocks || []).map((id) => `<code>${esc(id)}</code>`).join(", ") || "(none)"}` +
        `<br><b>Expected result:</b> ${esc(t.expected_result || "")}` +
        (tr.expectFailure ? `<br><i>Negative test — proposal error may be expected.</i>` : "");
    }
    if ($("ft-banner-title")) $("ft-banner-title").textContent = `#${t.id} ${t.name || ""}`;
  }
}

async function ftRecordResult(verdict, extra = {}) {
  const tr = state.testRunner;
  const t = tr.currentTest;
  if (!t) return;
  try {
    await fetch("/api/dev/functional-tests/results", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        test_id: t.id,
        test_name: t.name,
        prompt: t.prompt,
        human_verdict: verdict,
        expect_failure: tr.expectFailure,
        proposal_id: tr.currentProposalId,
        phase: tr.phase,
        ...extra,
      }),
    });
  } catch (e) {
    log("err", `Could not log test result: ${esc(e.message)}`);
  }
}

async function ftResetBaseline() {
  const doc = state.testRunner.catalog.document;
  const golden = state.testRunner.catalog.golden_path;
  const res = await fetch(`/api/dev/functional-tests/reset/${encodeURIComponent(doc)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ golden_path: golden }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || body.message || res.statusText);
  state.previewLoadedFor = null;
  state.mutations = [];
  renderBatch();
  await loadDocs();
  return body;
}

function ftHighlightBlockIds(ids) {
  state.testRunner.highlightIds = ids.filter((id) => id && id !== "NEW_INSERT");
  renderBlocks(state.testRunner.highlightIds);
  const first = state.testRunner.highlightIds[0];
  if (first) {
    const row = [...$("blocks-body").querySelectorAll("tr")].find(
      (tr) => tr.querySelector(".tid")?.textContent === first);
    row?.scrollIntoView({ block: "center", behavior: "smooth" });
  }
}

function ftHighlightFromProposal(proposal) {
  const ids = new Set();
  for (const d of proposal?.diffs || []) {
    if (d.target_id) ids.add(d.target_id);
  }
  for (const id of state.testRunner.currentTest?.expected_blocks || []) {
    if (id !== "NEW_INSERT") ids.add(id);
  }
  ftHighlightBlockIds([...ids]);
}

function ftOnProposalApproved(proposalId, changedIds) {
  const tr = state.testRunner;
  if (!tr.active || tr.phase !== "awaiting_approve" || proposalId !== tr.currentProposalId) return;
  tr.phase = "awaiting_verdict";
  ftHighlightBlockIds(changedIds || []);
  setView("preview");
  ftSetStatus("Approved — review Preview/Blocks, then mark Pass or Fail for this test.");
  ftSetVerdictButtons("awaiting_verdict");
  log("ok", `Test ${esc(tr.currentTest.id)}: approved — mark Pass or Fail when done reviewing.`);
}

async function ftAdvanceTest(verdict, extra = {}) {
  const tr = state.testRunner;
  if (verdict === "pass") tr.passed += 1;
  else if (verdict === "fail") tr.failed += 1;
  else tr.skipped += 1;

  await ftRecordResult(verdict, extra);

  tr.index += 1;
  tr.currentTest = null;
  tr.currentProposalId = null;
  tr.phase = null;
  tr.highlightIds = [];
  renderBlocks([]);
  ftUpdateUi();
  await ftRunCurrentTest();
}

async function ftRunCurrentTest() {
  const tr = state.testRunner;
  if (!tr.active || tr.index >= tr.tests.length) {
    ftFinish();
    return;
  }

  tr.currentTest = tr.tests[tr.index];
  tr.currentProposalId = null;
  tr.expectFailure = Boolean(tr.currentTest.expect_failure);
  tr.highlightIds = [];
  tr.phase = null;
  ftUpdateUi();
  ftSetVerdictButtons("hidden");
  ftSetStatus("Restoring golden document…");

  const doc = tr.catalog.document;

  try {
    await ftResetBaseline();
  } catch (e) {
    log("err", `Baseline restore failed: ${esc(e.message)}`);
    ftSetStatus(`Restore failed: ${e.message}`);
    tr.phase = "error";
    ftSetVerdictButtons("error");
    return;
  }

  if (state.currentDoc !== doc) {
    await openDoc(doc);
  } else {
    state.previewLoadedFor = null;
    await loadIndex();
    await loadProposals();
  }

  const prompt = tr.currentTest.prompt;
  $("ai-prompt").value = prompt;
  $("ai-prompt").scrollIntoView({ behavior: "smooth", block: "center" });
  tr.phase = "proposing";
  ftSetStatus("Prompt loaded in AI edit — proposing with AI…");
  log("ok", `Test ${esc(tr.currentTest.id)}: <i>${esc(prompt.slice(0, 120))}${prompt.length > 120 ? "…" : ""}</i>`);

  try {
    const proposal = await proposeWithAi({ promptOverride: prompt, skipClearPrompt: true });
    tr.currentProposalId = proposal.id;
    tr.phase = "awaiting_approve";
    ftHighlightFromProposal(proposal);
    ftSetStatus("Review the proposal, then click Approve & save (same as manual testing).");
    ftSetVerdictButtons("awaiting_approve");
    $("proposal-cards").scrollIntoView({ behavior: "smooth", block: "nearest" });
  } catch (e) {
    log("err", `Test ${esc(tr.currentTest.id)} proposal failed: ${esc(e.message)}`);
    tr.phase = "error";
    if (tr.expectFailure) {
      ftSetStatus(`Expected failure: ${e.message} — mark Pass or Fail (reject).`);
    } else {
      ftSetStatus(`Proposal failed: ${e.message}`);
    }
    ftSetVerdictButtons("error");
  }
}

async function ftRejectCurrentProposal() {
  if (!state.testRunner.currentProposalId) return;
  try {
    await fetch(`/api/proposals/${encodeURIComponent(state.testRunner.currentProposalId)}/reject`, {
      method: "POST",
    });
    await loadProposals();
  } catch {
    /* ignore */
  }
  state.testRunner.currentProposalId = null;
}

async function ftVerdictPassFail(verdict) {
  ftSetStatus("Restoring golden for next test…");
  try {
    await ftResetBaseline();
    if (state.currentDoc === state.testRunner.catalog.document) {
      state.previewLoadedFor = null;
      await loadIndex();
      if (state.view === "preview") await loadPreview();
    }
  } catch (e) {
    log("err", `Restore after verdict failed: ${esc(e.message)}`);
  }
  await ftAdvanceTest(verdict, { outcome: "applied" });
}

async function ftVerdictSkip() {
  await ftRejectCurrentProposal();
  await ftAdvanceTest("skip", { outcome: "skipped" });
}

async function ftVerdictRejectFail() {
  await ftRejectCurrentProposal();
  await ftAdvanceTest("fail", { outcome: "proposal_rejected" });
}

function ftFinish() {
  const tr = state.testRunner;
  tr.active = false;
  tr.phase = null;
  tr.currentTest = null;
  tr.highlightIds = [];
  renderBlocks([]);
  ftUpdateUi();
  log("ok",
    `Functional tests finished — ${tr.passed} pass, ${tr.failed} fail, ${tr.skipped} skip. ` +
    `Results appended to <code>docs/xyz-functional-test-results.jsonl</code>.`);
}

async function ftStart() {
  const btn = $("ft-btn-start");
  if (btn) btn.disabled = true;
  ftSetStatus("");
  try {
    log("ok", "Loading functional test catalog…");
    const res = await fetch("/api/dev/functional-tests");
    const catalog = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(catalog.error || catalog.message || `HTTP ${res.status}`);

    const tests = [...(catalog.tests || [])];
    if ($("ft-include-negative")?.checked) {
      for (const nt of catalog.negative_tests || []) {
        tests.push({ ...nt, expected_blocks: nt.expected_blocks || [] });
      }
    }
    if (tests.length === 0) throw new Error("No tests in catalog.");

    state.testRunner = {
      active: true,
      phase: null,
      catalog,
      tests,
      index: 0,
      passed: 0,
      failed: 0,
      skipped: 0,
      currentTest: null,
      currentProposalId: null,
      highlightIds: [],
      expectFailure: false,
    };
    ftUpdateUi();
    log("ok", `Starting ${tests.length} functional test(s) on <code>${esc(catalog.document)}</code>.`);
    await ftRunCurrentTest();
  } catch (e) {
    log("err", `Could not start functional tests: ${esc(e.message)}`);
    ftSetStatus(`Start failed: ${e.message}`);
    state.testRunner.active = false;
    ftUpdateUi();
  } finally {
    if (btn && !state.testRunner.active) btn.disabled = false;
  }
}

function ftStop() {
  if (!state.testRunner.active) return;
  state.testRunner.active = false;
  state.testRunner.phase = null;
  state.testRunner.highlightIds = [];
  renderBlocks([]);
  ftUpdateUi();
  log("ok", "Functional test runner stopped.");
}

ftBind("ft-btn-start", () => ftStart());
ftBind("ft-btn-stop", () => ftStop());
ftBind("ft-pass", () => {
  if (state.testRunner.phase === "awaiting_verdict") ftVerdictPassFail("pass");
  else if (state.testRunner.phase === "error") ftAdvanceTest("pass", { outcome: "expected_error" });
});
ftBind("ft-fail", () => {
  if (state.testRunner.phase === "awaiting_verdict") ftVerdictPassFail("fail");
  else if (state.testRunner.phase === "error") ftAdvanceTest("fail", { outcome: "unexpected_error" });
});
ftBind("ft-skip", () => ftVerdictSkip());
ftBind("ft-reject-fail", () => ftVerdictRejectFail());
