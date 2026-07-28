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
  proposals: [], // pending proposals for the current doc
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
  state.currentDoc = name;
  state.mutations = [];
  state.previewLoadedFor = null;
  state.selectedBlockIds = [];
  updateAiSelectionHint();
  renderBatch();
  await loadDocs(); // refresh active highlight
  $("doc-title").textContent = name;
  $("doc-actions").hidden = false;
  $("btn-download").href = `/api/documents/${encodeURIComponent(name)}/download`;
  $("btn-ai-propose").disabled = false;
  await loadIndex();
  await loadProposals();
  refreshRecoveryPanel();
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
function setView(view) {
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

// Injected into the preview iframe: hover shows the block id, click reports
// it back to the console so the matching row can be selected.
const PREVIEW_AUGMENT = `
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
  [data-dg-id] { cursor: pointer; border-radius: 3px; transition: background 0.1s; }
  [data-dg-id]:hover { background: #dbeafe !important; box-shadow: 0 0 0 2px #93c5fd; position: relative; }
  td[data-dg-id]:hover, th[data-dg-id]:hover {
    background: #dbeafe !important;
    box-shadow: inset 0 0 0 2px #3b82f6;
  }
  [data-dg-id]:hover::before {
    content: attr(data-dg-id);
    position: absolute;
    top: -22px;
    left: 0;
    background: #1e3a8a;
    color: #fff;
    font: 600 11px/1 "Segoe UI", sans-serif;
    padding: 4px 8px;
    border-radius: 4px;
    white-space: nowrap;
    z-index: 99;
    pointer-events: none;
  }
</style>
<script>
  document.addEventListener("click", function (e) {
    var el = e.target.closest("[data-dg-id]");
    parent.postMessage({
      dgBlockId: el ? el.getAttribute("data-dg-id") : null,
      x: e.clientX,
      y: e.clientY,
      ctrlKey: !!(e.ctrlKey || e.metaKey),
    }, "*");
  });
<\/script>`;

async function loadPreview() {
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
    html = html.includes("</body>")
      ? html.replace("</body>", `${PREVIEW_AUGMENT}</body>`)
      : html + PREVIEW_AUGMENT;
    $("preview-frame").srcdoc = html;
    state.previewLoadedFor = doc;
  } catch (e) {
    log("err", `Could not render preview: ${esc(e.message)}`);
  } finally {
    $("preview-loading").style.display = "none";
  }
}

// Click in the preview opens a floating action menu on that block
// (stays in the preview — mutations are staged in the composer on the right).
window.addEventListener("message", (e) => {
  if (!e.data || !("dgBlockId" in e.data)) return;
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
    state.proposals = all.filter((p) => p.status === "PENDING");
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
$("btn-ai-propose").onclick = () => proposeWithAi().catch(() => {});
$("btn-refresh-proposals").onclick = loadProposals;
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
