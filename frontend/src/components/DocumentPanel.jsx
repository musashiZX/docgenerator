import { useState, useEffect, useRef, useCallback } from 'react';
import { apiFetch, apiGetJson, apiPostJson } from '../utils/apiClient';

function docName(entry) {
  return typeof entry === 'string' ? entry : entry?.name;
}

export default function DocumentPanel({
  apiBase,
  selectedDoc,
  onDocSelect,
  previewVersion,
  onPreviewRefresh,
  onSelectionChange,
}) {
  const [documents, setDocuments] = useState([]);
  const [newDocName, setNewDocName] = useState('');
  const [showNewInput, setShowNewInput] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [uploadNote, setUploadNote] = useState('');
  const fileInputRef = useRef(null);

  const refreshDocs = useCallback(() => {
    apiGetJson(apiBase, '/api/documents')
      .then((d) => setDocuments(d?.documents || []))
      .catch(() => {});
  }, [apiBase]);

  useEffect(() => { refreshDocs(); }, [refreshDocs]);

  useEffect(() => {
    const onMessage = (event) => {
      if (event.data?.type !== 'docgen-selection') return;
      const text = (event.data.text || '').trim();
      if (text) onSelectionChange?.(text);
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [onSelectionChange]);

  const uploadFile = async (file) => {
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.docx')) {
      alert('Please upload a .docx Word document.');
      return;
    }

    setUploading(true);
    setUploadNote('');
    try {
      const fd = new FormData();
      fd.append('file', file);
      const { res, data } = await apiFetch(`${apiBase}/api/documents/upload`, {
        method: 'POST',
        body: fd,
      });
      if (!res.ok) throw new Error(data?.detail || 'Upload failed');

      const savedAs = data.saved_as;
      refreshDocs();
      onDocSelect(savedAs);
      onPreviewRefresh?.();

      if (data.renamed) {
        setUploadNote(`Saved as "${savedAs}" (original name was already in use).`);
      } else {
        setUploadNote(`Uploaded "${savedAs}".`);
      }
    } catch (err) {
      alert(`Upload failed: ${err.message}`);
    } finally {
      setUploading(false);
    }
  };

  const handleUpload = async (e) => {
    const file = e.target.files?.[0];
    await uploadFile(file);
    e.target.value = '';
  };

  const handleDrop = async (e) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    await uploadFile(file);
  };

  const handleCreate = async () => {
    const name = newDocName.trim() || 'new-document';
    try {
      const { data } = await apiPostJson(apiBase, '/api/documents', { name });
      refreshDocs();
      onDocSelect(data.name);
      onPreviewRefresh?.();
      setUploadNote(`Created "${data.name}".`);
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
      <div style={s.toolbar}>
        <div style={s.toolbarLeft}>
          <span style={s.sectionLabel}>Document</span>
          <select
            value={selectedDoc || ''}
            onChange={(e) => {
              onDocSelect(e.target.value || null);
              onPreviewRefresh?.();
            }}
            style={s.select}
          >
            <option value="">Select a document…</option>
            {documents.map((d) => {
              const name = docName(d);
              return (
                <option key={name} value={name}>{name}</option>
              );
            })}
          </select>
        </div>

        <div style={s.toolbarActions}>
          <button
            type="button"
            style={s.btn}
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
          >
            {uploading ? 'Uploading…' : 'Upload .docx'}
          </button>
          <input
            type="file"
            accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ref={fileInputRef}
            style={{ display: 'none' }}
            onChange={handleUpload}
          />

          {showNewInput ? (
            <span style={s.newRow}>
              <input
                value={newDocName}
                onChange={(e) => setNewDocName(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
                placeholder="filename.docx"
                style={s.newInput}
                autoFocus
              />
              <button type="button" style={s.btnPrimary} onClick={handleCreate}>Create</button>
              <button type="button" style={s.btnGhost} onClick={() => setShowNewInput(false)}>Cancel</button>
            </span>
          ) : (
            <button type="button" style={s.btn} onClick={() => setShowNewInput(true)}>
              New
            </button>
          )}

          {downloadUrl && (
            <a href={downloadUrl} download={selectedDoc} style={s.btnLink}>
              Download
            </a>
          )}
        </div>
      </div>

      {uploadNote && (
        <div style={s.uploadNote}>{uploadNote}</div>
      )}

      <div
        style={s.previewArea(dragOver)}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
      >
        {previewUrl ? (
          <iframe
            key={`${selectedDoc}-${previewVersion}`}
            src={previewUrl}
            style={s.iframe}
            title="Document preview"
          />
        ) : (
          <div style={s.empty}>
            <p style={s.emptyTitle}>Upload a Word document</p>
            <p style={s.emptyHint}>
              Drop a .docx file here or use Upload. Files are stored on the server in the document folder.
            </p>
            <button
              type="button"
              style={s.btnPrimaryLarge}
              onClick={() => fileInputRef.current?.click()}
              disabled={uploading}
            >
              {uploading ? 'Uploading…' : 'Choose .docx file'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

const s = {
  pane: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    minWidth: 0,
    minHeight: 0,
    background: '#e8eaed',
  },
  toolbar: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    padding: '10px 16px',
    background: '#fff',
    borderBottom: '1px solid #dadce0',
    flexShrink: 0,
    flexWrap: 'wrap',
  },
  toolbarLeft: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    minWidth: 0,
    flex: '1 1 240px',
  },
  toolbarActions: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    flexWrap: 'wrap',
  },
  uploadNote: {
    padding: '8px 16px',
    fontSize: 12,
    color: '#1a73e8',
    background: '#e8f0fe',
    borderBottom: '1px solid #d2e3fc',
    flexShrink: 0,
  },
  sectionLabel: {
    fontSize: 12,
    fontWeight: 600,
    color: '#5f6368',
    whiteSpace: 'nowrap',
  },
  select: {
    flex: 1,
    minWidth: 160,
    maxWidth: 420,
    padding: '7px 10px',
    borderRadius: 6,
    border: '1px solid #dadce0',
    fontSize: 13,
    background: '#fff',
    color: '#202124',
  },
  btn: {
    padding: '7px 14px',
    background: '#fff',
    color: '#1a73e8',
    border: '1px solid #dadce0',
    borderRadius: 6,
    fontSize: 13,
    fontWeight: 500,
    cursor: 'pointer',
    whiteSpace: 'nowrap',
  },
  btnPrimary: {
    padding: '7px 14px',
    background: '#1a73e8',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    fontSize: 13,
    fontWeight: 500,
    cursor: 'pointer',
  },
  btnPrimaryLarge: {
    marginTop: 16,
    padding: '10px 20px',
    background: '#1a73e8',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    fontSize: 14,
    fontWeight: 600,
    cursor: 'pointer',
  },
  btnGhost: {
    padding: '7px 10px',
    background: 'transparent',
    color: '#5f6368',
    border: 'none',
    borderRadius: 6,
    fontSize: 13,
    cursor: 'pointer',
  },
  btnLink: {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '7px 14px',
    background: '#fff',
    color: '#1a73e8',
    border: '1px solid #dadce0',
    borderRadius: 6,
    fontSize: 13,
    fontWeight: 500,
    textDecoration: 'none',
    whiteSpace: 'nowrap',
  },
  newRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
  },
  newInput: {
    padding: '6px 10px',
    borderRadius: 6,
    border: '1px solid #dadce0',
    fontSize: 13,
    width: 150,
  },
  previewArea: (dragOver) => ({
    flex: 1,
    minHeight: 0,
    display: 'flex',
    flexDirection: 'column',
    padding: 12,
    outline: dragOver ? '2px dashed #1a73e8' : 'none',
    outlineOffset: -8,
    background: dragOver ? '#e8f0fe' : 'transparent',
    transition: 'background 0.15s',
  }),
  iframe: {
    flex: 1,
    width: '100%',
    minHeight: 0,
    border: 'none',
    borderRadius: 8,
    background: '#fff',
    boxShadow: '0 1px 3px rgba(60,64,67,0.15)',
  },
  empty: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    textAlign: 'center',
    padding: 40,
    background: '#fff',
    borderRadius: 8,
    boxShadow: '0 1px 3px rgba(60,64,67,0.15)',
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: 600,
    color: '#3c4043',
    marginBottom: 8,
  },
  emptyHint: {
    fontSize: 14,
    color: '#80868b',
    lineHeight: 1.5,
    maxWidth: 400,
  },
};
