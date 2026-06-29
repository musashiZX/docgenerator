# Java DocIO Backend — Architecture & Implementation Plan

Branch: `feature/java-docio-backend`

## 1. Design assessment

### Verdict: **Good direction — recommended**

The current stack splits document mutation across two engines:

| Layer | Engine | Problem |
|-------|--------|---------|
| Backend | python-docx | Paragraph-level edits; weak format preservation |
| Frontend | Syncfusion JS Document Editor | Run-level edits; search API unreliable on lists |
| Bridge | Mirror `replace_text` tool calls in JS | Two sources of truth; drift and format loss |

Moving to **Syncfusion Essential DocIO (Java)** on the server gives one engine for both “brain” (AI planning) and “hands” (document mutation).

### What DocIO Java is (important)

[DocIO overview](https://help.syncfusion.com/document-processing/word/word-library/java/overview) is a **server-side Word library**, not the browser Document Editor:

- Opens / modifies / saves `.docx` without Microsoft Word
- Full DOM: sections → paragraphs → **text ranges** (runs) with character format
- Lists, tables, bookmarks, mail merge, find/replace with **`saveFormatting`**
- Export to **HTML**, RTF, TXT for preview ([getting started](https://help.syncfusion.com/document-processing/word/word-library/java/getting-started))

There is **no equivalent Python package** from Syncfusion. python-docx is a different, less capable model.

### Why this fixes your pain

1. **Format-preserving replace** — DocIO `Replace(given, replace, caseSensitive, wholeWord, saveFormatting)` keeps bold, highlight, list level ([find/replace docs](https://help.syncfusion.com/document-processing/word/word-library/net/working-with-find-and-replace)).
2. **Scoped selection** — Backend can locate `selected_text` in the DOM, restrict edits to matching paragraphs/runs (no Syncfusion offset hacks).
3. **Single source of truth** — One `.docx` on disk; frontend only displays preview + collects prompts.
4. **Structured AI contract** — DOM traversal produces stable paragraph/run IDs for the JSON IR you already designed.

### Trade-offs (accept consciously)

| Gain | Cost |
|------|------|
| Reliable edits | Lose in-browser WYSIWYG editing (unless you add read-only viewer later) |
| One document engine | **Syncfusion license** required (trial + `syncfusion.licensing` from v19+) |
| Simpler frontend | Rewrite backend in Java; LLM layer must be re-ported or wrapped |
| HTML preview from DocIO | Preview may differ slightly from Word desktop (same as current mammoth path) |

Optional later: read-only Syncfusion Document Editor for display only (loads `.docx` from server) — still no dual mutation.

---

## 2. Target architecture

```
┌─────────────────────────────────────────────────────────────┐
│  React frontend (thin)                                       │
│  • Upload / pick document                                    │
│  • Chat + optional text selection (plain text + hints)       │
│  • Preview pane (HTML from server)                           │
│  • Download .docx                                            │
└──────────────────────────┬──────────────────────────────────┘
                           │ JSON REST
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Spring Boot 3 + Java 17 (doc-agent-server/)                 │
│                                                              │
│  ┌─────────────┐   ┌──────────────┐   ┌─────────────────┐ │
│  │ REST API    │──▶│ AgentService │──▶│ LlmClient       │ │
│  │ controllers │   │ (tool loop)  │   │ Gemini / OpenAI │ │
│  └──────┬──────┘   └──────┬───────┘   └─────────────────┘ │
│         │                 │                                  │
│         │                 ▼                                  │
│         │          ┌──────────────┐                          │
│         └─────────▶│ DocIoService │◀── Syncfusion DocIO    │
│                    │ load/save    │                          │
│                    │ find/replace │                          │
│                    │ DOM extract  │                          │
│                    │ to HTML      │                          │
│                    └──────┬───────┘                          │
│                           │                                  │
│                    docs/*.docx                               │
│                    logs/traces/                              │
└─────────────────────────────────────────────────────────────┘
```

**Principle:** Frontend sends intent; backend owns all document logic.

---

## 3. API contract (JSON)

### 3.1 Edit request (frontend → backend)

```json
{
  "session_id": "uuid",
  "document_id": "training-program.docx",
  "user_prompt": "Add a comma at the end of each line",
  "model": "gemini-2.5-flash",
  "selection": {
    "selected_text": "Permanent employees …\nSeasonal …",
    "mode": "highlight"
  },
  "preferences": {
    "language": "auto",
    "tone": "professional"
  }
}
```

`selection` is optional (whole-document mode when omitted).

### 3.2 Context snapshot (backend internal, optionally returned in trace)

Built by **DocIoService.extractContext(doc, selection)** — deterministic, no AI:

```json
{
  "document_id": "training-program.docx",
  "selection": {
    "target_block_ids": ["p-12", "p-13", "p-14"],
    "selected_text": "…"
  },
  "blocks": [
    {
      "id": "p-12",
      "type": "list_item",
      "text": "Permanent employees (production, …)",
      "runs": [
        { "id": "p-12-r0", "text": "Permanent employees", "marks": ["bold"] },
        { "id": "p-12-r1", "text": " (production, …)", "marks": [] }
      ]
    }
  ]
}
```

### 3.3 Operation queue (LLM → backend validator → DocIO executor)

```json
{
  "status": "success",
  "explanation": "Added commas to three list items.",
  "operations": [
    {
      "action": "replace_text",
      "scope": "block",
      "target_id": "p-12",
      "find": "quality, management)",
      "replace": "quality, management),",
      "save_formatting": true
    }
  ],
  "preview_html": "<html>…</html>",
  "preview_version": 3
}
```

Reject invalid ops in Java **before** touching the document (`extra=forbid` Pydantic-style with Jackson + records).

---

## 4. DocIO integration (hands)

### 4.1 Maven dependencies

Repository: `https://jars.syncfusion.com/repository/maven-public/`

```xml
<dependency>
  <groupId>com.syncfusion</groupId>
  <artifactId>syncfusion-docio</artifactId>
  <version>29.1.33</version>
</dependency>
<dependency>
  <groupId>com.syncfusion</groupId>
  <artifactId>syncfusion-javahelper</artifactId>
  <version>29.1.33</version>
</dependency>
<dependency>
  <groupId>com.syncfusion</groupId>
  <artifactId>syncfusion-licensing</artifactId>
  <version>29.1.33</version>
</dependency>
```

Register license key at startup ([licensing note](https://help.syncfusion.com/document-processing/word/word-library/java/getting-started)).

### 4.2 Core DocIoService methods

| Method | DocIO API | Purpose |
|--------|-----------|---------|
| `load(path)` | `new WordDocument(path)` | Open .docx |
| `save(doc, path)` | `document.save(path, FormatType.Docx)` | Persist |
| `toHtml(doc)` | `document.save(htmlStream, FormatType.Html)` | Preview |
| `extractBlocks(doc, selectionText)` | Iterate `Section` → `Body` → paragraphs / list items | AI context |
| `replaceInBlock(doc, blockId, find, replace)` | Scoped `Replace(..., saveFormatting=true)` | Surgical edit |
| `replaceRun(doc, runId, newText)` | Select `WTextRange`, set text preserving format | Run-level |
| `insertAfter(doc, blockId, contentTree)` | `IWParagraph` / list APIs | Structural edits |

### 4.3 Selection resolution (replaces Syncfusion offsets)

1. Normalize whitespace in `selected_text` and block texts.
2. Find contiguous block sequence whose concatenated text contains `selected_text`.
3. Assign stable IDs for that request (`p-{index}`, `p-{index}-r{n}`).
4. Restrict all `Replace` calls to those blocks (paragraph-scoped search first).

For list items, each bullet is its own paragraph with `ListFormat` — DocIO exposes this directly ([lists in getting started](https://help.syncfusion.com/document-processing/word/word-library/java/getting-started)).

### 4.4 v1 operation set

| Action | DocIO implementation |
|--------|------------------------|
| `replace_text` | `document.replace(find, replace, false, false, true)` scoped to block |
| `append_to_block_end` | Find last run in block, append punctuation (no find/replace guesswork) |
| `replace_block_text` | Replace paragraph text while cloning character format of first run |
| `insert_paragraph_after` | `section.addParagraph()` after target |
| `insert_list_items_after` | `ListFormat.applyDefBulletStyle()` |

Defer tables/images to v2.

---

## 5. AI agent integration (brain)

### 5.1 Tool loop (port from current Python)

Reuse concepts from `doc_editor.py` + `llm_provider.py`:

```
User prompt + context blocks
    → LLM with JSON schema (operations[])
    → Java validator (IDs exist, actions allowed)
    → DocIoService.execute(operations)
    → Save .docx + regenerate HTML
    → Return explanation + preview
```

### 5.2 LLM options

| Option | Pros | Cons |
|--------|------|------|
| **A. Pure Java** (OpenAI / Google SDK) | Single process | Reimplement providers |
| **B. Hybrid** — Java calls existing Python LLM microservice | Fast migration | Two runtimes temporarily |
| **C. HTTP to OpenAI/Gemini REST** | Minimal deps | Manual tool-call parsing |

**Recommendation:** Start with **C or A** for edit endpoint; keep Python repo as reference for prompts.

### 5.3 Prompt strategy

Two-stage (your earlier plan), both on server:

1. **Fast model** — optional; normalize selection + infer `allowed_actions`
2. **Main model** — emit `operations[]` referencing block/run IDs from deterministic extract

For simple “add comma to each line”, skip stage 1; use deterministic `append_to_block_end` detector in Java when prompt matches punctuation patterns.

---

## 6. Frontend changes (thin client)

Remove or disable:

- Syncfusion Document Editor container (or keep as read-only preview in phase 2)
- `selectionReplace.js`, batch-replace mirror logic
- SFDT save/import round-trip

Keep / simplify:

- Document list, upload, download
- Chat UI + mode toggle (whole doc vs selection)
- Selection: browser `window.getSelection()` on preview iframe **or** manual paste of highlighted text (phase 1); paragraph-aware selection in phase 2 via preview markup with `data-block-id`

Preview:

```jsx
<iframe src={`${API}/api/documents/${name}/preview?v=${version}`} />
```

After each edit, bump `preview_version` from response.

---

## 7. Project layout

```
docGenerator/
├── docs/                          # .docx storage (unchanged)
├── logs/traces/                   # JSON trace (port api_trace.py)
├── doc-agent-server/              # NEW — Spring Boot
│   ├── pom.xml
│   └── src/main/java/com/docgen/
│       ├── DocAgentApplication.java
│       ├── config/                # CORS, license, env
│       ├── api/                   # REST controllers
│       ├── model/                 # Request/response records
│       ├── docio/                 # DocIoService, BlockExtractor, HtmlPreview
│       ├── agent/                 # AgentService, prompts, OperationValidator
│       ├── llm/                   # GeminiClient, OpenAiClient
│       └── trace/                 # ApiTraceFilter
├── frontend/                      # Slimmed React (existing, refactored)
├── python-legacy/                 # OPTIONAL — move api.py, doc_editor.py here later
└── docs/JAVA_DOCIO_PLAN.md        # this file
```

---

## 8. Implementation phases

### Phase 0 — Setup (2–3 days)

- [ ] Confirm Syncfusion Java license (trial or existing JS license scope)
- [ ] Create `doc-agent-server` Spring Boot skeleton
- [ ] Maven + DocIO hello-world: open `.docx`, replace text, save, export HTML
- [ ] `POST /api/health`, `GET /api/documents`, upload/download endpoints
- [ ] Port JSON trace middleware to Java

**Exit:** CLI or curl can upload doc, replace string, get HTML preview.

### Phase 1 — Context extraction + single-shot edit (4–5 days)

- [ ] `BlockExtractor` — paragraphs + list items + runs + marks
- [ ] Selection → target block IDs
- [ ] `POST /api/edit` with strict JSON schema
- [ ] LLM returns `operations[]`; validator + executor for `replace_text` + `append_to_block_end`
- [ ] Frontend: remove Syncfusion editor; preview-only + chat

**Exit:** “Add comma to each line” works on bullet list in XYZ training doc.

### Phase 2 — Agent mode (4–5 days)

- [ ] Multi-turn session store
- [ ] Tool loop: `read_blocks`, `replace_text`, `insert_paragraph`, `insert_list`
- [ ] `POST /api/chat` parity with current agent mode
- [ ] Trace: request, LLM raw, operations, preview hash

**Exit:** “Rewrite section 2 to be more formal” works end-to-end.

### Phase 3 — Hardening (3–4 days)

- [ ] Golden tests (JUnit) with fixture `.docx` files
- [ ] Error UX + partial apply handling
- [ ] `start.sh` / `start.ps1` launch Java + Vite
- [ ] Deprecation notice on Python `api.py` edit paths

**Exit:** Production-ready MVP on `feature/java-docio-backend`.

### Phase 4 — Optional enhancements

- Read-only Syncfusion viewer loading server `.docx`
- Checklist audit port (`checklist.py` → Java)
- Table cell operations

---

## 9. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Syncfusion license cost | Trial for dev; Community license if eligible |
| HTML preview ≠ Word layout | Accept for MVP; optional PDF preview later |
| LLM emits bad `find` strings | Prefer `append_to_block_end` + block IDs over free-text find |
| Large doc performance | Extract only ±N blocks around selection |
| Rewriting all prompts/tools | Copy from Python; hybrid LLM service short-term |

---

## 10. Recommended next step

On branch `feature/java-docio-backend`:

1. Add `doc-agent-server/` Phase 0 skeleton
2. Prove one vertical slice: **upload XYZ doc → POST edit with comma prompt → HTML preview updates**

Estimated total: **~3–4 weeks** to MVP (Phases 0–3).
