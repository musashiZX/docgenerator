import { useState, useRef, useEffect, useCallback } from 'react';
import ChatBot from './components/ChatBot';
import DocumentPanel from './components/DocumentPanel';

// ── API config ────────────────────────────────────────────────────────────────
// VITE_API_BASE is written to frontend/.env.local by start.ps1 so the port
// is always in sync with the backend.  Falls back to 8001 for manual runs.
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8001';

// Generate or restore a stable session ID so conversation history persists
// across page refreshes.
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

// How many characters to include as context before/after the selected text.
// ~1 500 characters covers roughly 8–12 lines of typical document prose,
// giving the LLM enough surrounding meaning without overwhelming the prompt.
const CONTEXT_CHARS = 1500;

/**
 * Ask Syncfusion to export the whole document as plain text, then slice out
 * the text that immediately precedes and follows `selectedText`.
 * Falls back to empty strings if the export API is unavailable or the
 * selected text cannot be located in the document.
 */
async function extractSelectionContext(documentEditor, selectedText) {
  if (!selectedText) return { contextBefore: '', contextAfter: '' };
  try {
    const fullText = await new Promise((resolve) => {
      try {
        documentEditor.exportContent('Txt', (content) => resolve(content || ''));
      } catch (_) {
        resolve('');
      }
    });
    if (!fullText) return { contextBefore: '', contextAfter: '' };
    // Normalise \r\n / \r → \n so the indexOf search matches the already-
    // normalised selectedText (Syncfusion uses \r as paragraph separator).
    const normalised = fullText.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    const idx = normalised.indexOf(selectedText);
    if (idx === -1) return { contextBefore: '', contextAfter: '' };
    return {
      contextBefore: normalised.slice(Math.max(0, idx - CONTEXT_CHARS), idx),
      contextAfter:  normalised.slice(
        idx + selectedText.length,
        idx + selectedText.length + CONTEXT_CHARS,
      ),
    };
  } catch (_) {
    return { contextBefore: '', contextAfter: '' };
  }
}

export default function App() {
  const editorRef = useRef(null);

  // ── State ─────────────────────────────────────────────────────────────────
  const [messages, setMessages]         = useState([]);
  const [isLoading, setIsLoading]       = useState(false);
  const [selectedDoc, setSelectedDoc]   = useState(null);   // string | null
  const [model, setModel]               = useState('gemini-2.5-flash');
  const [previewVersion, setPreviewVersion] = useState(0);  // bumped after each agent edit
  const [editMode, setEditMode]         = useState('agent'); // 'agent' | 'selection'
  const [selectionText, setSelectionText] = useState('');   // text highlighted in Syncfusion

  // ── Fetch initial model list (optional, for the model selector) ──────────
  const [models, setModels] = useState([]);
  useEffect(() => {
    fetch(`${API_BASE}/api/models`)
      .then((r) => r.json())
      .then((d) => setModels(d.models))
      .catch(() => {});
  }, []);

  // ── Send message to Python agent ─────────────────────────────────────────
  const handleSendMessage = useCallback(async (userInput) => {
    if (!selectedDoc) {
      setMessages((prev) => [
        ...prev,
        { role: 'ai', text: '⚠️ Please select or upload a document first.' },
      ]);
      return;
    }

    setMessages((prev) => [...prev, { role: 'user', text: userInput }]);
    setIsLoading(true);

    try {
      const res = await fetch(`${API_BASE}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          session_id: SESSION_ID,
          doc_name: selectedDoc,
          message: userInput,
          model,
        }),
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({ detail: res.statusText }));
        throw new Error(err.detail || `Server error ${res.status}`);
      }

      const data = await res.json();

      setMessages((prev) => [
        ...prev,
        {
          role: 'ai',
          text: data.reply || '(no reply)',
          toolCalls: data.tool_calls || [],
        },
      ]);

      const toolCalls = data.tool_calls || [];

      // ── Syncfusion format-preserving replacement ──────────────────────────
      // For every replace_text the AI called on python-docx, mirror it through
      // Syncfusion's JS engine in the Rich Editor.  Syncfusion replaces text
      // at the run level, so bold / italic / colour / font-size are preserved
      // even when python-docx had to fall back to a paragraph-level rebuild.
      if (editorRef.current) {
        const replaceOps = toolCalls.filter((tc) => tc.name === 'replace_text');
        replaceOps.forEach(({ args }) => {
          const find    = args?.find;
          const replace = args?.replace;
          if (!find) return;
          try {
            const editor = editorRef.current.documentEditor;
            editor.searchModule.find(find, false, false);
            if (editor.selection.text && editor.selection.text.trim()) {
              editor.editorModule.insertText(replace ?? '');
            }
          } catch (_) {
            // Silently ignore — document reloads from server via previewVersion.
          }
        });
      }

      // Bump the preview version so DocumentPanel reloads both the iframe
      // preview and the Syncfusion Rich Editor after every agent edit.
      if (toolCalls.length > 0) {
        setPreviewVersion((v) => v + 1);
      }
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        { role: 'ai', text: `⚠️ ${err.message}` },
      ]);
    } finally {
      setIsLoading(false);
    }
  }, [selectedDoc, model]);

  // ── Highlight & Batch Replace — surgical selection edit ─────────────────
  const handleSelectionEdit = useCallback(async (userInput) => {
    if (!selectionText) {
      setMessages((prev) => [
        ...prev,
        {
          role: 'ai',
          text: '⚠️ No text selected. Switch to the Rich Editor tab, highlight some text, then send your instruction.',
        },
      ]);
      return;
    }

    setMessages((prev) => [
      ...prev,
      { role: 'user', text: userInput, selectionPreview: selectionText },
    ]);
    setIsLoading(true);

    try {
      // Extract surrounding document text for better LLM context.
      let contextBefore = '';
      let contextAfter  = '';
      if (editorRef.current) {
        ({ contextBefore, contextAfter } = await extractSelectionContext(
          editorRef.current.documentEditor,
          selectionText,
        ));
      }

      const res = await fetch(`${API_BASE}/api/edit-selection`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          session_id: SESSION_ID,
          user_prompt: userInput,
          target_selection: {
            selected_text: selectionText,
            context_before: contextBefore,
            context_after:  contextAfter,
          },
          action_intent: 'batch_replace',
          model,
          user_preferences: { language: 'auto', tone: 'professional' },
        }),
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({ detail: res.statusText }));
        throw new Error(err.detail || `Server error ${res.status}`);
      }

      const data = await res.json();

      // Dispatch each surgical edit via Syncfusion's search-and-replace engine.
      // findAll + replace operates at the run level, so paragraph-level formatting
      // (bullet styles, table borders, indentation, numbering) is never touched.
      let applied = 0;
      let notFound = 0;
      const skippedFinds = [];

      if (editorRef.current && Array.isArray(data.edits)) {
        const docEditor = editorRef.current.documentEditor;
        data.edits.forEach((edit) => {
          if (edit.action !== 'replace_exact_text' || !edit.find) return;
          try {
            // find() navigates to the first match AND selects it, so
            // selection.text becomes the matched string.  We use that to
            // confirm a hit rather than selection.isEmpty (which is
            // unreliable after findAll).
            docEditor.searchModule.find(edit.find, false, false);
            const selText = docEditor.selection.text || '';
            console.debug(
              '[batch-replace] find=%o selText=%o isEmpty=%o',
              edit.find, selText, docEditor.selection.isEmpty,
            );
            if (selText.trim()) {
              // insertText replaces the current selection (the found text)
              // at run level — paragraph styles (bullets, list markers,
              // indentation) stored in the paragraph marker are untouched.
              docEditor.editorModule.insertText(edit.replace ?? '');
              applied++;
            } else {
              notFound++;
              skippedFinds.push(edit.find);
            }
          } catch (err) {
            console.error('[batch-replace] error on find=%o', edit.find, err);
            notFound++;
            skippedFinds.push(edit.find);
          }
        });
      }

      // Build a human-readable edit summary for the chat log.
      const editLines = (data.edits || [])
        .filter((e) => e.action === 'replace_exact_text')
        .map((e) => `  • "${e.find}" → "${e.replace}"`)
        .join('\n');

      let summary = '';
      if (applied === 0 && notFound === 0) {
        summary = 'No edits were suggested by the AI.';
      } else if (applied > 0 && notFound === 0) {
        summary = `Applied ${applied} edit${applied !== 1 ? 's' : ''}:\n${editLines}`;
      } else if (applied > 0) {
        summary =
          `Applied ${applied} edit${applied !== 1 ? 's' : ''}, ` +
          `${notFound} not found in document:\n${editLines}`;
      } else {
        summary =
          `No edits applied — ${notFound} text string${notFound !== 1 ? 's' : ''} ` +
          `not found exactly in document.\nSearched for:\n` +
          skippedFinds.map((f) => `  • "${f}"`).join('\n') +
          '\n\nTip: try a shorter or more unique selection.';
      }

      setMessages((prev) => [...prev, { role: 'ai', text: summary }]);

      // Clear the stored selection since it has been replaced.
      setSelectionText('');
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        { role: 'ai', text: `⚠️ ${err.message}` },
      ]);
    } finally {
      setIsLoading(false);
    }
  }, [selectionText, model]);

  // ── Clear history ─────────────────────────────────────────────────────────
  const handleClearHistory = useCallback(async () => {
    await fetch(`${API_BASE}/api/sessions/${SESSION_ID}`, { method: 'DELETE' }).catch(() => {});
    setMessages([]);
  }, []);

  // ── Layout ────────────────────────────────────────────────────────────────
  return (
    <div style={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
      <ChatBot
        messages={messages}
        isLoading={isLoading}
        models={models}
        selectedModel={model}
        onModelChange={setModel}
        onSend={handleSendMessage}
        onSelectionEdit={handleSelectionEdit}
        onClearHistory={handleClearHistory}
        selectedDoc={selectedDoc}
        editMode={editMode}
        onEditModeChange={setEditMode}
        selectionText={selectionText}
      />
      <DocumentPanel
        apiBase={API_BASE}
        editorRef={editorRef}
        selectedDoc={selectedDoc}
        onDocSelect={setSelectedDoc}
        previewVersion={previewVersion}
        onSelectionChange={setSelectionText}
      />
    </div>
  );
}
