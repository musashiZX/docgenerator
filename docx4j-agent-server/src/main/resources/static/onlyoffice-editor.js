// Manual "Edit in Word" surface: swaps the read-only preview for a live,
// self-hosted OnlyOffice editor in place — same panel, same Commit/History
// topbar, not a separate page. Both surfaces write to the same .docx (the
// project's existing "last save wins" convention).
//
// The Document Server's own save/close behavior isn't reliable embedded in
// an iframe, so closing this editor always goes through an explicit
// forcesave-and-wait round trip to the backend (see
// OnlyOfficeForceSaveController) rather than trusting an implicit
// disconnect-triggered save.
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
    const inline = document.getElementById("onlyoffice-inline");
    const frame = document.getElementById("preview-frame");
    const container = document.getElementById("onlyoffice-editor-container");

    container.innerHTML = "";
    setStatus(null);
    inline.hidden = false;
    if (frame) frame.style.visibility = "hidden";

    let payload;
    try {
      const res = await fetch(`/api/onlyoffice/editor-config/${encodeURIComponent(docName)}`);
      if (!res.ok) throw new Error(`editor-config failed: ${res.status}`);
      payload = await res.json();
    } catch (e) {
      log("error", `Edit in Word: ${e.message}. Is the OnlyOffice Document Server running (docker compose -f docker/onlyoffice-compose.yml up -d)?`);
      inline.hidden = true;
      if (frame) frame.style.visibility = "";
      return false;
    }

    try {
      await loadApiScript(payload.documentServerUrl);
    } catch (e) {
      log("error", `Edit in Word: ${e.message}`);
      inline.hidden = true;
      if (frame) frame.style.visibility = "";
      return false;
    }

    currentDocName = docName;
    currentKey = payload.config.document.key;

    const config = Object.assign({}, payload.config, {
      events: {
        onAppReady: () => log("info", "Edit in Word: editor ready."),
        onError: (e) => log("error", `Edit in Word: ${JSON.stringify(e && e.data)}`),
      },
    });
    currentEditor = new DocsAPI.DocEditor("onlyoffice-editor-container", config);
    return true;
  }

  /**
   * Saves (waiting for the callback to actually land), destroys the editor,
   * and switches back to the read-only preview. Always resolves — never
   * throws — so callers (switching document, switching view) can await it
   * unconditionally; a failed/timed-out save is reported via `log` and
   * surfaced in the status strip, not thrown.
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
        log("ok", "Edit in Word: saved.");
      } else if (body.status === "timeout") {
        ok = false;
        log("error", "Edit in Word: save timed out — the Document Server may not have reached this app. Check docs/ONLYOFFICE.md.");
      } else {
        ok = false;
        log("error", `Edit in Word: save failed (${body.detail || body.status || res.status}).`);
      }
    } catch (e) {
      ok = false;
      log("error", `Edit in Word: save request failed: ${e.message}`);
    }

    if (currentEditor && currentEditor.destroyEditor) {
      currentEditor.destroyEditor();
    }
    currentEditor = null;
    currentDocName = null;
    currentKey = null;

    document.getElementById("onlyoffice-inline").hidden = true;
    const frame = document.getElementById("preview-frame");
    if (frame) frame.style.visibility = "";
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
