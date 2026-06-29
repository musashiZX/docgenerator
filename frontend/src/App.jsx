import { useState, useEffect, useCallback } from 'react';
import ChatBot from './components/ChatBot';
import DocumentPanel from './components/DocumentPanel';
import { apiFetch, apiGetJson, apiPostJson } from './utils/apiClient';

// Written by start-java.ps1 / start-java.sh → frontend/.env.local (Java :8080)
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

function getSessionId() {
  const key = 'doc_agent_session_id';
  let id = localStorage.getItem(key);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(key, id);
  }
  return id;
}

const SESSION_ID = getSessionId();

export default function App() {
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [selectedDoc, setSelectedDoc] = useState(null);
  const [selectionText, setSelectionText] = useState('');
  const [model, setModel] = useState('gpt-4o-mini');
  const [previewVersion, setPreviewVersion] = useState(0);
  const [models, setModels] = useState([]);

  useEffect(() => {
    apiGetJson(API_BASE, '/api/models')
      .then((d) => { if (d?.models) setModels(d.models); })
      .catch(() => {});
  }, []);

  const handleSendMessage = useCallback(async (userInput) => {
    if (!selectedDoc) {
      setMessages((prev) => [
        ...prev,
        { role: 'ai', text: 'Please select or upload a document first.' },
      ]);
      return;
    }

    const selectionForMessage = selectionText.trim() || null;

    setMessages((prev) => [
      ...prev,
      {
        role: 'user',
        text: userInput,
        selectionPreview: selectionForMessage,
      },
    ]);
    setIsLoading(true);

    try {
      const body = {
        session_id: SESSION_ID,
        doc_name: selectedDoc,
        message: userInput,
        model,
      };
      if (selectionForMessage) {
        body.selected_text = selectionForMessage;
      }

      const { data, traceId } = await apiPostJson(API_BASE, '/api/chat', body);

      const toolCalls = data.tool_calls || [];
      const roundSummary = data.round_summary || null;
      const resolvedTraceId = data.trace_id || traceId;

      setMessages((prev) => [
        ...prev,
        {
          role: 'ai',
          text: data.reply || '(no reply)',
          toolCalls,
          roundSummary,
          traceId: resolvedTraceId,
        },
      ]);

      if (toolCalls.length > 0) {
        setPreviewVersion((v) => v + 1);
      }

      setSelectionText('');
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        { role: 'ai', text: err.message },
      ]);
    } finally {
      setIsLoading(false);
    }
  }, [selectedDoc, model, selectionText]);

  const handleClearHistory = useCallback(async () => {
    await apiFetch(`${API_BASE}/api/sessions/${SESSION_ID}`, { method: 'DELETE' }).catch(() => {});
    setMessages([]);
    setSelectionText('');
  }, []);

  return (
    <div className="app-shell">
      <ChatBot
        messages={messages}
        isLoading={isLoading}
        models={models}
        selectedModel={model}
        onModelChange={setModel}
        onSend={handleSendMessage}
        onClearHistory={handleClearHistory}
        onClearSelection={() => setSelectionText('')}
        selectedDoc={selectedDoc}
        selectionText={selectionText}
        apiBase={API_BASE}
      />
      <DocumentPanel
        apiBase={API_BASE}
        selectedDoc={selectedDoc}
        onDocSelect={setSelectedDoc}
        previewVersion={previewVersion}
        onPreviewRefresh={() => setPreviewVersion((v) => v + 1)}
        onSelectionChange={setSelectionText}
      />
    </div>
  );
}
