import { useState, useEffect, useRef, useCallback } from 'react';
import { registerLicense } from '@syncfusion/ej2-base';
import {
  DocumentEditorContainerComponent,
  Toolbar,
} from '@syncfusion/ej2-react-documenteditor';

registerLicense(import.meta.env.VITE_SYNCFUSION_LICENSE || '');
DocumentEditorContainerComponent.Inject(Toolbar);

// Syncfusion demo service (v31+ URL).
// All Syncfusion conversion calls (import, clipboard, spell-check) stay in
// the frontend — the Python backend is only used for document storage and AI.
const SYNCFUSION_SERVICE = 'https://document.syncfusion.com/web-services/docx-editor/api/documenteditor/';

const TAB = { PREVIEW: 'preview', EDITOR: 'editor' };

export default function DocumentPanel({ apiBase, editorRef, selectedDoc, onDocSelect, previewVersion, onSelectionChange }) {
  const [documents, setDocuments]   = useState([]);
  const [activeTab, setActiveTab]   = useState(TAB.PREVIEW);
  const [newDocName, setNewDocName] = useState('');
  const [showNewInput, setShowNewInput] = useState(false);
  const [uploading, setUploading]   = useState(false);
  const [editorLoading, setEditorLoading] = useState(false);
  const [editorError, setEditorError]   = useState(null);
  const [saving, setSaving]             = useState(false);
  const fileInputRef = useRef(null);

  // ── Fetch document list ───────────────────────────────────────────────────
  const refreshDocs = () => {
    fetch(`${apiBase}/api/documents`)
      .then((r) => r.json())
      .then((d) => setDocuments(d.documents || []))
      .catch(() => {});
  };

  useEffect(() => { refreshDocs(); }, []);

  // ── Load the selected document into the Syncfusion Rich Editor ───────────
  // Flow: fetch .docx from Python → POST to Syncfusion import service → open SFDT
  const loadDocumentInEditor = useCallback(async (docName) => {
    if (!docName || !editorRef.current) return;
    setEditorLoading(true);
    setEditorError(null);
    try {
      // 1. Fetch the .docx file from our Python backend.
      const docRes = await fetch(
        `${apiBase}/api/documents/${encodeURIComponent(docName)}/download`
      );
      if (!docRes.ok) throw new Error(`Could not fetch document (${docRes.status})`);
      const blob = await docRes.blob();

      // 2. Send the .docx to Syncfusion's service to convert to SFDT JSON.
      //    This is a purely frontend-to-Syncfusion call; the Python backend is
      //    not involved.
      const formData = new FormData();
      formData.append('files', blob, docName);
      const importRes = await fetch(`${SYNCFUSION_SERVICE}Import`, {
        method: 'POST',
        body: formData,
      });
      if (!importRes.ok) throw new Error(`Syncfusion import failed (${importRes.status})`);
      const sfdt = await importRes.json();

      // 3. Open the SFDT in the Syncfusion editor.
      editorRef.current.documentEditor.open(JSON.stringify(sfdt));
    } catch (err) {
      setEditorError(err.message);
    } finally {
      setEditorLoading(false);
    }
  }, [apiBase, editorRef]);

  // Auto-load when switching to the Rich Editor tab or when the doc/previewVersion changes.
  useEffect(() => {
    if (activeTab === TAB.EDITOR && selectedDoc) {
      loadDocumentInEditor(selectedDoc);
    }
  }, [activeTab, selectedDoc, previewVersion]);

  // ── Upload .docx ──────────────────────────────────────────────────────────
  const handleUpload = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await fetch(`${apiBase}/api/documents/upload`, { method: 'POST', body: fd });
      if (!res.ok) throw new Error((await res.json()).detail || 'Upload failed');
      const { saved_as } = await res.json();
      refreshDocs();
      onDocSelect(saved_as);
      setActiveTab(TAB.PREVIEW);
    } catch (err) {
      alert(`Upload failed: ${err.message}`);
    } finally {
      setUploading(false);
      e.target.value = '';
    }
  };

  // ── Create empty doc ──────────────────────────────────────────────────────
  const handleCreate = async () => {
    const name = newDocName.trim() || 'new-document';
    try {
      const res = await fetch(`${apiBase}/api/documents`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      });
      if (!res.ok) throw new Error((await res.json()).detail || 'Create failed');
      const { name: saved } = await res.json();
      refreshDocs();
      onDocSelect(saved);
      setActiveTab(TAB.PREVIEW);
    } catch (err) {
      alert(`Create failed: ${err.message}`);
    } finally {
      setShowNewInput(false);
      setNewDocName('');
    }
  };

  const previewUrl = selectedDoc
    ? `${apiBase}/api/documents/${encodeURIComponent(selectedDoc)}/preview?v=${previewVersion}`
    : null;

  const downloadUrl = selectedDoc
    ? `${apiBase}/api/documents/${encodeURIComponent(selectedDoc)}/download`
    : null;

  return (
    <div style={s.pane}>
      {/* ── Top toolbar ── */}
      <div style={s.toolbar}>
        {/* Document selector */}
        <select
          value={selectedDoc || ''}
          onChange={(e) => { onDocSelect(e.target.value || null); setActiveTab(TAB.PREVIEW); }}
          style={s.select}
        >
          <option value="">— select a document —</option>
          {documents.map((d) => (
            <option key={d} value={d}>{d}</option>
          ))}
        </select>

        {/* Upload */}
        <button
          style={s.btn('#2b579a')}
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
          title="Upload a .docx file"
        >
          {uploading ? 'Uploading…' : '📂 Upload'}
        </button>
        <input
          type="file"
          accept=".docx"
          ref={fileInputRef}
          style={{ display: 'none' }}
          onChange={handleUpload}
        />

        {/* New document */}
        {showNewInput ? (
          <span style={{ display: 'flex', gap: 4 }}>
            <input
              value={newDocName}
              onChange={(e) => setNewDocName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              placeholder="filename.docx"
              style={s.newInput}
              autoFocus
            />
            <button style={s.btn('#2e7d32')} onClick={handleCreate}>✓</button>
            <button style={s.btn('#888')} onClick={() => setShowNewInput(false)}>✕</button>
          </span>
        ) : (
          <button style={s.btn('#555')} onClick={() => setShowNewInput(true)} title="Create empty document">
            ＋ New
          </button>
        )}

        {/* Download */}
        {downloadUrl && (
          <a href={downloadUrl} download={selectedDoc} style={s.btn('#1565c0', true)}>
            ⬇ Download
          </a>
        )}

        {/* Save Rich Editor → Python server */}
        {selectedDoc && activeTab === TAB.EDITOR && (
          <button
            style={s.btn(saving ? '#888' : '#2e7d32')}
            disabled={saving}
            title="Export SFDT from Syncfusion and save as .docx on the server"
            onClick={async () => {
              if (!editorRef.current) return;
              setSaving(true);
              try {
                const sfdt = editorRef.current.documentEditor.serialize();
                const res = await fetch(`${apiBase}/api/documents/save-sfdt`, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ name: selectedDoc, sfdt }),
                });
                if (!res.ok) throw new Error((await res.json()).detail || 'Save failed');
                alert(`✅ Saved "${selectedDoc}" to server.`);
              } catch (err) {
                alert(`Save failed: ${err.message}`);
              } finally {
                setSaving(false);
              }
            }}
          >
            {saving ? 'Saving…' : '💾 Save to Server'}
          </button>
        )}
      </div>

      {/* ── Tab bar ── */}
      <div style={s.tabBar}>
        {[
          { key: TAB.PREVIEW, label: '🔍 Live Preview' },
          { key: TAB.EDITOR,  label: '✏️ Rich Editor'  },
        ].map(({ key, label }) => (
          <button
            key={key}
            onClick={() => setActiveTab(key)}
            style={s.tab(activeTab === key)}
          >
            {label}
          </button>
        ))}
        {selectedDoc && (
          <span style={s.docName}>{selectedDoc}</span>
        )}
      </div>

      {/* ── Content ── */}
      <div style={s.content}>
        {/* Live Preview — iframe pointing at Python-rendered mammoth HTML */}
        <div style={{ ...s.fill, display: activeTab === TAB.PREVIEW ? 'flex' : 'none', flexDirection: 'column' }}>
          {previewUrl ? (
            <iframe
              key={previewVersion}         // forces a reload when the doc changes
              src={previewUrl}
              style={s.iframe}
              title="Document preview"
            />
          ) : (
            <div style={s.empty}>
              Select or upload a document to see a live preview here.<br />
              <span style={{ fontSize: 13, color: '#aaa' }}>
                The preview refreshes automatically after every AI edit.
              </span>
            </div>
          )}
        </div>

        {/* Rich Editor — Syncfusion (only mounted when tab is active to prevent
            toolbar dropdowns from rendering into <body> and breaking the layout) */}
        {activeTab === TAB.EDITOR && (
          <div style={{ ...s.fill, display: 'flex', flexDirection: 'column' }}>
            {/* Status bar */}
            {editorLoading && (
              <div style={s.editorStatus('info')}>
                ⏳ Loading document into editor…
              </div>
            )}
            {editorError && (
              <div style={s.editorStatus('error')}>
                ⚠️ {editorError} —{' '}
                <button onClick={() => loadDocumentInEditor(selectedDoc)} style={s.retryBtn}>
                  Retry
                </button>
                {' '}or use the toolbar <strong>Open</strong> button.
              </div>
            )}
            {!editorLoading && !editorError && selectedDoc && (
              <div style={s.editorStatus('ok')}>
                ✅ Showing <strong>{selectedDoc}</strong> — refreshes automatically after AI edits.
              </div>
            )}
            {!selectedDoc && (
              <div style={s.editorStatus('info')}>
                Select a document from the dropdown to load it here.
              </div>
            )}
            <div style={{ flex: 1, overflow: 'hidden', minHeight: 0 }}>
              <DocumentEditorContainerComponent
                ref={editorRef}
                serviceUrl={SYNCFUSION_SERVICE}
                height="100%"
                enableToolbar={true}
                showPropertiesPane={false}
                selectionChange={() => {
                  if (!onSelectionChange || !editorRef.current) return;
                  try {
                    const raw = editorRef.current.documentEditor.selection.text || '';
                    // Syncfusion uses \r as paragraph separator — normalise to \n
                    // so the AI receives clean line breaks and context search works.
                    const text = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
                    onSelectionChange(text);
                  } catch (_) {}
                }}
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────
const s = {
  pane: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
    background: '#f3f3f3',
    minWidth: 0,
  },
  toolbar: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '8px 12px',
    background: '#fff',
    borderBottom: '1px solid #ddd',
    flexShrink: 0,
    flexWrap: 'wrap',
  },
  select: {
    padding: '5px 8px',
    borderRadius: 6,
    border: '1px solid #ccc',
    fontSize: 13,
    minWidth: 180,
  },
  btn: (bg, isAnchor = false) => ({
    display: 'inline-flex',
    alignItems: 'center',
    padding: '5px 11px',
    background: bg,
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    fontSize: 13,
    fontWeight: 600,
    cursor: 'pointer',
    textDecoration: 'none',
    whiteSpace: 'nowrap',
  }),
  newInput: {
    padding: '4px 8px',
    borderRadius: 6,
    border: '1px solid #ccc',
    fontSize: 13,
    width: 140,
  },
  tabBar: {
    display: 'flex',
    alignItems: 'center',
    gap: 0,
    background: '#fff',
    borderBottom: '2px solid #e0e0e0',
    flexShrink: 0,
    padding: '0 12px',
  },
  tab: (active) => ({
    padding: '9px 18px',
    border: 'none',
    borderBottom: active ? '2px solid #2b579a' : '2px solid transparent',
    background: 'none',
    color: active ? '#2b579a' : '#666',
    fontWeight: active ? 700 : 400,
    fontSize: 13,
    cursor: 'pointer',
    marginBottom: -2,
  }),
  docName: {
    marginLeft: 'auto',
    fontSize: 12,
    color: '#999',
    fontStyle: 'italic',
    paddingRight: 4,
  },
  content: {
    flex: 1,
    overflow: 'hidden',
    position: 'relative',
  },
  fill: {
    position: 'absolute',
    inset: 0,
    overflow: 'hidden',
  },
  iframe: {
    width: '100%',
    height: '100%',
    border: 'none',
    background: '#f3f3f3',
  },
  editorStatus: (type) => ({
    padding: '6px 14px',
    background: type === 'error' ? '#fff3f3' : type === 'ok' ? '#f0fff4' : '#fff8e1',
    fontSize: 12,
    color: type === 'error' ? '#c62828' : type === 'ok' ? '#2e7d32' : '#666',
    borderBottom: `1px solid ${type === 'error' ? '#ffcdd2' : type === 'ok' ? '#c8e6c9' : '#ffe082'}`,
    flexShrink: 0,
  }),
  retryBtn: {
    background: 'none',
    border: 'none',
    color: '#1565c0',
    cursor: 'pointer',
    textDecoration: 'underline',
    fontSize: 12,
    padding: 0,
  },
  empty: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    color: '#aaa',
    fontSize: 15,
    textAlign: 'center',
    gap: 10,
    padding: 40,
  },
};
