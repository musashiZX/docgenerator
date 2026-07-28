"use strict";

/** Standalone history page — thin shell around RecoveryUI for startup embed / separate route. */

let currentDoc = null;
let recoveryPanel = null;

const $ = (id) => document.getElementById(id);

function log(kind, html) {
  console.log(`[${kind}]`, html.replace(/<[^>]+>/g, ""));
}

async function checkHealth() {
  try {
    const res = await fetch("/api/health");
    $("health-dot").className = `dot ${res.ok ? "up" : "down"}`;
    $("health-text").textContent = res.ok ? "server up" : `error ${res.status}`;
  } catch {
    $("health-dot").className = "dot down";
    $("health-text").textContent = "unreachable";
  }
}

async function loadDocs() {
  const res = await fetch("/api/documents");
  const docs = await res.json();
  const list = $("doc-list");
  list.innerHTML = "";
  if (docs.length === 0) {
    list.innerHTML = `<li class="empty">No documents in <code>docs/</code>.</li>`;
    return;
  }
  for (const doc of docs) {
    const li = document.createElement("li");
    li.className = doc.name === currentDoc ? "active" : "";
    li.innerHTML =
      `<span class="doc-name">${RecoveryUI.esc(doc.name)}</span>` +
      `<span class="doc-meta">${(doc.size / 1024).toFixed(1)} KB</span>`;
    li.onclick = () => selectDoc(doc.name);
    list.appendChild(li);
  }
}

function selectDoc(name) {
  currentDoc = name;
  $("doc-title").textContent = name;
  const params = new URLSearchParams(window.location.search);
  params.set("doc", name);
  window.history.replaceState({}, "", `history.html?${params}`);
  loadDocs();
  mountRecovery();
}

function mountRecovery() {
  if (recoveryPanel) recoveryPanel.destroy();
  recoveryPanel = RecoveryUI.mount($("history-root"), {
    getDocName: () => currentDoc,
    log: (kind, msg) => log(kind, msg),
    onRestored: async () => log("ok", "Document restored — refresh console if open."),
    onCommitted: async () => {},
  });
}

function initFromQuery() {
  const doc = new URLSearchParams(window.location.search).get("doc");
  if (doc) selectDoc(doc);
}

$("btn-refresh-docs").onclick = () => {
  loadDocs();
  recoveryPanel?.refresh();
};

checkHealth();
loadDocs().then(initFromQuery);
