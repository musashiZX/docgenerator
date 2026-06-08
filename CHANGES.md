# Session Changes — Surgical Batch-Replace Editor

## What Was Built

A **"Highlight & Rewrite"** mode in the React frontend that lets the user select text in the Syncfusion Document Editor, describe an edit in plain language, and have the AI apply precise, format-preserving changes using a find-and-replace approach.

---

## Architecture Overview

```
User selects text in Syncfusion → selectionChange fires
        ↓
App.jsx: extractSelectionContext() pulls ±1500 chars of surrounding text
        ↓
POST /api/edit-selection  { selected_text, context_before, context_after, user_prompt }
        ↓
Backend: LLM analyses selection, returns batch_replace JSON
        ↓
Frontend: loops through edits, calls find() + insertText() per edit
        ↓
Syncfusion applies each replacement at run level — paragraph styles untouched
```

---

## Key Design Decisions

### 1. Batch-Replace JSON Schema (not plain rewrite)
The LLM returns a structured action list instead of a rewritten blob:
```json
{
  "action": "batch_replace",
  "edits": [
    { "action": "replace_exact_text", "find": "and safety points", "replace": "and safety points," }
  ]
}
```
- The `find` value must be a verbatim substring — 100% exact match
- Each edit is minimal: only what is strictly necessary changes
- The LLM is forbidden from returning prose or markdown

### 2. End-of-Line Targeting (not full-line replacement)
**Critical rule**: for end-of-line changes (add comma, add period), the AI must use the **last 4–7 words** as `find`, NOT the entire line.

- Lines often start with bold/formatted text (e.g., `**Day 1 —** regular text`)
- `insertText` on a full-line selection collapses mixed formatting to one style
- Targeting only the tail leaves the bold prefix untouched

### 3. `find()` + `insertText()` — not `findAll()` + `replace()`
After testing multiple Syncfusion APIs:
- `searchModule.findAll()` + `selection.isEmpty` check: `isEmpty` is always `true` after `findAll` — never fires
- `searchModule.findAll()` + `searchModule.replace(text, true)`: accepted without error but is a silent no-op in this Syncfusion version
- ✅ **Working**: `searchModule.find()` selects the first match (sets `selection.text`), then `editorModule.insertText()` replaces it

### 4. Syncfusion `\r` Normalisation
`documentEditor.selection.text` uses `\r` (CR) as paragraph separator for multi-paragraph selections. This caused the AI to see bullet list items as one long string with no line breaks.

Fix: normalise `\r\n` and `\r` → `\n` at capture time in `DocumentPanel.jsx` and in `extractSelectionContext()` in `App.jsx`.

---

## Files Changed

### Backend

#### `api.py` _(new file — not previously tracked)_
- `POST /api/edit-selection` endpoint: single-shot LLM call returning `batch_replace` JSON
- `_SELECTION_SYSTEM` prompt: enforces JSON-only output, surgical `find` scope rules, end-of-line targeting
- JSON parsing + validation of LLM response; strips markdown fences if model disobeys
- CORS origins updated for ports 5174, 5175 (Vite auto-increment)

#### `llm_provider.py`
- Added `complete(system, user_text) → str` to base `Provider` and both `GeminiProvider` / `OpenAIProvider` — single-shot non-tool-calling inference

### Frontend

#### `frontend/src/App.jsx`
- `CONTEXT_CHARS = 1500` — characters of surrounding document text sent as context
- `extractSelectionContext()` — exports full document as plain text, slices before/after selection, normalises `\r` → `\n`
- `handleSelectionEdit()` — sends `POST /api/edit-selection`, receives `batch_replace`, loops through edits:
  - `searchModule.find(find, false, false)` → check `selection.text` → `editorModule.insertText(replace)`
  - Applied / not-found counters surface in chat as a diff summary
- `API_BASE` reads `import.meta.env.VITE_API_BASE` (written by `start.sh`) with fallback to `http://localhost:8001`

#### `frontend/src/components/DocumentPanel.jsx`
- `selectionChange` handler: normalises `\r\n` / `\r` → `\n` before calling `onSelectionChange`

#### `frontend/src/components/ChatBot.jsx`
- Mode toggle: **Agent Mode** (python-docx) vs **Highlight & Rewrite** (batch-replace)
- Selection preview box shown when in Highlight & Rewrite mode

### Launcher

#### `start.sh` _(new file)_
- Kills old `python.exe` / `node.exe` via `taskkill`
- Kills port squatter using `netstat -ano` + `taskkill /F /PID` (handles ghost sockets that PowerShell's `Stop-Process` cannot)
- Finds first free port from 8001 upward
- Writes `VITE_API_BASE=http://localhost:<PORT>` to `frontend/.env.local` **before** starting Vite — guarantees frontend and backend always agree on port
- Health-checks `/api/health` before starting frontend
- Detects actual Vite port from its stdout (handles Vite auto-incrementing to 5174, etc.)
- `trap cleanup INT TERM EXIT` kills both processes on Ctrl+C

---

## Pending Work

- [ ] **Checklist feature** (`checklist-feature`): `POST /api/documents/{name}/checklist`, structural + LLM content audit, `ChecklistPanel.jsx` tab
- [ ] **Multi-occurrence replace**: current `find()` only replaces the first match; need a loop for cases where the same phrase appears multiple times in the selection
- [ ] **Scope search to selection**: Syncfusion has no built-in "search within selection range" API; workaround is relying on unique `find` strings
- [ ] **Undo grouping**: multiple `insertText` calls create multiple undo steps; group them as one transaction

---

## How to Run

```bash
# In Git Bash (from project root):
./start.sh

# Custom port:
./start.sh 8003
```

The script prints the exact URLs for both backend and frontend when ready.
