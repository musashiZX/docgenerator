// Manual Editing surface: a self-hosted OnlyOffice editor, its own tab
// (word-wrap), deliberately separate from AI Editing (the diff view) — see
// setView in app.js. They're not meant to be used at the same time, so this
// module only has two real operations: open fresh (always current content,
// no staleness possible) and close-with-save (forcesave, waited-for, before
// tearing down — see docs/ONLYOFFICE.md for why an in-place refresh isn't
// used: the Document Server's refreshFile() is documented to update a live
// session without a reload, but doesn't actually update the rendered page
// in this environment; a fresh open every time is what's reliable).
window.OnlyOfficeEditor = (() => {
  let apiScriptPromise = null;
  let currentEditor = null;
  let currentDocName = null;
  let currentKey = null;

  function loadApiScript(documentServerUrl) {
    if (window.DocsAPI) return Promise.resolve();
    if (apiScriptPromise) return apiScriptPromise;
    apiScriptPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = `${documentServerUrl}/web-apps/apps/api/documents/api.js`;
      script.onload = resolve;
      script.onerror = () => reject(new Error("Could not load the OnlyOffice Document Server API script."));
      document.head.appendChild(script);
    });
    return apiScriptPromise;
  }

  function setStatus(text, cls) {
    const el = document.getElementById("onlyoffice-inline-status");
    if (!el) return;
    if (!text) { el.hidden = true; return; }
    el.hidden = false;
    el.textContent = text;
    el.className = "onlyoffice-inline-status" + (cls ? ` ${cls}` : "");
  }

  function isOpen() {
    return currentEditor !== null;
  }

  async function open(docName, opts) {
    opts = opts || {};
    const log = opts.log || (() => {});
    const container = document.getElementById("onlyoffice-editor-container");
    container.innerHTML = "";
    setStatus(null);

    let payload;
    try {
      const res = await fetch(`/api/onlyoffice/editor-config/${encodeURIComponent(docName)}`);
      if (!res.ok) throw new Error(`editor-config failed: ${res.status}`);
      payload = await res.json();
    } catch (e) {
      log("error", `Manual Editing: ${e.message}. Is the OnlyOffice Document Server running (docker compose -f docker/onlyoffice-compose.yml up -d)?`);
      setStatus("Could not load the document editor — see Activity log.", "error");
      return false;
    }

    try {
      await loadApiScript(payload.documentServerUrl);
    } catch (e) {
      log("error", `Manual Editing: ${e.message}`);
      setStatus("Could not load the document editor — see Activity log.", "error");
      return false;
    }

    currentDocName = docName;
    currentKey = payload.config.document.key;

    const config = Object.assign({}, payload.config, {
      events: {
        onAppReady: () => log("info", "Manual Editing: editor ready."),
        onError: (e) => log("error", `Manual Editing: ${JSON.stringify(e && e.data)}`),
      },
    });
    currentEditor = new DocsAPI.DocEditor("onlyoffice-editor-container", config);
    return true;
  }

  /**
   * Saves (waiting for the callback to actually land) and destroys the
   * editor. Always resolves — never throws — so callers (switching
   * document, switching view) can await it unconditionally; a failed/timed
   * -out save is reported via `log`, not thrown.
   */
  async function closeAndSave(opts) {
    opts = opts || {};
    const log = opts.log || (() => {});
    if (!isOpen()) return true;

    const docName = currentDocName;
    const key = currentKey;
    let ok = true;

    setStatus("Saving…", "saving");
    try {
      const res = await fetch(`/api/onlyoffice/forcesave/${encodeURIComponent(docName)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ key }),
      });
      const body = await res.json().catch(() => ({}));
      if (body.status === "saved" || body.status === "no_changes") {
        log("ok", "Manual Editing: saved.");
      } else if (body.status === "timeout") {
        ok = false;
        log("error", "Manual Editing: save timed out — the Document Server may not have reached this app. Check docs/ONLYOFFICE.md.");
      } else {
        ok = false;
        log("error", `Manual Editing: save failed (${body.detail || body.status || res.status}).`);
      }
    } catch (e) {
      ok = false;
      log("error", `Manual Editing: save request failed: ${e.message}`);
    }

    if (currentEditor && currentEditor.destroyEditor) {
      currentEditor.destroyEditor();
    }
    currentEditor = null;
    currentDocName = null;
    currentKey = null;
    setStatus(null);

    if (opts.onSaved) await opts.onSaved(ok);
    return ok;
  }

  return { open, closeAndSave, isOpen };
})();

window.addEventListener("beforeunload", (e) => {
  if (window.OnlyOfficeEditor && OnlyOfficeEditor.isOpen()) {
    e.preventDefault();
    e.returnValue = "";
  }
});
