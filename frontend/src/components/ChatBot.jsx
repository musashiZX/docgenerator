import { useState, useRef, useEffect } from 'react';

export default function ChatBot({
  messages, isLoading,
  models, selectedModel, onModelChange,
  onSend, onSelectionEdit,
  onClearHistory,
  selectedDoc,
  editMode, onEditModeChange,
  selectionText,
}) {
  const [input, setInput] = useState('');
  const [expandedTools, setExpandedTools] = useState({});
  const bottomRef = useRef(null);

  const isSelectionMode = editMode === 'selection';

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isLoading]);

  const handleSend = () => {
    const trimmed = input.trim();
    if (!trimmed || isLoading) return;
    if (isSelectionMode) {
      onSelectionEdit(trimmed);
    } else {
      onSend(trimmed);
    }
    setInput('');
  };

  const toggleTools = (i) =>
    setExpandedTools((prev) => ({ ...prev, [i]: !prev[i] }));

  return (
    <div style={s.pane}>
      {/* Header */}
      <div style={s.header}>
        <span>📝 Document AI Agent</span>
        <button onClick={onClearHistory} style={s.clearBtn} title="Clear conversation history">
          🗑 Clear
        </button>
      </div>

      {/* Model selector */}
      <div style={s.modelRow}>
        <label style={s.modelLabel}>Model</label>
        <select
          value={selectedModel}
          onChange={(e) => onModelChange(e.target.value)}
          style={s.modelSelect}
        >
          {(models.length ? models : [selectedModel]).map((m) => (
            <option key={m} value={m}>{m}</option>
          ))}
        </select>
      </div>

      {/* Mode toggle */}
      <div style={s.modeBar}>
        <button
          onClick={() => onEditModeChange('agent')}
          style={s.modeBtn(!isSelectionMode)}
          title="Agent Mode: the AI edits the whole document using python-docx tools"
        >
          Agent Mode
        </button>
        <button
          onClick={() => onEditModeChange('selection')}
          style={s.modeBtn(isSelectionMode)}
          title="Rewrite Mode: highlight text in the Rich Editor, then send a rewrite instruction"
        >
          Highlight & Rewrite
        </button>
      </div>

      {/* Selection preview (shown in Rewrite mode) */}
      {isSelectionMode && (
        <div style={s.selectionBox(!!selectionText)}>
          {selectionText ? (
            <>
              <div style={s.selectionLabel}>Selected text:</div>
              <div style={s.selectionPreview}>&ldquo;{selectionText}&rdquo;</div>
            </>
          ) : (
            <div style={s.selectionHint}>
              Switch to the <strong>Rich Editor</strong> tab and highlight text to rewrite it.
            </div>
          )}
        </div>
      )}

      {selectedDoc && (
        <div style={s.docBadge}>Editing: <strong>{selectedDoc}</strong></div>
      )}

      {/* Message list */}
      <div style={s.messageList}>
        {messages.length === 0 && (
          <p style={s.emptyHint}>
            {isSelectionMode
              ? 'Highlight text in the Rich Editor, then type a rewrite instruction here.'
              : 'Select a document on the right, then tell the agent what to edit.'}
          </p>
        )}

        {messages.map((msg, i) => (
          <div key={i} style={{ marginBottom: 14 }}>
            <div style={s.label(msg.role)}>
              {msg.role === 'user' ? 'You' : 'AI Agent'}
            </div>
            {msg.selectionPreview && (
              <div style={s.msgSelectionTag}>
                Rewriting: &ldquo;{msg.selectionPreview}&rdquo;
              </div>
            )}
            <div style={s.row(msg.role)}>
              <span style={s.bubble(msg.role)}>{msg.text}</span>
            </div>

            {/* Tool calls — collapsible */}
            {msg.toolCalls?.length > 0 && (
              <div style={{ marginTop: 4, paddingLeft: msg.role === 'ai' ? 0 : undefined }}>
                <button
                  onClick={() => toggleTools(i)}
                  style={s.toolToggle}
                >
                  🔧 {msg.toolCalls.length} tool call{msg.toolCalls.length > 1 ? 's' : ''}
                  {expandedTools[i] ? ' ▲' : ' ▼'}
                </button>
                {expandedTools[i] && (
                  <div style={s.toolList}>
                    {msg.toolCalls.map((tc, j) => (
                      <div key={j} style={s.toolItem}>
                        <strong>{tc.name}</strong>
                        <pre style={s.toolPre}>
                          {JSON.stringify(tc.args, null, 2)}
                          {'\n→ '}{tc.result}
                        </pre>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        ))}

        {isLoading && (
          <div>
            <div style={s.label('ai')}>AI Agent</div>
            <span style={s.typing}>Thinking…</span>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* Input area */}
      <div style={s.inputArea}>
        <textarea
          rows={2}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); } }}
          placeholder={
            isSelectionMode
              ? 'Describe how to rewrite the selection… (Enter to send)'
              : 'Tell the agent what to edit… (Enter to send)'
          }
          style={s.textarea}
          disabled={isLoading}
        />
        <button
          onClick={handleSend}
          disabled={isLoading || !input.trim()}
          style={s.sendBtn(isLoading || !input.trim())}
        >
          {isLoading ? '…' : isSelectionMode ? 'Rewrite' : 'Send'}
        </button>
      </div>
    </div>
  );
}

const s = {
  pane: {
    width: '30%',
    minWidth: 260,
    maxWidth: 420,
    display: 'flex',
    flexDirection: 'column',
    borderRight: '1px solid #ddd',
    background: '#fff',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '12px 14px',
    background: '#2b579a',
    color: '#fff',
    fontWeight: 700,
    fontSize: 14,
    flexShrink: 0,
  },
  clearBtn: {
    background: 'rgba(255,255,255,0.18)',
    border: 'none',
    color: '#fff',
    padding: '3px 9px',
    borderRadius: 5,
    cursor: 'pointer',
    fontSize: 12,
  },
  modelRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '7px 12px',
    borderBottom: '1px solid #eee',
    flexShrink: 0,
    background: '#fafafa',
  },
  modelLabel: { fontSize: 12, color: '#555', whiteSpace: 'nowrap' },
  modelSelect: {
    flex: 1,
    fontSize: 12,
    padding: '3px 6px',
    borderRadius: 5,
    border: '1px solid #ccc',
  },
  docBadge: {
    fontSize: 11,
    color: '#555',
    padding: '4px 12px',
    background: '#f0f4ff',
    borderBottom: '1px solid #dce6ff',
    flexShrink: 0,
  },
  modeBar: {
    display: 'flex',
    flexShrink: 0,
    borderBottom: '1px solid #eee',
  },
  modeBtn: (active) => ({
    flex: 1,
    padding: '7px 4px',
    border: 'none',
    borderBottom: active ? '2px solid #2b579a' : '2px solid transparent',
    background: active ? '#f0f4ff' : '#fafafa',
    color: active ? '#2b579a' : '#666',
    fontWeight: active ? 700 : 400,
    fontSize: 12,
    cursor: 'pointer',
    transition: 'all 0.15s',
  }),
  selectionBox: (hasSelection) => ({
    margin: '6px 10px',
    padding: '6px 10px',
    borderRadius: 6,
    border: `1px solid ${hasSelection ? '#2b579a' : '#ddd'}`,
    background: hasSelection ? '#f0f4ff' : '#fafafa',
    flexShrink: 0,
    fontSize: 11,
  }),
  selectionLabel: {
    color: '#2b579a',
    fontWeight: 700,
    marginBottom: 2,
    fontSize: 10,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  selectionPreview: {
    color: '#333',
    fontStyle: 'italic',
    lineHeight: 1.4,
    wordBreak: 'break-word',
    maxHeight: 60,
    overflowY: 'auto',
  },
  selectionHint: {
    color: '#999',
    lineHeight: 1.4,
  },
  msgSelectionTag: {
    fontSize: 10,
    color: '#2b579a',
    background: '#eef2ff',
    borderRadius: 4,
    padding: '2px 7px',
    marginBottom: 3,
    display: 'inline-block',
    fontStyle: 'italic',
    maxWidth: '88%',
    wordBreak: 'break-word',
  },
  messageList: {
    flex: 1,
    overflowY: 'auto',
    padding: '14px 12px',
  },
  emptyHint: {
    color: '#bbb',
    fontSize: 13,
    textAlign: 'center',
    marginTop: 50,
    lineHeight: 1.6,
  },
  label: (role) => ({
    fontSize: 10,
    color: '#aaa',
    marginBottom: 2,
    textAlign: role === 'user' ? 'right' : 'left',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  }),
  row: (role) => ({
    display: 'flex',
    justifyContent: role === 'user' ? 'flex-end' : 'flex-start',
  }),
  bubble: (role) => ({
    display: 'inline-block',
    padding: '8px 12px',
    borderRadius: role === 'user' ? '14px 14px 4px 14px' : '14px 14px 14px 4px',
    background: role === 'user' ? '#2b579a' : '#f0f2f5',
    color: role === 'user' ? '#fff' : '#1a1a1a',
    fontSize: 13,
    lineHeight: 1.5,
    maxWidth: '88%',
    wordBreak: 'break-word',
    whiteSpace: 'pre-wrap',
  }),
  typing: {
    fontSize: 13,
    color: '#aaa',
    fontStyle: 'italic',
  },
  toolToggle: {
    background: 'none',
    border: '1px solid #ddd',
    borderRadius: 5,
    fontSize: 11,
    color: '#888',
    cursor: 'pointer',
    padding: '2px 8px',
    marginTop: 3,
  },
  toolList: {
    marginTop: 4,
    background: '#f8f8f8',
    borderRadius: 6,
    border: '1px solid #eee',
    padding: '6px 8px',
    fontSize: 11,
  },
  toolItem: { marginBottom: 6 },
  toolPre: {
    margin: '2px 0 0',
    fontFamily: 'monospace',
    fontSize: 10,
    color: '#555',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
  },
  inputArea: {
    padding: '10px 10px',
    borderTop: '1px solid #eee',
    display: 'flex',
    gap: 7,
    alignItems: 'flex-end',
    background: '#fafafa',
    flexShrink: 0,
  },
  textarea: {
    flex: 1,
    resize: 'none',
    padding: '8px 10px',
    borderRadius: 8,
    border: '1px solid #ccc',
    fontSize: 13,
    fontFamily: 'inherit',
    outline: 'none',
    lineHeight: 1.45,
  },
  sendBtn: (disabled) => ({
    padding: '8px 14px',
    background: disabled ? '#b0bec5' : '#2b579a',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    cursor: disabled ? 'not-allowed' : 'pointer',
    fontWeight: 700,
    fontSize: 13,
    whiteSpace: 'nowrap',
  }),
};
