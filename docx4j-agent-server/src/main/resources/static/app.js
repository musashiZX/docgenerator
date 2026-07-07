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
  view: "blocks", // "blocks" | "preview"
  previewLoadedFor: null, // doc name the iframe currently shows
  proposals: [], // pending proposals for the current doc
  selectedBlockId: null, // block targeted from preview for AI context
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
  state.selectedBlockId = null;
  updateAiSelectionHint();
  renderBatch();
  await loadDocs(); // refresh active highlight
  $("doc-title").textContent = name;
  $("doc-actions").hidden = false;
  $("btn-download").href = `/api/documents/${encodeURIComponent(name)}/download`;
  $("btn-ai-propose").disabled = false;
  await loadIndex();
  await loadProposals();
  if (state.view === "preview") await loadPreview();
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
  $("table-wrap").hidden = !(view === "blocks" && state.currentDoc);
  $("preview-wrap").hidden = !(view === "preview" && state.currentDoc);
  if (view === "preview" && state.currentDoc && state.previewLoadedFor !== state.currentDoc) {
    loadPreview();
  }
}

// Injected into the preview iframe: hover shows the block id, click reports
// it back to the console so the matching row can be selected.
const PREVIEW_AUGMENT = `
<style>
  [data-dg-id] { cursor: pointer; border-radius: 3px; transition: background 0.1s; }
  [data-dg-id]:hover { background: #dbeafe !important; box-shadow: 0 0 0 2px #93c5fd; position: relative; }
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
  const { dgBlockId: id, x, y } = e.data;
  if (!id) {
    hidePreviewMenu();
    return;
  }
  const block = state.blocks.find((b) => b.target_id === id);
  if (!block) {
    log("err", `<code>${esc(id)}</code> not found in the index — try Re-index.`);
    return;
  }
  showPreviewMenu(block, x, y);
});

function showPreviewMenu(block, x, y) {
  state.selectedBlockId = block.target_id;
  updateAiSelectionHint();
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

function selectedTextForAi() {
  if (!state.selectedBlockId) return null;
  const block = state.blocks.find((b) => b.target_id === state.selectedBlockId);
  return block && block.text ? block.text : null;
}

function updateAiSelectionHint() {
  const el = $("ai-selection-hint");
  if (!el) return;
  if (!state.selectedBlockId) {
    el.hidden = true;
    return;
  }
  const block = state.blocks.find((b) => b.target_id === state.selectedBlockId);
  el.hidden = false;
  el.innerHTML = `Focus block: <code class="tid">${esc(state.selectedBlockId)}</code>` +
    (block && block.text
      ? ` — <span class="muted">${esc(block.text.slice(0, 80))}${block.text.length > 80 ? "…" : ""}</span>`
      : "") +
    ` <button class="btn ghost sm" id="btn-clear-selection">Clear</button>`;
  const btn = $("btn-clear-selection");
  if (btn) btn.onclick = () => {
    state.selectedBlockId = null;
    updateAiSelectionHint();
  };
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
    tr.querySelector('[data-act="modify"]').onclick = () => stageModify(block);
    const before = tr.querySelector('[data-act="insert-before"]');
    if (before) before.onclick = () => stageInsert(block, "before");
    const after = tr.querySelector('[data-act="insert-after"]');
    if (after) after.onclick = () => stageInsert(block, "after");
    const del = tr.querySelector('[data-act="delete"]');
    if (del) del.onclick = () => stageDelete(block);
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
          style: m.style || undefined,
        };
      }
      if (m.op === "delete") {
        return { op: "delete", target_id: m.targetId };
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
    } else {
      log("ok", "Proposal rejected — document unchanged.");
    }
    await loadProposals();
  } catch (e) {
    log("err", `Could not ${action} proposal: ${esc(e.message)}`);
    await loadProposals();
  }
}

async function proposeWithAi() {
  const prompt = $("ai-prompt").value.trim();
  if (!prompt) {
    log("err", "Describe the edit first.");
    return;
  }
  const selectedText = selectedTextForAi();
  const btn = $("btn-ai-propose");
  btn.disabled = true;
  btn.textContent = "Thinking…";
  try {
    const payload = {
      doc_name: state.currentDoc,
      message: prompt,
      model: $("ai-model").value.trim() || undefined,
    };
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
    log("ok",
      `AI proposed ${body.batch.mutations.length} mutation(s) — review below.` +
      (body.batch.explanation ? `<br><i>${esc(body.batch.explanation)}</i>` : ""));
    $("ai-prompt").value = "";
    await loadProposals();
  } catch (e) {
    log("err", `AI proposal failed: ${esc(e.message)}`);
  } finally {
    btn.disabled = !state.currentDoc;
    btn.textContent = "Propose with AI";
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
// Clicking anywhere in the console outside the floating menu closes it.
document.addEventListener("click", (e) => {
  if (!e.target.closest("#preview-menu")) hidePreviewMenu();
});
$("btn-clear-batch").onclick = () => { state.mutations = []; renderBatch(); };
$("btn-apply").onclick = applyBatch;
$("btn-propose").onclick = proposeManualBatch;
$("btn-ai-propose").onclick = proposeWithAi;
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
