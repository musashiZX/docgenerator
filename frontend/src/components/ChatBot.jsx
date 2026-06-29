import { useState, useRef, useEffect } from 'react';
import { apiGetJson } from '../utils/apiClient';

export default function ChatBot({
  messages,
  isLoading,
  models,
  selectedModel,
  onModelChange,
  onSend,
  onClearHistory,
  onClearSelection,
  selectedDoc,
  selectionText,
  apiBase,
}) {
  const [input, setInput] = useState('');
  const [expandedTools, setExpandedTools] = useState({});
  const [expandedTraces, setExpandedTraces] = useState({});
  const [showActivity, setShowActivity] = useState(false);
  const [recentTraces, setRecentTraces] = useState([]);
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isLoading]);

  const loadRecentTraces = () => {
    if (!apiBase) return;
    apiGetJson(apiBase, '/api/traces').then((d) => {
      if (d?.traces) setRecentTraces(d.traces);
    });
  };

  useEffect(() => {
    if (showActivity) loadRecentTraces();
  }, [showActivity, apiBase, messages]);

  const handleSend = () => {
    const trimmed = input.trim();
    if (!trimmed || isLoading) return;
    onSend(trimmed);
    setInput('');
  };

  const toggleTools = (i) =>
    setExpandedTools((prev) => ({ ...prev, [i]: !prev[i] }));

  const toggleTrace = (i) =>
    setExpandedTraces((prev) => ({ ...prev, [i]: !prev[i] }));

  let backendLabel = '';
  try {
    backendLabel = apiBase ? new URL(apiBase).host : '';
  } catch {
    backendLabel = apiBase || '';
  }

  return (
    <div style={s.pane}>
      <div style={s.header}>
        <div>
          <div style={s.title}>Document Assistant</div>
          <div style={s.subtitle}>
            Java backend · {backendLabel || 'not connected'}
          </div>
        </div>
        <div style={s.headerActions}>
          <button
            type="button"
            onClick={() => setShowActivity((v) => !v)}
            style={s.clearBtn}
            title="Recent operation traces"
          >
            Activity
          </button>
          <button type="button" onClick={onClearHistory} style={s.clearBtn}>
            Clear
          </button>
        </div>
      </div>

      <div style={s.modelRow}>
        <label style={s.modelLabel} htmlFor="model-select">Model</label>
        <select
          id="model-select"
          value={selectedModel}
          onChange={(e) => onModelChange(e.target.value)}
          style={s.modelSelect}
        >
          {(models.length ? models : [selectedModel]).map((m) => (
            <option key={m} value={m}>{m}</option>
          ))}
        </select>
      </div>

      {selectedDoc && (
        <div style={s.docBadge}>
          <span style={s.docBadgeLabel}>Active</span>
          <span style={s.docBadgeName}>{selectedDoc}</span>
        </div>
      )}

      <div style={s.selectionBox(!!selectionText)}>
        <div style={s.selectionHeader}>
          <span style={s.selectionLabel}>Selected from preview</span>
          {selectionText && (
            <button type="button" onClick={onClearSelection} style={s.clearSelectionBtn}>
              Clear
            </button>
          )}
        </div>
        {selectionText ? (
          <div style={s.selectionPreview}>&ldquo;{selectionText}&rdquo;</div>
        ) : (
          <div style={s.selectionHint}>
            Highlight text in the preview on the right — it will appear here as context for your next message.
          </div>
        )}
      </div>

      {showActivity && (
        <div style={s.activityPanel}>
          <div style={s.activityTitle}>Recent traces (logs/traces/)</div>
          {recentTraces.length === 0 ? (
            <div style={s.activityEmpty}>No traces yet.</div>
          ) : (
            recentTraces.slice(0, 8).map((t) => (
              <div key={t.trace_id || t.folder} style={s.activityRow}>
                <span style={s.activityId}>{t.trace_id || '—'}</span>
                <span style={s.activityMeta}>
                  {t.kind || t.path || ''} · {t.status || '—'}
                  {t.operation_count != null ? ` · ${t.operation_count} ops` : ''}
                </span>
              </div>
            ))
          )}
        </div>
      )}

      <div style={s.messageList}>
        {messages.length === 0 && (
          <p style={s.emptyHint}>
            Upload or select a document, highlight any passage in the preview, then describe what to change.
          </p>
        )}

        {messages.map((msg, i) => (
          <div key={i} style={s.msgBlock}>
            <div style={s.label(msg.role)}>
              {msg.role === 'user' ? 'You' : 'Assistant'}
            </div>
            {msg.selectionPreview && (
              <div style={s.msgSelectionTag}>
                Referencing: &ldquo;{msg.selectionPreview}&rdquo;
              </div>
            )}
            <div style={s.row(msg.role)}>
              <span style={s.bubble(msg.role)}>{msg.text}</span>
            </div>

            {msg.roundSummary && (
              <div style={s.roundBox}>
                <button type="button" onClick={() => toggleTrace(i)} style={s.roundToggle}>
                  This round — {msg.roundSummary.change_count ?? 0} change
                  {(msg.roundSummary.change_count ?? 0) !== 1 ? 's' : ''}
                  {msg.traceId ? ` · trace ${msg.traceId}` : ''}
                  {expandedTraces[i] ? ' ▲' : ' ▼'}
                </button>
                {expandedTraces[i] ? (
                  <div style={s.roundBody}>
                    <pre style={s.roundPre}>{msg.roundSummary.summary_text}</pre>
                    {(msg.roundSummary.changes || []).map((c, j) => (
                      <div key={j} style={s.roundChange}>
                        <strong>{c.tool}</strong>: {c.description}
                        <div style={s.roundResult}>{c.result}</div>
                      </div>
                    ))}
                    {msg.roundSummary.has_snapshots && (
                      <div style={s.roundNote}>
                        Before/after snapshots saved in trace folder.
                      </div>
                    )}
                  </div>
                ) : (
                  <div style={s.roundCollapsed}>{msg.roundSummary.summary_text}</div>
                )}
              </div>
            )}

            {msg.toolCalls?.length > 0 && (
              <div style={s.toolSection}>
                <button type="button" onClick={() => toggleTools(i)} style={s.toolToggle}>
                  {msg.toolCalls.length} tool call{msg.toolCalls.length > 1 ? 's' : ''}
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
            <div style={s.label('ai')}>Assistant</div>
            <span style={s.typing}>Thinking…</span>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      <div style={s.inputArea}>
        <textarea
          rows={2}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="Describe what to change… (Enter to send)"
          style={s.textarea}
          disabled={isLoading}
        />
        <button
          type="button"
          onClick={handleSend}
          disabled={isLoading || !input.trim()}
          style={s.sendBtn(isLoading || !input.trim())}
        >
          {isLoading ? '…' : 'Send'}
        </button>
      </div>
    </div>
  );
}

const s = {
  pane: {
    width: 380,
    flexShrink: 0,
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
    borderRight: '1px solid #dadce0',
    background: '#fff',
  },
  header: {
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: 8,
    padding: '14px 16px',
    background: '#1a73e8',
    color: '#fff',
    flexShrink: 0,
    flexWrap: 'wrap',
  },
  title: {
    fontWeight: 600,
    fontSize: 15,
    lineHeight: 1.3,
  },
  subtitle: {
    fontSize: 11,
    opacity: 0.85,
    marginTop: 2,
  },
  clearBtn: {
    background: 'rgba(255,255,255,0.15)',
    border: 'none',
    color: '#fff',
    padding: '5px 10px',
    borderRadius: 6,
    cursor: 'pointer',
    fontSize: 12,
    flexShrink: 0,
  },
  headerActions: {
    display: 'flex',
    gap: 6,
    flexShrink: 0,
  },
  modelRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '8px 16px',
    borderBottom: '1px solid #e8eaed',
    flexShrink: 0,
    background: '#f8f9fa',
  },
  modelLabel: {
    fontSize: 12,
    color: '#5f6368',
    whiteSpace: 'nowrap',
  },
  modelSelect: {
    flex: 1,
    fontSize: 12,
    padding: '5px 8px',
    borderRadius: 6,
    border: '1px solid #dadce0',
    background: '#fff',
  },
  docBadge: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    fontSize: 12,
    padding: '6px 16px',
    background: '#e8f0fe',
    borderBottom: '1px solid #d2e3fc',
    flexShrink: 0,
    minWidth: 0,
  },
  docBadgeLabel: {
    color: '#1a73e8',
    fontWeight: 600,
    flexShrink: 0,
  },
  docBadgeName: {
    color: '#3c4043',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  selectionBox: (hasSelection) => ({
    margin: '8px 12px',
    padding: '8px 10px',
    borderRadius: 8,
    border: `1px solid ${hasSelection ? '#1a73e8' : '#dadce0'}`,
    background: hasSelection ? '#e8f0fe' : '#f8f9fa',
    flexShrink: 0,
    fontSize: 12,
  }),
  selectionHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    marginBottom: 4,
  },
  selectionLabel: {
    color: '#1a73e8',
    fontWeight: 600,
    fontSize: 10,
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  clearSelectionBtn: {
    border: 'none',
    background: 'transparent',
    color: '#5f6368',
    fontSize: 11,
    cursor: 'pointer',
    padding: 0,
  },
  selectionPreview: {
    color: '#202124',
    fontStyle: 'italic',
    lineHeight: 1.4,
    wordBreak: 'break-word',
    maxHeight: 72,
    overflowY: 'auto',
  },
  selectionHint: {
    color: '#80868b',
    lineHeight: 1.4,
    fontSize: 11,
  },
  msgSelectionTag: {
    fontSize: 10,
    color: '#1a73e8',
    background: '#eef2ff',
    borderRadius: 4,
    padding: '3px 8px',
    marginBottom: 4,
    display: 'inline-block',
    fontStyle: 'italic',
    maxWidth: '92%',
    wordBreak: 'break-word',
  },
  messageList: {
    flex: 1,
    minHeight: 0,
    overflowY: 'auto',
    padding: '14px 16px',
  },
  emptyHint: {
    color: '#9aa0a6',
    fontSize: 13,
    textAlign: 'center',
    marginTop: 48,
    lineHeight: 1.6,
    padding: '0 8px',
  },
  msgBlock: {
    marginBottom: 14,
  },
  label: (role) => ({
    fontSize: 10,
    color: '#9aa0a6',
    marginBottom: 3,
    textAlign: role === 'user' ? 'right' : 'left',
    textTransform: 'uppercase',
    letterSpacing: 0.4,
    fontWeight: 600,
  }),
  row: (role) => ({
    display: 'flex',
    justifyContent: role === 'user' ? 'flex-end' : 'flex-start',
  }),
  bubble: (role) => ({
    display: 'inline-block',
    padding: '9px 12px',
    borderRadius: role === 'user' ? '12px 12px 4px 12px' : '12px 12px 12px 4px',
    background: role === 'user' ? '#1a73e8' : '#f1f3f4',
    color: role === 'user' ? '#fff' : '#202124',
    fontSize: 13,
    lineHeight: 1.5,
    maxWidth: '92%',
    wordBreak: 'break-word',
    whiteSpace: 'pre-wrap',
  }),
  typing: {
    fontSize: 13,
    color: '#9aa0a6',
    fontStyle: 'italic',
  },
  toolSection: {
    marginTop: 4,
  },
  toolToggle: {
    background: 'none',
    border: '1px solid #dadce0',
    borderRadius: 6,
    fontSize: 11,
    color: '#5f6368',
    cursor: 'pointer',
    padding: '3px 8px',
  },
  toolList: {
    marginTop: 6,
    background: '#f8f9fa',
    borderRadius: 6,
    border: '1px solid #e8eaed',
    padding: '8px 10px',
    fontSize: 11,
  },
  toolItem: {
    marginBottom: 6,
  },
  toolPre: {
    margin: '3px 0 0',
    fontFamily: 'Consolas, monospace',
    fontSize: 10,
    color: '#5f6368',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
  },
  activityPanel: {
    padding: '8px 12px',
    background: '#f1f3f4',
    borderBottom: '1px solid #e8eaed',
    flexShrink: 0,
    maxHeight: 140,
    overflowY: 'auto',
    fontSize: 11,
  },
  activityTitle: {
    fontWeight: 600,
    color: '#5f6368',
    marginBottom: 6,
    fontSize: 10,
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  activityEmpty: {
    color: '#9aa0a6',
  },
  activityRow: {
    display: 'flex',
    flexDirection: 'column',
    gap: 2,
    padding: '4px 0',
    borderBottom: '1px solid #e8eaed',
  },
  activityId: {
    fontFamily: 'Consolas, monospace',
    color: '#1a73e8',
    fontWeight: 600,
  },
  activityMeta: {
    color: '#5f6368',
  },
  roundBox: {
    marginTop: 6,
    border: '1px solid #c2d7f7',
    borderRadius: 8,
    background: '#f8fbff',
    overflow: 'hidden',
  },
  roundToggle: {
    width: '100%',
    textAlign: 'left',
    padding: '6px 10px',
    border: 'none',
    background: '#e8f0fe',
    color: '#1a73e8',
    fontSize: 11,
    fontWeight: 600,
    cursor: 'pointer',
  },
  roundCollapsed: {
    padding: '6px 10px',
    fontSize: 11,
    color: '#3c4043',
    whiteSpace: 'pre-wrap',
    lineHeight: 1.4,
  },
  roundBody: {
    padding: '8px 10px',
    fontSize: 11,
  },
  roundPre: {
    margin: '0 0 8px',
    whiteSpace: 'pre-wrap',
    fontFamily: 'inherit',
    color: '#202124',
    lineHeight: 1.45,
  },
  roundChange: {
    marginBottom: 8,
    paddingBottom: 6,
    borderBottom: '1px solid #e8eaed',
  },
  roundResult: {
    color: '#5f6368',
    marginTop: 2,
    fontSize: 10,
  },
  roundNote: {
    marginTop: 6,
    fontSize: 10,
    color: '#80868b',
    fontStyle: 'italic',
  },
  inputArea: {
    padding: '12px 16px',
    borderTop: '1px solid #e8eaed',
    display: 'flex',
    gap: 8,
    alignItems: 'flex-end',
    background: '#f8f9fa',
    flexShrink: 0,
  },
  textarea: {
    flex: 1,
    resize: 'none',
    padding: '9px 11px',
    borderRadius: 8,
    border: '1px solid #dadce0',
    fontSize: 13,
    fontFamily: 'inherit',
    outline: 'none',
    lineHeight: 1.45,
    background: '#fff',
  },
  sendBtn: (disabled) => ({
    padding: '9px 16px',
    background: disabled ? '#dadce0' : '#1a73e8',
    color: disabled ? '#80868b' : '#fff',
    border: 'none',
    borderRadius: 8,
    cursor: disabled ? 'not-allowed' : 'pointer',
    fontWeight: 600,
    fontSize: 13,
    whiteSpace: 'nowrap',
  }),
};
