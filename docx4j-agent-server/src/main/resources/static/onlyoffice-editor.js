// Manual "Edit in Word" surface: embeds the self-hosted OnlyOffice Document
// Server as a second editing mode alongside the AI chat/propose flow. Both
// write to the same .docx (existing "last save wins" convention). Saves are
// persisted server-side by the app's own /api/onlyoffice/callback endpoint,
// not by this script — closing the editor just refreshes the app's view.
window.OnlyOfficeEditor = (() => {
  let apiScriptPromise = null;
  let currentEditor = null;

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

  function close(opts) {
    if (currentEditor && currentEditor.destroyEditor) {
      currentEditor.destroyEditor();
    }
    currentEditor = null;
    document.getElementById("onlyoffice-overlay").hidden = true;
    if (opts && opts.onSaved) opts.onSaved();
  }

  async function open(docName, opts) {
    opts = opts || {};
    const log = opts.log || (() => {});
    const overlay = document.getElementById("onlyoffice-overlay");
    const title = document.getElementById("onlyoffice-overlay-title");
    const container = document.getElementById("onlyoffice-editor-container");
    const closeBtn = document.getElementById("btn-onlyoffice-close");

    title.textContent = `Edit in Word — ${docName}`;
    container.innerHTML = "";
    overlay.hidden = false;
    closeBtn.onclick = () => close(opts);

    let payload;
    try {
      const res = await fetch(`/api/onlyoffice/editor-config/${encodeURIComponent(docName)}`);
      if (!res.ok) throw new Error(`editor-config failed: ${res.status}`);
      payload = await res.json();
    } catch (e) {
      log("error", `Edit in Word: ${e.message}. Is the OnlyOffice Document Server running?`);
      overlay.hidden = true;
      return;
    }

    try {
      await loadApiScript(payload.documentServerUrl);
    } catch (e) {
      log("error", `Edit in Word: ${e.message}`);
      overlay.hidden = true;
      return;
    }

    const config = Object.assign({}, payload.config, {
      events: {
        onAppReady: () => log("info", "Edit in Word: editor ready."),
        onError: (e) => log("error", `Edit in Word: ${JSON.stringify(e && e.data)}`),
      },
    });
    currentEditor = new DocsAPI.DocEditor("onlyoffice-editor-container", config);
  }

  return { open, close };
})();
