# Basic AI Agent — Implementation Plan

Branch: `feature/java-docio-backend`  
Scope: MVP — upload doc → prompt → AI edits in-memory `WordDocument` → save to folder DB → preview

---

## 1. Core idea (how AI uses Syncfusion Java)

**The LLM never calls Java or DocIO directly.** That would be unsafe and unreliable.

Instead, use a **tool loop** (same pattern as your Python `doc_editor.py`):

```
User prompt
    ↓
AgentService loads WordDocument from disk (in-memory DOM)
    ↓
LLM receives: system prompt + document snapshot + user message
    ↓
LLM returns: tool call(s) with JSON args  e.g. replace_text { find, replace }
    ↓
ToolExecutor maps each tool → DocIO method on the live WordDocument
    ↓
Repeat until LLM replies with text only (no more tools)
    ↓
DocIoService.save(document) → docs/<filename>.docx
    ↓
Return: reply + preview_html + preview_version
```

The **WordDocument object is the session state** for one agent turn. Every tool mutates that same in-memory object. Save once at the end (or after each tool if you want crash safety — start with once per turn).

| Layer | Responsibility |
|-------|----------------|
| **Frontend** | Upload file, pick file, send prompt, show preview |
| **AgentService** | LLM loop, conversation history |
| **DocumentSnapshot** | Walk DOM → text for LLM (`[p-0]`, list items, tables) |
| **ToolExecutor** | Validate args → call DocIO on `WordDocument` |
| **DocIoService** | `load()` / `save()` / `toHtml()` — file I/O only |
| **docs/ folder** | File DB — one `.docx` per document, no SQL |

Reference: [DocIO Java overview](https://help.syncfusion.com/document-processing/word/word-library/java/overview) — `WordDocument` is the deserialized object model (sections, paragraphs, `IWTextRange`, list format).

---

## 2. Document lifecycle (read → object → edit → save)

```
docs/training.docx          ← folder DB (already exists)
        │
        ▼
WordDocument doc = new WordDocument(path)   ← "deserialize" = open .docx into DOM
        │
        ▼
[ Agent turn: tools mutate `doc` in memory ]
        │
        ▼
doc.save(path, FormatType.Docx)             ← persist back to same file
        │
        ▼
doc.save(htmlStream, FormatType.Html)       ← preview for frontend
```

**Rules:**

- One `WordDocument` instance per agent turn; do not reload mid-turn unless you intentionally re-read after many edits.
- Always `document.close()` in try-with-resources.
- Filename = document ID (`training.docx`). No UUID indirection for MVP.
- Optional later: save backup as `docs/.backups/training-<timestamp>.docx` before overwrite.

---

## 3. Frontend scope (strip down)

Remove or hide for MVP:

- Syncfusion Rich Editor tab (no client-side mutation)
- Highlight & Rewrite / selection batch-replace
- Agent vs selection mode toggle
- SFDT import/export, Syncfusion cloud service calls

Keep:

| UI | Action |
|----|--------|
| Document dropdown | `GET /api/documents` |
| Upload button | `POST /api/documents/upload` |
| New document | `POST /api/documents` |
| Download | `GET /api/documents/{name}/download` |
| Preview iframe | `GET /api/documents/{name}/preview?v={version}` |
| Chat input + Send | `POST /api/chat` |
| Model selector (optional) | Pass `model` in chat body |
| Clear history | `DELETE /api/sessions/{id}` |

**Frontend sends one JSON body per edit:**

```json
{
  "session_id": "uuid-from-localStorage",
  "document_id": "training.docx",
  "message": "Add a comma at the end of each bullet in section 2",
  "model": "gemini-2.5-flash"
}
```

No selected text required for MVP. Add optional `selection.selected_text` in Phase 2.

Point `VITE_API_BASE` at Java server (`start-java.ps1` already does this).

---

## 4. Backend modules to add

```
doc-agent-server/src/main/java/com/docgen/
├── api/
│   ├── DocumentController.java     ✅ exists — keep upload/list/preview/download
│   └── ChatController.java         NEW — POST /api/chat
├── agent/
│   ├── AgentService.java           NEW — run tool loop
│   ├── AgentSessionStore.java      NEW — in-memory session → history
│   ├── DocumentSnapshot.java       NEW — WordDocument → LLM text
│   ├── ToolDefinitions.java        NEW — JSON schemas for LLM
│   ├── ToolExecutor.java           NEW — dispatch tool → DocIO
│   └── AgentPrompts.java           NEW — system prompt
├── llm/
│   ├── LlmClient.java              NEW — interface
│   ├── GeminiClient.java           NEW
│   └── OpenAiClient.java           NEW (optional)
├── docio/
│   └── DocIoService.java           ✅ extend — load/save/snapshot helpers
└── model/
    ├── ChatRequest.java
    └── ChatResponse.java
```

---

## 5. Tool catalog v1 (LLM tools → DocIO methods)

Start with **5 tools** — enough for basic editing, each maps 1:1 to DocIO:

### 5.1 `read_document`

| | |
|-|-|
| **Purpose** | Give LLM current structure (call first every turn) |
| **DocIO** | Iterate `document.getSections()` → `Body` → paragraphs / tables |
| **Output** | Plain text lines: `[p-0] (List Bullet) Permanent employees…` |

Implementation sketch:

```java
for (IWSection section : document.getSections()) {
  for (int i = 0; i < section.getBody().getChildEntities().getCount(); i++) {
    Entity entity = section.getBody().getChildEntities().get(i);
    if (entity instanceof WParagraph) { /* append text + list level */ }
    if (entity instanceof WTable) { /* append [TABLE t-n] rows */ }
  }
}
```

### 5.2 `replace_text`

| | |
|-|-|
| **Purpose** | Find/replace exact string (typo, comma, phrase) |
| **DocIO** | `document.replace(find, replace, false, false)` → returns count |
| **Args** | `{ "find": "…", "replace": "…" }` |

Use for surgical edits. DocIO replaces all occurrences — if you need one occurrence only, add `replace_first: true` → `document.setReplaceFirst(true)` before replace.

### 5.3 `set_paragraph_text`

| | |
|-|-|
| **Purpose** | Rewrite one paragraph by index from `read_document` |
| **DocIO** | Get paragraph at index → clear runs → `appendText(text)` preserving first run format OR replace entire paragraph text |
| **Args** | `{ "index": 12, "text": "…" }` |

### 5.4 `insert_paragraph`

| | |
|-|-|
| **Purpose** | Insert before paragraph at index |
| **DocIO** | `section.getBody().getChildEntities().insert(index, newParagraph)` |
| **Args** | `{ "index": 12, "text": "…", "style": "Normal" \| "List Bullet" \| "Heading 2" }` |

Apply list: `paragraph.getListFormat().applyDefBulletStyle()` when style is list.

### 5.5 `append_paragraph`

| | |
|-|-|
| **Purpose** | Add paragraph at end of body |
| **DocIO** | `section.addParagraph()` + `appendText` |
| **Args** | `{ "text": "…", "style": "Normal" }` |

**Phase 2 tools:** `read_table`, `set_table_cell`, `replace_in_paragraph` (scoped find within one index).

---

## 6. ToolExecutor pattern (the bridge)

This is the answer to “how does AI use Syncfusion methods?”:

```java
public class ToolExecutor {
  private final WordDocument document;

  public String dispatch(String toolName, JsonNode args) throws Exception {
    return switch (toolName) {
      case "read_document"     -> documentSnapshot.render(document);
      case "replace_text"      -> replaceText(args.get("find").asText(), args.get("replace").asText());
      case "set_paragraph_text"-> setParagraphText(args.get("index").asInt(), args.get("text").asText());
      case "insert_paragraph"  -> insertParagraph(...);
      case "append_paragraph"  -> appendParagraph(...);
      default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
    };
  }

  private String replaceText(String find, String replace) throws Exception {
    int n = document.replace(find, replace, false, false);
    return n > 0 ? "Replaced " + n + " occurrence(s)." : "No match found for: " + find;
  }
}
```

**Validation before DocIO:**

- `find` non-empty for replace
- `index` within `[0, paragraphCount)` 
- Reject unknown tool names
- Cap tool calls per turn (e.g. 20) to prevent loops

---

## 7. Agent loop (AgentService)

Port logic from Python `llm_provider.run_agent_loop` + `doc_editor.dispatch`:

```
POST /api/chat
  1. Resolve docs/<document_id>
  2. Load session history (session_id)
  3. try (WordDocument doc = load(path))
  4.   executor = new ToolExecutor(doc)
  5.   snapshot = executor.dispatch("read_document", {})
  6.   Append user message to history
  7.   loop (max 10 rounds):
  8.     response = llm.chat(system, tools, history)
  9.     if response has tool_calls:
 10.       for each tool_call:
 11.         result = executor.dispatch(name, args)
 12.         append tool result to history
 13.     else:
 14.       final reply = response.text; break
  15.   save(doc, path)
  16.   preview = toHtml(doc)
  17. return { reply, tool_calls_log, preview_html, preview_version }
```

**System prompt** (adapt from Python `SYSTEM_PROMPT`):

- Always call `read_document` first
- Use paragraph indices from snapshot
- Prefer `replace_text` for small exact changes
- Use `insert_paragraph` / `append_paragraph` for new content
- Short summary after edits

---

## 8. API summary

| Method | Path | Status |
|--------|------|--------|
| GET | `/api/health` | ✅ |
| GET | `/api/documents` | ✅ |
| POST | `/api/documents` | ✅ |
| POST | `/api/documents/upload` | ✅ |
| GET | `/api/documents/{name}/download` | ✅ |
| GET | `/api/documents/{name}/preview` | ✅ bump `?v=` after edit |
| **POST** | **`/api/chat`** | **NEW — main agent endpoint** |
| GET | `/api/models` | NEW — list Gemini/OpenAI models |
| DELETE | `/api/sessions/{id}` | NEW — clear chat history |

### Chat response

```json
{
  "reply": "Added commas to three bullet items in section 2.",
  "tool_calls": [
    { "name": "read_document", "args": {}, "result": "[p-0] …" },
    { "name": "replace_text", "args": { "find": "…", "replace": "…," }, "result": "Replaced 1 occurrence(s)." }
  ],
  "preview_html": "<html>…</html>",
  "preview_version": 4
}
```

Frontend: on success, set `previewVersion++` to refresh iframe.

---

## 9. LLM integration

**MVP:** Gemini (`GEMINI_API_KEY` from `.env`) — matches your Python stack.

Add to `pom.xml`:

```xml
<!-- Option A: Google GenAI Java SDK -->
<!-- Option B: plain HTTP with WebClient (fewer deps) -->
```

`LlmClient` interface:

```java
LlmResponse chat(String system, List<ToolSpec> tools, List<Message> history, String userMessage);
```

Must support **function calling** (tool_calls in response). Gemini `generateContent` with `tools` declaration; OpenAI chat completions with `tools` — same shape as Python.

Load keys from `.env` via existing `spring.config.import`.

---

## 10. Folder DB (`docs/`)

Already implemented in `DocIoService`:

- Storage: `doc-agent-server/docs/*.docx`
- List / upload / download / create empty
- No database server — filesystem is source of truth
- Config: `app.docs-dir: docs` in `application.yml`

Optional metadata sidecar (Phase 2): `docs/training.docx.meta.json` with `{ "updated_at", "preview_version" }`.

---

## 11. Implementation phases

### Phase A — Snapshot + tools (3–4 days)

| # | Task | Owner |
|---|------|-------|
| A1 | `DocumentSnapshot.java` — walk sections/body, assign `[p-n]` indices | Backend |
| A2 | `ToolExecutor` — implement 5 tools against live `WordDocument` | Backend |
| A3 | Unit tests with fixture `.docx` in `src/test/resources/` | Backend |
| A4 | Manual test: load → replace_text → save → reopen verifies change | Backend |

**Exit:** JUnit proves replace + insert work on real DocIO DOM.

### Phase B — LLM + agent loop (3–4 days)

| # | Task | Owner |
|---|------|-------|
| B1 | `GeminiClient` with tool calling | Backend |
| B2 | `ToolDefinitions.java` — tool JSON schemas for Gemini | Backend |
| B3 | `AgentService` + `ChatController` | Backend |
| B4 | `AgentSessionStore` — `ConcurrentHashMap<sessionId, List<Message>>` | Backend |
| B5 | `AgentPrompts.SYSTEM` — port from Python | Backend |
| B6 | Trace logging: save request/response JSON to `logs/traces/` | Backend |

**Exit:** `curl POST /api/chat` with prompt edits a real doc; preview updates.

### Phase C — Frontend slim-down (2 days)

| # | Task | Owner |
|---|------|-------|
| C1 | Remove Document Editor tab + Syncfusion editor deps (optional keep license for later) | Frontend |
| C2 | `App.jsx` — only chat + preview + doc picker; call Java `/api/chat` | Frontend |
| C3 | Remove selection edit, batch-replace, python-specific paths | Frontend |
| C4 | Show tool call summary in chat (optional collapsible) | Frontend |

**Exit:** User uploads doc, sends prompt, sees preview refresh — end-to-end in browser.

### Phase D — Hardening (2 days)

| # | Task | Owner |
|---|------|-------|
| D1 | Golden test: "add comma to each bullet line" on XYZ training doc | QA |
| D2 | Error handling: LLM fail, invalid tool args, DocIO exception | Backend |
| D3 | `GET /api/models` + model selector wired | Both |
| D4 | README update | Docs |

**Exit:** MVP demo-ready.

**Total estimate: ~10–12 days**

---

## 12. Testing strategy

| Test | What |
|------|------|
| `DocumentSnapshotTest` | Fixture doc → expected `[p-n]` lines |
| `ToolExecutorTest` | replace_text / insert_paragraph on fixture |
| `AgentServiceTest` | Mock LlmClient returning fixed tool_calls → assert file changed |
| Manual E2E | Upload XYZ doc → chat → preview matches expectation |

Fixture: copy one `.docx` into `src/test/resources/fixtures/training.docx` (not committed if large — use minimal 3-paragraph doc for CI).

---

## 13. What we deliberately skip in MVP

- SQL / real database
- Selection / highlight mode
- Two-stage preprocess LLM (structured IR) — use tool loop first; add if tool loop insufficient
- Table editing tools
- Multi-user / auth
- Undo history

---

## 14. Recommended build order (start here)

1. **DocumentSnapshot** + **ToolExecutor** (no LLM) — prove DocIO mutation path  
2. **ChatController** stub returning hard-coded replace — wire frontend preview  
3. **GeminiClient** + **AgentService** — real AI  
4. **Strip frontend** — preview-only UI  

Say **go** when ready to start Phase A.
