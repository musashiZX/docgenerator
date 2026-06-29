"""FastAPI backend for the Word Document AI Agent.

Exposes the existing Python agent logic as a REST API so the React frontend
can drive it independently of the Streamlit UI.

Run:
    .venv/Scripts/python.exe -m uvicorn api:app --reload --port 8000

Endpoints
---------
GET  /api/health
GET  /api/models
GET  /api/documents
POST /api/documents                    create empty doc
POST /api/documents/upload             upload a .docx file
GET  /api/documents/{name}/download    download the .docx
GET  /api/documents/{name}/preview     HTML preview via mammoth
POST /api/chat                         run one agent turn (python-docx Agent Mode)
POST /api/edit-selection               rewrite highlighted text (Selection Mode)
POST /api/trace/{trace_id}/append      append client-side trace data to a trace folder
DELETE /api/sessions/{session_id}      clear conversation history

JSON traces for every /api/* call are saved under logs/traces/ (disable with API_TRACE=0).
"""

from __future__ import annotations

import json
import re
import time
import uuid
from pathlib import Path
from typing import Optional

import mammoth
from dotenv import load_dotenv
from fastapi import Body, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, Response
from pydantic import BaseModel
from starlette.requests import ClientDisconnect

from api_trace import ApiTrace, append_extra, begin_trace, list_recent_traces, tracing_enabled
from app_logger import get_logger, truncate
from doc_editor import SYSTEM_PROMPT, TOOLS as DOC_TOOLS, DocEditor, dispatch
from llm_provider import ALL_MODELS, get_provider

load_dotenv()

log = get_logger()

app = FastAPI(title="Word Doc Agent API", version="1.0.0")

# Allow the Vite dev server (5173) and any production origin.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:3000", "http://localhost:4173", "http://localhost:5174", "http://localhost:5175"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["X-Trace-Id", "X-Trace-Dir"],
)


@app.middleware("http")
async def trace_api_exchange(request: Request, call_next):
    """Save exact request/response JSON for every /api/* call."""
    path = request.url.path
    if not path.startswith("/api/") or not tracing_enabled():
        return await call_next(request)

    trace: ApiTrace | None = begin_trace(
        request.method,
        path,
        request.url.query,
    )
    started = time.perf_counter()

    try:
        body_bytes = await request.body()
    except ClientDisconnect:
        if trace:
            trace.save_request(b"", request.headers.get("content-type"))
            trace.save_response(499, b"", None, (time.perf_counter() - started) * 1000)
        return Response(status_code=499)

    if trace:
        trace.save_request(body_bytes, request.headers.get("content-type"))

    async def receive():
        return {"type": "http.request", "body": body_bytes, "more_body": False}

    request = Request(request.scope, receive)
    if trace:
        request.state.trace = trace
    response = await call_next(request)

    resp_body = b""
    async for chunk in response.body_iterator:
        resp_body += chunk

    duration_ms = (time.perf_counter() - started) * 1000
    if trace:
        trace.save_response(
            response.status_code,
            resp_body,
            response.headers.get("content-type"),
            duration_ms,
        )
        log.info(
            "API TRACE | id=%s | %s %s → %d (%.0f ms) | %s",
            trace.trace_id,
            request.method,
            path,
            response.status_code,
            duration_ms,
            trace.dir.name,
        )

    headers = dict(response.headers)
    if trace:
        headers["X-Trace-Id"] = trace.trace_id
        headers["X-Trace-Dir"] = str(trace.dir.relative_to(Path(__file__).parent))

    return Response(
        content=resp_body,
        status_code=response.status_code,
        headers=headers,
        media_type=response.media_type,
    )


DOC_DIR = Path(__file__).parent / "docs"
DOC_DIR.mkdir(exist_ok=True)

# In-memory session store:  session_id -> {"history": [...]}
# History uses the provider-neutral format from llm_provider.py.
_sessions: dict[str, dict] = {}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _session(session_id: str) -> dict:
    if session_id not in _sessions:
        _sessions[session_id] = {"history": []}
    return _sessions[session_id]


def _doc_path(name: str) -> Path:
    path = DOC_DIR / name
    if not path.exists():
        raise HTTPException(404, f"Document '{name}' not found.")
    return path


# Mammoth style map: maps Word paragraph/run styles → HTML tags.
# Extend this list to handle more of your document's custom styles.
_MAMMOTH_STYLE_MAP = """
p[style-name='Heading 1']        => h1:fresh
p[style-name='Heading 2']        => h2:fresh
p[style-name='Heading 3']        => h3:fresh
p[style-name='Heading 4']        => h4:fresh
p[style-name='Title']            => h1.doc-title:fresh
p[style-name='Subtitle']         => p.doc-subtitle:fresh
p[style-name='List Bullet']      => ul > li:fresh
p[style-name='List Bullet 2']    => ul > li:fresh
p[style-name='List Number']      => ol > li:fresh
p[style-name='List Number 2']    => ol > li:fresh
p[style-name='Quote']            => blockquote:fresh
p[style-name='Intense Quote']    => blockquote.intense:fresh
p[style-name='Caption']          => p.caption:fresh
r[style-name='Strong']           => strong
r[style-name='Emphasis']         => em
r[style-name='Intense Emphasis'] => em.intense
"""


def _to_html(doc_path: Path) -> str:
    """Convert a .docx to styled HTML using mammoth with Word-style mappings."""
    with open(doc_path, "rb") as fh:
        result = mammoth.convert_to_html(fh, style_map=_MAMMOTH_STYLE_MAP)
    return result.value


PREVIEW_WRAPPER = """\
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    /* ── Page shell ── */
    html, body {{
      margin: 0;
      padding: 0;
      background: #e8e8e8;
      font-family: Calibri, 'Segoe UI', Arial, sans-serif;
      font-size: 11pt;
      color: #1a1a1a;
    }}
    .page {{
      background: #fff;
      width: min(816px, 96%);
      margin: 28px auto;
      padding: 96px 96px 96px 96px;
      min-height: 1056px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.18);
      border: 1px solid #d0d0d0;
      line-height: 1.5;
    }}

    /* ── Headings ── */
    h1.doc-title {{
      font-size: 26pt; font-weight: 300; color: #1a1a1a;
      border-bottom: 1px solid #2b579a; padding-bottom: 6pt;
      margin: 0 0 14pt;
    }}
    p.doc-subtitle {{ font-size: 13pt; color: #555; margin: 0 0 20pt; }}
    h1 {{ font-size: 16pt; color: #2e74b5; font-weight: 700; margin: 18pt 0 6pt; }}
    h2 {{ font-size: 13pt; color: #2e74b5; font-weight: 700; margin: 14pt 0 5pt; }}
    h3 {{ font-size: 12pt; color: #5b9bd5; font-weight: 700; font-style: italic; margin: 11pt 0 4pt; }}
    h4 {{ font-size: 11pt; color: #777; font-weight: 700; margin: 10pt 0 4pt; }}

    /* ── Body text ── */
    p {{ margin: 0 0 7pt; }}

    /* ── Lists ── */
    ul, ol {{ margin: 3pt 0 8pt 24pt; padding: 0; }}
    li {{ margin-bottom: 3pt; }}

    /* ── Quotes ── */
    blockquote {{
      border-left: 3px solid #2b579a;
      margin: 8pt 0 8pt 0;
      padding: 4pt 10pt;
      color: #444;
      font-style: italic;
      background: #f5f8ff;
    }}
    blockquote.intense {{
      border-left: 4px solid #1a3c6e;
      background: #eef2fb;
    }}

    /* ── Tables ── */
    table {{
      border-collapse: collapse;
      width: 100%;
      margin: 10pt 0;
      font-size: 10pt;
    }}
    th {{
      background: #2b579a;
      color: #fff;
      font-weight: 700;
      text-align: left;
      padding: 5px 8px;
      border: 1px solid #1e4080;
    }}
    td {{
      padding: 4px 8px;
      border: 1px solid #bdd0e8;
      vertical-align: top;
    }}
    tr:nth-child(even) td {{ background: #f0f5fb; }}

    /* ── Caption ── */
    p.caption {{ font-size: 9pt; color: #888; font-style: italic; margin: 2pt 0 8pt; }}

    /* ── Inline ── */
    strong {{ font-weight: 700; }}
    em {{ font-style: italic; }}
    em.intense {{ font-style: italic; color: #2b579a; }}
    a {{ color: #2b579a; }}
  </style>
</head>
<body>
  <div class="page">{body}</div>
</body>
</html>"""


# ---------------------------------------------------------------------------
# Routes — system
# ---------------------------------------------------------------------------

@app.get("/api/health")
def health():
    return {"status": "ok", "tracing": tracing_enabled()}


class TraceAppendRequest(BaseModel):
    name: str = "client"
    data: dict | list | str | int | float | bool | None = None


@app.post("/api/trace/{trace_id}/append")
def trace_append(trace_id: str, req: TraceAppendRequest):
    """Append frontend-only execution data to an existing trace folder."""
    path = append_extra(trace_id, req.name, req.data)
    if path is None:
        raise HTTPException(404, f"Trace '{trace_id}' not found.")
    log.info("API TRACE APPEND | id=%s | %s", trace_id, path.name)
    return {"trace_id": trace_id, "saved_as": path.name}


@app.get("/api/traces")
def list_traces(limit: int = 30):
    """List recent API trace folders (newest first)."""
    return {
        "tracing": tracing_enabled(),
        "trace_root": str((Path(__file__).parent / "logs" / "traces").relative_to(Path(__file__).parent)),
        "traces": list_recent_traces(limit),
    }


@app.get("/api/models")
def list_models():
    return {"models": ALL_MODELS}


# ---------------------------------------------------------------------------
# Routes — documents
# ---------------------------------------------------------------------------

@app.get("/api/documents")
def list_documents():
    docs = sorted(p.name for p in DOC_DIR.glob("*.docx"))
    return {"documents": docs}


@app.post("/api/documents", status_code=201)
def create_document(name: str = Body(..., embed=True)):
    """Create a new empty .docx file."""
    if not name.endswith(".docx"):
        name += ".docx"
    path = DOC_DIR / name
    DocEditor(path)  # creates the file if it doesn't exist
    log.info("CREATE DOC | %s", name)
    return {"name": path.name}


@app.post("/api/documents/upload", status_code=201)
async def upload_document(file: UploadFile = File(...)):
    if not (file.filename or "").endswith(".docx"):
        raise HTTPException(400, "Only .docx files are accepted.")
    target = DOC_DIR / file.filename
    stem, suffix = target.stem, target.suffix or ".docx"
    n = 1
    while target.exists():
        target = DOC_DIR / f"{stem} ({n}){suffix}"
        n += 1
    content = await file.read()
    target.write_bytes(content)
    log.info("UPLOAD | %s (%d bytes) → %s", file.filename, len(content), target.name)
    return {"saved_as": target.name}


@app.get("/api/documents/{name}/download")
def download_document(name: str):
    path = _doc_path(name)
    return FileResponse(
        str(path),
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        filename=name,
    )


@app.get("/api/documents/{name}/preview", response_class=HTMLResponse)
def preview_document(name: str):
    """Return the document as styled HTML (via mammoth) for the iframe preview."""
    path = _doc_path(name)
    try:
        body = _to_html(path)
    except Exception as exc:
        raise HTTPException(500, f"Preview failed: {exc}") from exc
    return PREVIEW_WRAPPER.format(body=body)


# ---------------------------------------------------------------------------
# Routes — chat / agent
# ---------------------------------------------------------------------------

class ChatRequest(BaseModel):
    session_id: str
    doc_name: str
    message: str
    model: str = "gemini-2.5-flash"


@app.post("/api/chat")
def chat(req: ChatRequest):
    """Run one agent turn and return the reply + an updated HTML preview."""
    doc_path = _doc_path(req.doc_name)
    session = _session(req.session_id)
    history = session["history"]

    provider = get_provider(req.model)
    if provider is None:
        raise HTTPException(
            400,
            f"No API key configured for model '{req.model}'. "
            "Set GEMINI_API_KEY or OPENAI_API_KEY in .env.",
        )

    editor = DocEditor(doc_path)

    log.info(
        "API CHAT | session=%s | model=%s | doc=%s | prompt=%r",
        req.session_id, req.model, req.doc_name, truncate(req.message, 200),
    )

    def _dispatch(name: str, args: dict) -> str:
        result = dispatch(editor, name, args)
        log.info(
            "TOOL | %s(%s) → %s",
            name,
            truncate(json.dumps(args, default=str), 120),
            truncate(result, 120),
        )
        return result

    try:
        reply, tool_log = provider.run_agent_loop(
            system=SYSTEM_PROMPT,
            tools=DOC_TOOLS,
            history=history,
            user_text=req.message,
            dispatch=_dispatch,
        )
    except Exception as exc:
        log.exception("API CHAT FAILED | %s", exc)
        raise HTTPException(500, str(exc)) from exc

    # Return the updated document as HTML so the frontend can refresh its preview.
    try:
        preview_html = _to_html(doc_path)
    except Exception:
        preview_html = ""

    log.info(
        "API CHAT DONE | session=%s | tools=%d | reply=%r",
        req.session_id, len(tool_log), truncate(reply, 200),
    )

    return {
        "reply": reply,
        "tool_calls": tool_log,
        "preview_html": preview_html,
    }


# TODO (checklist feature): add POST /api/documents/{name}/checklist
#   - Run structural rules (has_title, has_headings, no_placeholder_text, …)
#   - Run LLM content audit via provider.complete() (single-shot JSON response)
#   - Persist results as a sidecar <name>.checklist.json beside the .docx
#   - Return list of {id, label, kind, passed, note} results
#   Frontend: add a ChecklistPanel tab in DocumentPanel.jsx

@app.delete("/api/sessions/{session_id}")
def clear_session(session_id: str):
    """Wipe conversation history for the given session."""
    _sessions.pop(session_id, None)
    log.info("SESSION CLEARED | %s", session_id)
    return {"cleared": session_id}


# ---------------------------------------------------------------------------
# Routes — highlight & rewrite (client-side selection mode)
# ---------------------------------------------------------------------------

class UserPreferences(BaseModel):
    language: str = "auto"
    tone: str = "professional"


class TargetSelection(BaseModel):
    selected_text: str
    context_before: str = ""
    context_after: str = ""


class AIEditRequest(BaseModel):
    session_id: str
    user_prompt: str
    target_selection: TargetSelection
    action_intent: str = "rewrite"          # e.g. "rewrite" | "expand" | "summarize"
    model: str = "gemini-2.5-flash"
    user_preferences: UserPreferences = UserPreferences()


_SELECTION_SYSTEM = """\
You are a world-class surgical document editing agent.
Your task: apply the user's instruction to the SELECTED TEXT by producing a minimal set of exact find-and-replace operations.

ABSOLUTE RULES:
1. Output ONLY valid JSON — no prose, no explanation, no markdown fences, no preamble.
2. The JSON MUST follow this exact schema:
   {"action":"batch_replace","edits":[{"action":"replace_exact_text","find":"...","replace":"..."}]}
3. Every "find" value MUST be a verbatim substring of selected_text (100% exact match — same punctuation, spaces, and capitalisation).
4. Granularity rule — always use the SMALLEST safe "find" scope:
   a. APPEND to end of line (add punctuation/text at end):
      "find" = the LAST 4–7 WORDS of the line ONLY — never the entire line.
      "replace" = those same words + the appended text.
      Reason: lines often start with bold/formatted text; replacing the whole line destroys that formatting.
      Example — adding comma: find="and safety points"  replace="and safety points,"
      Example — adding period: find="and temporary workers"  replace="and temporary workers."
   b. PREPEND to start of line (add text at beginning):
      "find" = the FIRST 4–7 WORDS of the line only.
   c. WORD/PHRASE change (replace a word, fix a typo, rephrase):
      "find" = the phrase itself plus 2–3 words on each side for uniqueness.
   NEVER replace an entire sentence or line if only the end or beginning needs changing.
   NEVER use a single character, symbol, or string shorter than 3 words as "find".
5. One edit per line — if N lines each need the same kind of change, produce exactly N separate edits, one per line. Do NOT share a single short "find" across multiple lines.
6. NEVER output context_before or context_after content.
7. NEVER add or remove bullet characters (•, -, *), numbering, or markdown — those are paragraph-style properties, not text content.
8. FORMATTING PRESERVATION — the editor keeps bold, italic, highlight colour, and list/table structure automatically ONLY when edits are surgical:
   a. Lines that mix formatted and plain text (e.g. a bold "Day 1 —" prefix followed by regular body text): NEVER include the formatted prefix in "find". Target only the plain-text tail or the specific word being changed.
   b. Headings / chapter titles that are entirely bold: change only the specific word(s) inside the title, with 2–3 neighbouring words for uniqueness — never the full heading.
   c. Highlighted or coloured spans: "find" must sit entirely inside one continuous phrase; do not span from plain text into highlighted text or across highlight boundaries.
   d. Lists: each list item is one line. Apply one edit per item. Never include list markers or numbering in "find"/"replace".
   e. Tables: treat each cell's text like a mini-paragraph; scope "find" to the cell phrase being changed, not the whole row.\
"""


def _build_selection_prompt(req: AIEditRequest) -> str:
    sel = req.target_selection
    prefs = req.user_preferences

    parts = [
        f"User instruction: {req.user_prompt}",
        f"Preferred language: {prefs.language}",
        f"Preferred tone: {prefs.tone}",
        "",
        "=== Context BEFORE selection (READ-ONLY — never include in output) ===",
        sel.context_before.strip() or "(none)",
        "",
        "=== SELECTED TEXT — your only edit target ===",
        sel.selected_text,
        "",
        "=== Context AFTER selection (READ-ONLY — never include in output) ===",
        sel.context_after.strip() or "(none)",
        "",
        "Analyse the selected text against the instruction.",
        "Identify the minimum set of verbatim substrings to change.",
        "Output the batch_replace JSON only.",
    ]
    return "\n".join(parts)


@app.post("/api/edit-selection")
def edit_selection(req: AIEditRequest, request: Request):
    """Surgical batch-replace editing of a highlighted text range.

    The LLM analyses the selected text and returns a ``batch_replace`` JSON
    containing a list of verbatim find→replace pairs.  The frontend executes
    each pair via Syncfusion's searchModule so paragraph-level formatting
    (bullets, table structure, indentation) is never touched.
    """
    provider = get_provider(req.model)
    if provider is None:
        raise HTTPException(
            400,
            f"No API key configured for model '{req.model}'. "
            "Set GEMINI_API_KEY or OPENAI_API_KEY in .env.",
        )

    prompt = _build_selection_prompt(req)
    log.info(
        "EDIT-SELECTION | session=%s | model=%s | selected=%r",
        req.session_id, req.model,
        truncate(req.target_selection.selected_text, 120),
    )

    try:
        raw = provider.complete(_SELECTION_SYSTEM, prompt)
    except Exception as exc:
        log.exception("EDIT-SELECTION FAILED | %s", exc)
        raise HTTPException(500, str(exc)) from exc

    # Strip markdown code fences if the model wrapped the JSON anyway.
    raw = raw.strip()
    raw = re.sub(r'^```[a-z]*\n?', '', raw)
    raw = re.sub(r'\n?```$', '', raw)
    raw = raw.strip()

    log.info("EDIT-SELECTION RAW | %r", truncate(raw, 400))

    trace = getattr(request.state, "trace", None)
    if trace:
        trace.save_extra("llm_prompt", {"system": _SELECTION_SYSTEM, "user": prompt})
        trace.save_extra("llm_raw", {"raw": raw})

    try:
        result = json.loads(raw)
    except json.JSONDecodeError as exc:
        log.error("EDIT-SELECTION | non-JSON response: %r", truncate(raw, 300))
        raise HTTPException(
            500,
            "The AI returned an unexpected format — please try again.",
        ) from exc

    if result.get("action") != "batch_replace" or not isinstance(result.get("edits"), list):
        log.error("EDIT-SELECTION | bad schema: %r", result)
        raise HTTPException(500, "AI response schema invalid — please try again.")

    log.info(
        "EDIT-SELECTION DONE | session=%s | edits=%d",
        req.session_id, len(result["edits"]),
    )
    return result  # { action: "batch_replace", edits: [{action, find, replace}, ...] }


# ---------------------------------------------------------------------------
# Syncfusion → Python server: save a document edited in the Rich Editor
# ---------------------------------------------------------------------------

class SyncfusionSaveRequest(BaseModel):
    name: str    # target filename in docs/
    sfdt: str    # SFDT JSON string from Syncfusion's editor.serialize()


@app.post("/api/documents/save-sfdt", status_code=200)
async def save_sfdt(req: SyncfusionSaveRequest):
    """Accept SFDT from the Syncfusion Rich Editor and convert it back to .docx.

    Flow in the frontend:
        const sfdt = editorRef.current.documentEditor.serialize();
        await fetch('/api/documents/save-sfdt', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ name: selectedDoc, sfdt }),
        });

    The conversion uses Syncfusion's public service (same one used for import).
    For production, self-host the Syncfusion Word Processor server and point
    SYNCFUSION_SERVICE to it.
    """
    import httpx

    SYNCFUSION_SERVICE = "https://ej2services.syncfusion.com/production/web-services/"

    # Ask Syncfusion's service to convert SFDT → .docx bytes.
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{SYNCFUSION_SERVICE}api/documenteditor/ExportSFDT",
                content=req.sfdt.encode(),
                headers={"Content-Type": "application/json"},
            )
        if resp.status_code != 200:
            raise HTTPException(502, f"Syncfusion export service error {resp.status_code}")
    except httpx.RequestError as exc:
        raise HTTPException(502, f"Could not reach Syncfusion service: {exc}") from exc

    target = DOC_DIR / req.name
    target.write_bytes(resp.content)
    log.info("SFDT SAVE | %s (%d bytes)", req.name, len(resp.content))
    return {"saved_as": req.name, "bytes": len(resp.content)}
