"use strict";

/* ============ state ============ */
const state = {
  docs: [],
  currentDoc: null,
  blocks: [],
  mutations: [], // { targetId, oldText, occurrence, newText }
};

const $ = (id) => document.getElementById(id);
const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

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
  renderBatch();
  await loadDocs(); // refresh active highlight
  $("doc-title").textContent = name;
  $("doc-actions").hidden = false;
  $("btn-download").href = `/api/documents/${encodeURIComponent(name)}/download`;
  await loadIndex();
}

async function loadIndex(flashIds) {
  try {
    const res = await fetch(`/api/documents/${encodeURIComponent(state.currentDoc)}/index`);
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || res.statusText);
    state.blocks = body.blocks;
    renderBlocks(flashIds || []);
    $("index-hint").hidden = true;
    $("table-wrap").hidden = false;
  } catch (e) {
    log("err", `Could not load index: ${esc(e.message)}`);
  }
}

function renderBlocks(flashIds) {
  const tbody = $("blocks-body");
  tbody.innerHTML = "";
  const staged = new Set(state.mutations.map((m) => m.targetId));
  for (const block of state.blocks) {
    const tr = document.createElement("tr");
    if (staged.has(block.target_id)) tr.classList.add("staged");
    if (flashIds.includes(block.target_id)) tr.classList.add("flash-changed");
    const typeTag = block.type === "table_cell"
      ? `<span class="tag cell">cell ${block.row},${block.col}</span>`
      : `<span class="tag">para</span>`;
    const text = block.text
      ? `<span class="block-text">${esc(block.text)}</span>`
      : `<span class="block-text empty">(empty)</span>`;
    tr.innerHTML =
      `<td class="num">${block.ordinal}</td>` +
      `<td><span class="tid">${esc(block.target_id)}</span></td>` +
      `<td>${typeTag}</td>` +
      `<td><span class="tag">${esc(block.style)}</span></td>` +
      `<td class="num">${block.run_count}</td>` +
      `<td class="num">${block.char_count}</td>` +
      `<td>${text}</td>`;
    tr.title = "Click to stage a modify mutation for this block";
    tr.onclick = () => stageMutation(block);
    tbody.appendChild(tr);
  }
}

/* ============ mutation composer ============ */
function stageMutation(block) {
  if (state.mutations.some((m) => m.targetId === block.target_id)) {
    log("err", `<code>${esc(block.target_id)}</code> is already staged — one mutation per block per batch.`);
    return;
  }
  if (!block.text) {
    log("err", `<code>${esc(block.target_id)}</code> has no text; modify needs a non-empty old_text.`);
    return;
  }
  state.mutations.push({
    targetId: block.target_id,
    oldText: block.text,
    occurrence: 0,
    newText: block.text,
  });
  renderBatch();
}

function renderBatch() {
  const wrap = $("mutation-cards");
  wrap.innerHTML = "";
  $("composer-hint").hidden = state.mutations.length > 0;
  $("btn-apply").disabled = state.mutations.length === 0;

  state.mutations.forEach((m, i) => {
    const card = document.createElement("div");
    card.className = "mutation-card";
    card.innerHTML =
      `<div class="card-head"><span class="tid">${esc(m.targetId)}</span>` +
      `<button class="btn ghost sm danger-text" data-remove="${i}">Remove</button></div>` +
      `<div class="field"><label>old_text (must match current text — the lock)</label>` +
      `<textarea data-field="oldText" data-i="${i}">${esc(m.oldText)}</textarea></div>` +
      `<div class="field-row">` +
      `<div class="field narrow"><label>occurrence</label>` +
      `<input type="number" min="0" value="${m.occurrence}" data-field="occurrence" data-i="${i}"></div>` +
      `<div class="field"><label>new_text (replacement)</label>` +
      `<textarea data-field="newText" data-i="${i}">${esc(m.newText)}</textarea></div>` +
      `</div>`;
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
    input.oninput = () => {
      const m = state.mutations[Number(input.dataset.i)];
      m[input.dataset.field] =
        input.dataset.field === "occurrence" ? Number(input.value) : input.value;
      refreshJsonPreview();
    };
  });

  renderBlocks([]);
  refreshJsonPreview();
}

function buildBatch() {
  return {
    schema_version: 1,
    explanation: $("explanation").value || undefined,
    mutations: state.mutations.map((m) => ({
      op: "modify",
      target_id: m.targetId,
      old_text: m.oldText,
      occurrence: m.occurrence,
      new_text: m.newText,
    })),
  };
}

function refreshJsonPreview() {
  if (!$("json-preview").hidden) {
    $("json-preview").textContent = JSON.stringify(buildBatch(), null, 2);
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
    const body = await res.json();

    if (res.ok) {
      const ids = body.changed_ids || [];
      log("ok",
        `Applied ${body.applied_count} mutation(s). Changed: ` +
        ids.map((id) => `<code>${esc(id)}</code>`).join(", "));
      state.mutations = [];
      $("explanation").value = "";
      renderBatch();
      await loadIndex(ids);
    } else if (body.details) {
      const items = body.details
        .map((d) => `<li>[#${d.mutation_index}] <b>${esc(d.code)}</b> — ${esc(d.message)}</li>`)
        .join("");
      log("err", `Validation failed (HTTP ${res.status}):<ul>${items}</ul>Document not modified.`);
    } else {
      log("err",
        `Rejected (HTTP ${res.status}): ${esc(body.error || body.message || "unknown error")}. ` +
        `Batch rolled back — document unchanged.`);
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
$("btn-refresh-index").onclick = () => loadIndex();
$("btn-clear-batch").onclick = () => { state.mutations = []; renderBatch(); };
$("btn-apply").onclick = applyBatch;
$("btn-clear-log").onclick = () => { $("log").innerHTML = ""; };
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
