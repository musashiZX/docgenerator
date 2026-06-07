"""Streamlit UI for the Word document agent.

Run:
    streamlit run app.py
"""

import json
import os
import re
from html import escape
from pathlib import Path

import streamlit as st
import streamlit.components.v1 as components
from dotenv import load_dotenv

from doc_editor import DocEditor, dispatch, doc_to_html, html_to_doc, TOOLS as DOC_TOOLS
from doc_editor_gemini import SYSTEM_PROMPT
from pdf_to_markdown import (
    MD_SYSTEM_PROMPT,
    MD_TOOLS,
    convert_markdown_to_pdf,
    convert_pdf_to_markdown,
    get_pipeline_step_paths,
    md_dispatch,
    read_markdown,
    write_markdown,
)
import checklist as cl
from llm_provider import (
    ALL_MODELS,
    get_provider,
    missing_key_message,
    provider_name_for_model,
)
from app_logger import get_logger, truncate

log = get_logger()

try:
    from streamlit_quill import st_quill
    HAS_QUILL = True
except ImportError:  # streamlit-quill not installed
    HAS_QUILL = False

load_dotenv()

st.set_page_config(page_title="Word Doc Agent", page_icon="📝", layout="wide")

DEFAULT_MODEL = "gemini-2.5-flash"
DOC_DIR = Path(__file__).parent / "docs"
DOC_DIR.mkdir(exist_ok=True)
PDF_DIR = Path(__file__).parent / "pdfs"
PDF_DIR.mkdir(exist_ok=True)
MD_DIR = Path(__file__).parent / "markdown"
MD_DIR.mkdir(exist_ok=True)


# -------- session state --------

def reset_chat():
    st.session_state.messages = []  # display log: list of {role, text, tool_calls?}
    st.session_state.history = []   # provider-neutral history (see llm_provider)


for key, default in [
    ("messages", []),
    ("history", []),
    ("doc_path", None),
    ("model", DEFAULT_MODEL),
    ("last_saved", None),
    ("checklist_results", None),  # list[dict] or None
    ("checklist_sig", None),       # doc signature when results were taken
    ("md_path", None),             # currently-open markdown file (Path)
    ("pdf_pending", None),         # dict {name, bytes} waiting for Transform click
    ("md_messages", []),           # display log for the markdown chat
    ("md_history", []),            # provider-neutral history for the markdown chat
]:
    if key not in st.session_state:
        st.session_state[key] = default


SUPPORTED_STYLES = [
    "Normal",
    "Title",
    "Heading 1",
    "Heading 2",
    "Heading 3",
    "List Bullet",
    "List Number",
    "Quote",
]


def doc_signature(path: Path) -> str:
    """Stable key for the editor widgets — bumps when the file is rewritten."""
    try:
        return f"{path.name}:{path.stat().st_mtime_ns}"
    except OSError:
        return path.name


# -------- helpers --------

def get_active_provider():
    """Build the LLM provider for the model currently selected in the sidebar."""
    return get_provider(st.session_state.model)


def key_error_message() -> str:
    return missing_key_message(st.session_state.model)


PAGE_CSS = """
:root {
  --page-bg: #f3f3f3;
  --paper: #ffffff;
  --ink: #202020;
  --accent: #2b579a;       /* Word blue */
  --accent-2: #2e74b5;     /* Heading 1 blue */
  --accent-3: #5b9bd5;     /* Heading 2 lighter */
  --rule: #e1e1e1;
}
* { box-sizing: border-box; }
html, body {
  margin: 0;
  padding: 0;
  background: var(--page-bg);
  font-family: Calibri, "Segoe UI", Arial, sans-serif;
  color: var(--ink);
}
.page-wrap {
  padding: 24px 0;
  min-height: 100%;
}
.page {
  background: var(--paper);
  width: min(816px, 95%);
  margin: 0 auto 24px;
  padding: 96px 96px 96px 96px;       /* ~1in margins at 96dpi */
  min-height: 1056px;                 /* ~Letter height */
  box-shadow: 0 1px 3px rgba(0,0,0,0.18), 0 6px 18px rgba(0,0,0,0.12);
  border: 1px solid #d8d8d8;
  font-size: 11pt;
  line-height: 1.4;
}
.page p { margin: 0 0 8pt 0; }
.title {
  font-size: 28pt;
  font-weight: 300;
  color: var(--ink);
  border-bottom: 1px solid var(--accent);
  padding-bottom: 4pt;
  margin: 0 0 14pt 0;
  letter-spacing: 0.2px;
}
.h1 { font-size: 16pt; color: var(--accent-2); font-weight: 600; margin: 14pt 0 6pt; }
.h2 { font-size: 13pt; color: var(--accent-3); font-weight: 600; margin: 12pt 0 4pt; }
.h3 { font-size: 12pt; color: var(--accent-3); font-style: italic; font-weight: 600; margin: 10pt 0 4pt; }
.quote {
  border-left: 3px solid var(--accent);
  padding: 4pt 10pt;
  color: #404040;
  font-style: italic;
  margin: 6pt 0;
  background: #fafbfd;
}
ul.bullets, ol.numbers { margin: 4pt 0 8pt 24pt; padding: 0; }
ul.bullets li, ol.numbers li { margin: 0 0 4pt 0; }
.empty {
  color: #999;
  font-style: italic;
  text-align: center;
  margin-top: 200px;
}
.footer-note {
  text-align: center;
  font-size: 9pt;
  color: #909090;
  margin-top: 8px;
}
"""


def _close_lists(out: list, state: dict) -> None:
    if state.get("list_kind"):
        out.append(f"</{state['list_kind']}>")
        state["list_kind"] = None


def render_doc_html(path: Path) -> str:
    """Render the current document as Word-styled HTML."""
    paragraphs = []
    if path.exists():
        editor = DocEditor(path)
        paragraphs = editor.doc.paragraphs

    body: list[str] = []
    state = {"list_kind": None}  # "ul" | "ol" | None

    if not paragraphs:
        body.append('<div class="empty">This document is empty.<br>Ask the agent to add some content.</div>')
    else:
        for p in paragraphs:
            style = p.style.name if p.style else "Normal"
            text = escape(p.text) if p.text else "&nbsp;"

            if style == "List Bullet":
                if state["list_kind"] != "ul":
                    _close_lists(body, state)
                    body.append('<ul class="bullets">')
                    state["list_kind"] = "ul"
                body.append(f"<li>{text}</li>")
                continue
            if style == "List Number":
                if state["list_kind"] != "ol":
                    _close_lists(body, state)
                    body.append('<ol class="numbers">')
                    state["list_kind"] = "ol"
                body.append(f"<li>{text}</li>")
                continue

            _close_lists(body, state)
            if style == "Title":
                body.append(f'<p class="title">{text}</p>')
            elif style == "Heading 1":
                body.append(f'<p class="h1">{text}</p>')
            elif style == "Heading 2":
                body.append(f'<p class="h2">{text}</p>')
            elif style == "Heading 3":
                body.append(f'<p class="h3">{text}</p>')
            elif style == "Quote":
                body.append(f'<p class="quote">{text}</p>')
            else:
                body.append(f"<p>{text}</p>")
        _close_lists(body, state)

    filename = escape(path.name) if path else ""
    return f"""<!doctype html>
<html><head><meta charset="utf-8"><style>{PAGE_CSS}</style></head>
<body>
  <div class="page-wrap">
    <div class="page">
      {''.join(body)}
    </div>
    <div class="footer-note">{filename}</div>
  </div>
</body></html>"""


def run_agent(provider, editor: DocEditor, user_text: str):
    """Run one agent turn (model + tool loop) against the Word document."""
    history = st.session_state.history

    log.info("AGENT START | model=%s | doc=%s | prompt=%r",
             provider.model, editor.path.name, truncate(user_text, 300))

    def _dispatch(name: str, args: dict) -> str:
        result = dispatch(editor, name, args)
        log.info("TOOL CALL | %s(%s) -> %s",
                 name, truncate(json.dumps(args, default=str), 200),
                 truncate(result, 200))
        return result

    final_text, tool_log = provider.run_agent_loop(
        system=SYSTEM_PROMPT,
        tools=DOC_TOOLS,
        history=history,
        user_text=user_text,
        dispatch=_dispatch,
    )
    log.info("AGENT END | reply=%r", truncate(final_text, 300))
    return final_text, tool_log


def run_md_agent(provider, md_path: Path, user_text: str):
    """Run one agent turn against the markdown file."""
    history = st.session_state.md_history

    log.info("MD AGENT START | model=%s | md=%s | prompt=%r",
             provider.model, md_path.name, truncate(user_text, 300))

    def _dispatch(name: str, args: dict) -> str:
        result = md_dispatch(md_path, name, args)
        log.info("MD TOOL CALL | %s(%s) -> %s",
                 name, truncate(json.dumps(args, default=str), 200),
                 truncate(result, 200))
        return result

    final_text, tool_log = provider.run_agent_loop(
        system=MD_SYSTEM_PROMPT,
        tools=MD_TOOLS,
        history=history,
        user_text=user_text,
        dispatch=_dispatch,
    )
    # Keep the displayed tool results compact.
    for entry in tool_log:
        entry["result"] = truncate(entry["result"], 400)
    log.info("MD AGENT END | reply=%r", truncate(final_text, 300))
    return final_text, tool_log


# -------- sidebar --------

with st.sidebar:
    st.title("📝 Word Doc Agent")

    has_gemini = bool(os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY"))
    has_openai = bool(os.environ.get("OPENAI_API_KEY"))
    if not has_gemini and not has_openai:
        st.error("No API key set in .env (need GEMINI_API_KEY or OPENAI_API_KEY).")

    st.subheader("Document")

    uploaded = st.file_uploader(
        "Upload a .docx",
        type=["docx"],
        help="Saved into the docs/ folder. If a file with the same name already exists, it's renamed to avoid overwriting.",
    )
    if uploaded is not None:
        upload_key = f"{uploaded.name}:{uploaded.size}"
        if st.session_state.get("last_upload_key") != upload_key:
            target = DOC_DIR / uploaded.name
            stem, suffix = target.stem, target.suffix or ".docx"
            n = 1
            while target.exists():
                target = DOC_DIR / f"{stem} ({n}){suffix}"
                n += 1
            target.write_bytes(uploaded.getvalue())
            log.info("UPLOAD | saved %s (%d bytes) as %s",
                     uploaded.name, uploaded.size, target.name)
            st.session_state.last_upload_key = upload_key
            st.session_state.doc_path = target
            reset_chat()
            st.success(f"Uploaded `{target.name}`.")
            st.rerun()

    existing = sorted(p.name for p in DOC_DIR.glob("*.docx"))
    options = ["<new file…>"] + existing
    choice = st.selectbox("Open", options, index=0 if not existing else 1)

    if choice == "<new file…>":
        new_name = st.text_input("New filename", value="my-doc.docx")
        if st.button("Create / Open", use_container_width=True):
            if not new_name.endswith(".docx"):
                new_name += ".docx"
            path = DOC_DIR / new_name
            DocEditor(path)  # ensures the file exists
            st.session_state.doc_path = path
            reset_chat()
            st.rerun()
    else:
        path = DOC_DIR / choice
        if st.session_state.doc_path != path:
            st.session_state.doc_path = path
            reset_chat()
            st.rerun()

    if st.session_state.doc_path:
        st.caption(f"Editing: `{st.session_state.doc_path.name}`")
        with open(st.session_state.doc_path, "rb") as f:
            st.download_button(
                "⬇️ Download .docx",
                f.read(),
                file_name=st.session_state.doc_path.name,
                mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                use_container_width=True,
            )

    st.subheader("PDF → Markdown")
    pdf_uploaded = st.file_uploader(
        "Upload a .pdf",
        type=["pdf"],
        key="pdf_uploader",
        help="Click 'Transform PDF → Markdown' below to convert. The markdown is saved into the markdown/ folder.",
    )
    if pdf_uploaded is not None:
        st.session_state.pdf_pending = {
            "name": pdf_uploaded.name,
            "bytes": pdf_uploaded.getvalue(),
        }
        st.caption(f"Ready: `{pdf_uploaded.name}` ({len(st.session_state.pdf_pending['bytes'])} bytes)")

    transform_disabled = st.session_state.pdf_pending is None
    if st.button(
        "✨ Transform PDF → Markdown",
        use_container_width=True,
        disabled=transform_disabled,
        type="primary",
    ):
        provider = get_active_provider()
        if provider is None:
            st.error(key_error_message())
        else:
            pending = st.session_state.pdf_pending
            with st.spinner(
                f"Converting `{pending['name']}` …  "
                "(Step 1: Marker extraction · Step 2: Gemini color enrichment)"
            ):
                try:
                    # Step 0 — persist the original PDF for traceability.
                    pdf_target = PDF_DIR / pending["name"]
                    stem, suffix = pdf_target.stem, pdf_target.suffix or ".pdf"
                    n = 1
                    while pdf_target.exists():
                        pdf_target = PDF_DIR / f"{stem} ({n}){suffix}"
                        n += 1
                    pdf_target.write_bytes(pending["bytes"])
                    log.info(
                        "PDF→MD | STEP 0 | original PDF saved as %s (%d bytes)",
                        pdf_target.name,
                        len(pending["bytes"]),
                    )

                    result = convert_pdf_to_markdown(
                        provider,
                        pending["bytes"],
                        pending["name"],
                        MD_DIR,
                    )

                    steps_saved = []
                    if result.step1_marker_path:
                        steps_saved.append(f"`{result.step1_marker_path.name}`")
                    if result.step2_enriched_path:
                        steps_saved.append(f"`{result.step2_enriched_path.name}`")

                    st.session_state.md_path = result.markdown_path
                    st.session_state.md_messages = []
                    st.session_state.md_history = []
                    st.session_state.pdf_pending = None

                    step_note = (
                        f"  \nPipeline steps saved: {', '.join(steps_saved)}"
                        if steps_saved
                        else ""
                    )
                    st.success(
                        f"Saved `{result.markdown_path.name}` and "
                        f"`{result.json_path.name}` in `markdown/`."
                        + step_note
                    )
                    st.rerun()
                except Exception as e:  # noqa: BLE001
                    log.exception("PDF→MD FAILED | %s", e)
                    st.error(f"Conversion failed: {e}")

    md_files = sorted(p.name for p in MD_DIR.glob("*.md"))
    if md_files:
        current_md = st.session_state.md_path.name if st.session_state.md_path else None
        idx = md_files.index(current_md) if current_md in md_files else 0
        md_choice = st.selectbox("Open markdown", md_files, index=idx, key="md_choice")
        chosen = MD_DIR / md_choice
        if st.session_state.md_path != chosen:
            st.session_state.md_path = chosen
            st.session_state.md_messages = []
            st.session_state.md_history = []
            st.rerun()

        with open(chosen, "rb") as f:
            st.download_button(
                "⬇️ Download .md",
                f.read(),
                file_name=chosen.name,
                mime="text/markdown",
                use_container_width=True,
                key=f"dl-md::{chosen.name}",
            )

        if st.button("📄 Export to PDF", use_container_width=True, key=f"export-pdf::{chosen.name}"):
            with st.spinner("Generating PDF …"):
                try:
                    pdf_out = chosen.with_suffix(".pdf")
                    convert_markdown_to_pdf(chosen, pdf_out)
                    log.info("EXPORT PDF | %s → %s", chosen.name, pdf_out.name)
                    with open(pdf_out, "rb") as fp:
                        st.download_button(
                            "⬇️ Download PDF",
                            fp.read(),
                            file_name=pdf_out.name,
                            mime="application/pdf",
                            use_container_width=True,
                            key=f"dl-pdf::{pdf_out.name}",
                        )
                except Exception as e:  # noqa: BLE001
                    log.exception("EXPORT PDF FAILED | %s", e)
                    st.error(f"PDF export failed: {e}")

    st.subheader("Model")
    default_idx = ALL_MODELS.index(st.session_state.model) if st.session_state.model in ALL_MODELS else 0
    st.session_state.model = st.selectbox(
        "Model (Gemini or OpenAI GPT)",
        ALL_MODELS,
        index=default_idx,
    )
    _active = provider_name_for_model(st.session_state.model)
    if _active == "openai" and not has_openai:
        st.warning("Selected an OpenAI model but OPENAI_API_KEY is not set in .env.")
    elif _active == "gemini" and not has_gemini:
        st.warning("Selected a Gemini model but GEMINI_API_KEY is not set in .env.")

    if st.button("🗑️ Clear chat history", use_container_width=True):
        reset_chat()
        st.rerun()


# -------- main panel --------

if not st.session_state.doc_path and not st.session_state.md_path:
    st.info(
        "Pick or create a `.docx` file in the sidebar, "
        "or upload a PDF and click **Transform PDF → Markdown** to get started."
    )
    st.stop()

# Mode selector: only shown if both kinds of files exist in this session.
mode_options = []
if st.session_state.doc_path:
    mode_options.append("Word document")
if st.session_state.md_path:
    mode_options.append("Markdown")

if len(mode_options) > 1:
    mode = st.radio(
        "Working on",
        mode_options,
        horizontal=True,
        key="active_mode",
    )
else:
    mode = mode_options[0]

if mode == "Markdown":
    md_path: Path = st.session_state.md_path
    json_path = md_path.with_suffix(".json")

    left, right = st.columns([2, 3])

    with right:
        st.subheader(f"Markdown — `{md_path.name}`")
        tab_edit, tab_preview, tab_steps, tab_json = st.tabs(
            ["Edit", "Preview", "Pipeline Steps", "JSON"]
        )

        current_text = read_markdown(md_path)

        with tab_edit:
            edited = st.text_area(
                "markdown source",
                value=current_text,
                height=600,
                key=f"md-edit::{md_path.name}",
                label_visibility="collapsed",
            )
            cols = st.columns([1, 1, 3])
            if cols[0].button("💾 Save", use_container_width=True, key=f"md-save::{md_path.name}"):
                write_markdown(md_path, edited)
                log.info("MANUAL SAVE | markdown | md=%s", md_path.name)
                st.success("Saved.")
                st.rerun()

        with tab_preview:
            st.markdown(current_text, unsafe_allow_html=True)

        with tab_steps:
            st.caption(
                "Intermediate files saved at each pipeline step. "
                "Only available for files converted with the Marker + Gemini pipeline."
            )
            step_paths = get_pipeline_step_paths(md_path)

            step_labels = {
                "step1_marker": "Step 1 — Marker extraction (raw)",
                "step2_enriched": "Step 2 — Gemini color enrichment",
                "step1_fallback": "Step 1 — Gemini-only fallback",
            }
            found_any = False
            for key, label in step_labels.items():
                path = step_paths.get(key)
                if path:
                    found_any = True
                    with st.expander(f"📄 {label}  (`{path.name}`)"):
                        step_text = path.read_text(encoding="utf-8")
                        st.text_area(
                            label,
                            value=step_text,
                            height=400,
                            key=f"step-view::{path.name}",
                            label_visibility="collapsed",
                            disabled=True,
                        )
                        with open(path, "rb") as sf:
                            st.download_button(
                                f"⬇️ Download {path.name}",
                                sf.read(),
                                file_name=path.name,
                                mime="text/markdown",
                                use_container_width=True,
                                key=f"dl-step::{path.name}",
                            )
            if not found_any:
                st.info(
                    "No pipeline step files found for this document. "
                    "Re-convert the PDF to generate them."
                )

        with tab_json:
            if json_path.exists():
                st.code(json_path.read_text(encoding="utf-8"), language="json")
            else:
                st.info("No matching JSON sidecar found.")

    with left:
        st.subheader("Chat")
        md_chat_box = st.container(height=500)
        with md_chat_box:
            for msg in st.session_state.md_messages:
                with st.chat_message(msg["role"]):
                    if msg.get("tool_calls"):
                        with st.expander(f"🔧 {len(msg['tool_calls'])} tool call(s)"):
                            for call in msg["tool_calls"]:
                                st.code(
                                    f"{call['name']}({json.dumps(call['args'], default=str)})\n"
                                    f"-> {call['result']}",
                                    language="text",
                                )
                    if msg.get("text"):
                        st.markdown(msg["text"])

        prompt = st.chat_input("Tell the agent what to edit in the markdown…")
        if prompt:
            provider = get_active_provider()
            if provider is None:
                st.error(key_error_message())
                st.stop()
            st.session_state.md_messages.append({"role": "user", "text": prompt})
            with st.spinner("Thinking…"):
                try:
                    reply, tool_log = run_md_agent(provider, md_path, prompt)
                    st.session_state.md_messages.append(
                        {"role": "assistant", "text": reply, "tool_calls": tool_log}
                    )
                except Exception as e:  # noqa: BLE001
                    log.exception("MD AGENT FAILED | md=%s | %s", md_path.name, e)
                    st.session_state.md_messages.append(
                        {"role": "assistant", "text": f"⚠️ Error: {e}"}
                    )
            st.rerun()

    st.stop()

left, right = st.columns([2, 3])

with right:
    st.subheader("Document")
    sig = doc_signature(st.session_state.doc_path)

    tab_labels = ["Edit (WYSIWYG)", "Edit (structured)", "Preview", "✅ Checklist"]
    if not HAS_QUILL:
        tab_labels[0] = "Edit (WYSIWYG · disabled)"
    tab_quill, tab_struct, tab_preview, tab_check = st.tabs(tab_labels)

    # ---- WYSIWYG tab ----
    with tab_quill:
        if not HAS_QUILL:
            st.warning(
                "Install `streamlit-quill` to enable the WYSIWYG editor:\n\n"
                "```\npip install streamlit-quill beautifulsoup4\n```"
            )
        else:
            editor_doc = DocEditor(st.session_state.doc_path)
            initial_html = doc_to_html(editor_doc)
            html = st_quill(
                value=initial_html,
                html=True,
                toolbar=[
                    [{"header": [1, 2, 3, False]}],
                    ["bold", "italic", "underline"],
                    [{"list": "ordered"}, {"list": "bullet"}],
                    ["blockquote"],
                    ["clean"],
                ],
                key=f"quill::{sig}",
            )
            cols = st.columns([1, 1, 3])
            if cols[0].button("💾 Save", use_container_width=True, key=f"quill-save::{sig}"):
                try:
                    html_to_doc(editor_doc, html or "")
                    st.session_state.last_saved = "just now"
                    log.info("MANUAL SAVE | wysiwyg | doc=%s", editor_doc.path.name)
                    st.success("Saved.")
                    st.rerun()
                except Exception as e:  # noqa: BLE001
                    log.exception("MANUAL SAVE FAILED | wysiwyg | doc=%s | %s",
                                  editor_doc.path.name, e)
                    st.error(f"Save failed: {e}")
            if st.session_state.last_saved:
                cols[2].caption(f"Last saved: {st.session_state.last_saved}")
            st.caption(
                "Tip: headings, bullet/numbered lists, blockquotes, and bold/italic/underline "
                "round-trip to .docx. Tables, images, and nested lists are not supported yet."
            )

    # ---- Structured tab ----
    with tab_struct:
        editor_doc = DocEditor(st.session_state.doc_path)
        paragraphs = list(editor_doc.doc.paragraphs)

        with st.form(key=f"structured::{sig}", clear_on_submit=False):
            new_rows: list[tuple[str, str, bool]] = []
            for i, p in enumerate(paragraphs):
                style_name = p.style.name if p.style else "Normal"
                if style_name not in SUPPORTED_STYLES:
                    style_name = "Normal"
                c1, c2, c3 = st.columns([5, 2, 1])
                text = c1.text_area(
                    f"¶ {i}",
                    value=p.text,
                    key=f"text-{sig}-{i}",
                    height=70,
                    label_visibility="collapsed",
                )
                style = c2.selectbox(
                    f"style-{i}",
                    SUPPORTED_STYLES,
                    index=SUPPORTED_STYLES.index(style_name),
                    key=f"style-{sig}-{i}",
                    label_visibility="collapsed",
                )
                delete = c3.checkbox(
                    "🗑",
                    key=f"del-{sig}-{i}",
                    help="Delete this paragraph on save",
                )
                new_rows.append((text, style, delete))

            st.markdown("**Add new paragraph at the end**")
            n1, n2 = st.columns([5, 2])
            new_text = n1.text_area(
                "new",
                value="",
                key=f"new-text-{sig}",
                height=70,
                label_visibility="collapsed",
            )
            new_style = n2.selectbox(
                "new-style",
                SUPPORTED_STYLES,
                index=0,
                key=f"new-style-{sig}",
                label_visibility="collapsed",
            )

            submitted = st.form_submit_button("💾 Save changes", use_container_width=True)
            if submitted:
                # Wipe and rebuild — same approach as html_to_doc.
                for p in list(editor_doc.doc.paragraphs):
                    p._element.getparent().remove(p._element)
                for text, style, delete in new_rows:
                    if delete:
                        continue
                    p = editor_doc.doc.add_paragraph(text)
                    editor_doc._apply_style(p, style)
                if new_text.strip():
                    p = editor_doc.doc.add_paragraph(new_text)
                    editor_doc._apply_style(p, new_style)
                if not editor_doc.doc.paragraphs:
                    editor_doc.doc.add_paragraph("")
                editor_doc.save()
                st.session_state.last_saved = "just now"
                log.info("MANUAL SAVE | structured | doc=%s | paragraphs=%d",
                         editor_doc.path.name, len(editor_doc.doc.paragraphs))
                st.success("Saved.")
                st.rerun()

    # ---- Preview tab (read-only Word render) ----
    with tab_preview:
        components.html(
            render_doc_html(st.session_state.doc_path),
            height=900,
            scrolling=True,
        )

    # ---- Checklist tab ----
    with tab_check:
        items = cl.load_checklist(st.session_state.doc_path)

        c1, c2 = st.columns([1, 1])
        run_clicked = c1.button("▶️ Run checks", use_container_width=True, key=f"run-checks::{sig}")
        run_struct_only = c2.button(
            "⚡ Structural only",
            use_container_width=True,
            key=f"run-struct::{sig}",
            help="Skip the LLM audit — fast, no API call.",
        )

        if run_clicked or run_struct_only:
            editor_doc = DocEditor(st.session_state.doc_path)
            provider = None if run_struct_only else get_active_provider()
            mode = "structural-only" if run_struct_only else "structural+llm"
            log.info("CHECKLIST RUN | mode=%s | doc=%s | items=%d",
                     mode, editor_doc.path.name, len(items))
            with st.spinner("Running checks…"):
                try:
                    results = cl.run_all(provider, editor_doc, items)
                    st.session_state.checklist_results = [cl.result_to_dict(r) for r in results]
                    st.session_state.checklist_sig = sig
                    passed = sum(1 for r in results if r.passed is True)
                    failed = sum(1 for r in results if r.passed is False)
                    log.info("CHECKLIST DONE | passed=%d/%d | failed=%d",
                             passed, len(results), failed)
                except Exception as e:  # noqa: BLE001
                    log.exception("CHECKLIST FAILED | %s", e)
                    st.error(f"Check run failed: {e}")

        results = st.session_state.checklist_results
        if results:
            stale = st.session_state.checklist_sig != sig
            if stale:
                st.warning("Document changed since last run — results may be outdated.")
            passed = sum(1 for r in results if r["passed"] is True)
            failed = sum(1 for r in results if r["passed"] is False)
            unknown = sum(1 for r in results if r["passed"] is None)
            st.metric("Passing", f"{passed} / {len(results)}",
                      delta=None if not failed else f"-{failed} failing")
            for r in results:
                icon = "✅" if r["passed"] is True else ("❌" if r["passed"] is False else "❓")
                kind_tag = "·" if r["kind"] == "structural" else "🤖"
                line = f"{icon} {kind_tag} **{r['label']}**"
                if r["passed"] is False or r["passed"] is None:
                    note = r["note"] or ("(no detail)" if r["passed"] is None else "")
                    line += f"  \n&nbsp;&nbsp;&nbsp;&nbsp;<span style='color:#666;font-size:0.9em'>{escape(note)}</span>"
                st.markdown(line, unsafe_allow_html=True)
        else:
            st.info("Click **Run checks** to audit this document.")

        st.divider()
        with st.expander("✏️ Edit checklist"):
            st.caption(
                "Items marked `structural` use built-in deterministic rules: "
                f"{', '.join(sorted(cl.RULES.keys()))}. "
                "Items marked `content` are judged by the LLM from their label."
            )

            edit_key = f"checklist-edit::{sig}"
            if edit_key not in st.session_state:
                st.session_state[edit_key] = [dict(it) for it in items]

            buf = st.session_state[edit_key]
            for idx, it in enumerate(buf):
                cols = st.columns([5, 2, 2, 1])
                it["label"] = cols[0].text_input(
                    "label", value=it.get("label", ""), key=f"cl-label-{sig}-{idx}",
                    label_visibility="collapsed",
                )
                kinds = ["structural", "content"]
                kind_idx = kinds.index(it.get("kind", "content")) if it.get("kind") in kinds else 1
                it["kind"] = cols[1].selectbox(
                    "kind", kinds, index=kind_idx,
                    key=f"cl-kind-{sig}-{idx}", label_visibility="collapsed",
                )
                if it["kind"] == "structural":
                    rule_options = list(cl.RULES.keys())
                    cur_rule = it.get("rule") or it.get("id")
                    rule_idx = rule_options.index(cur_rule) if cur_rule in rule_options else 0
                    it["rule"] = cols[2].selectbox(
                        "rule", rule_options, index=rule_idx,
                        key=f"cl-rule-{sig}-{idx}", label_visibility="collapsed",
                    )
                else:
                    cols[2].caption("(LLM-judged)")
                if cols[3].button("🗑", key=f"cl-del-{sig}-{idx}"):
                    buf.pop(idx)
                    st.rerun()

            add_cols = st.columns([5, 2, 2, 1])
            new_label = add_cols[0].text_input("New item label", key=f"cl-new-label-{sig}",
                                               placeholder="e.g. References section is present")
            new_kind = add_cols[1].selectbox("New kind", ["content", "structural"], key=f"cl-new-kind-{sig}")
            new_rule = None
            if new_kind == "structural":
                new_rule = add_cols[2].selectbox("Rule", list(cl.RULES.keys()), key=f"cl-new-rule-{sig}")
            if add_cols[3].button("➕", key=f"cl-add-{sig}"):
                if new_label.strip():
                    new_id = re.sub(r"[^a-z0-9]+", "_", new_label.lower()).strip("_") or f"item_{len(buf)}"
                    item = {"id": new_id, "label": new_label.strip(), "kind": new_kind}
                    if new_kind == "structural":
                        item["id"] = new_rule  # use rule name so it's stable
                        item["rule"] = new_rule
                    buf.append(item)
                    st.rerun()

            save_cols = st.columns([1, 1, 3])
            if save_cols[0].button("💾 Save checklist", use_container_width=True, key=f"cl-save-{sig}"):
                cl.save_checklist(st.session_state.doc_path, buf)
                st.session_state.checklist_results = None
                st.success("Checklist saved.")
                st.rerun()
            if save_cols[1].button("↺ Reset to default", use_container_width=True, key=f"cl-reset-{sig}"):
                st.session_state[edit_key] = [dict(it) for it in cl.DEFAULT_CHECKLIST]
                cl.save_checklist(st.session_state.doc_path, cl.DEFAULT_CHECKLIST)
                st.session_state.checklist_results = None
                st.rerun()

with left:
    st.subheader("Chat")
    chat_box = st.container(height=500)

    with chat_box:
        for msg in st.session_state.messages:
            with st.chat_message(msg["role"]):
                if msg.get("tool_calls"):
                    with st.expander(f"🔧 {len(msg['tool_calls'])} tool call(s)"):
                        for call in msg["tool_calls"]:
                            st.code(
                                f"{call['name']}({json.dumps(call['args'], default=str)})\n"
                                f"-> {call['result']}",
                                language="text",
                            )
                if msg.get("text"):
                    st.markdown(msg["text"])

    prompt = st.chat_input("Tell the agent what to edit…")
    if prompt:
        provider = get_active_provider()
        if provider is None:
            st.error(key_error_message())
            st.stop()

        st.session_state.messages.append({"role": "user", "text": prompt})

        editor = DocEditor(st.session_state.doc_path)
        with st.spinner("Thinking…"):
            try:
                reply, tool_log = run_agent(provider, editor, prompt)
                st.session_state.messages.append(
                    {"role": "assistant", "text": reply, "tool_calls": tool_log}
                )
            except Exception as e:  # noqa: BLE001
                log.exception("AGENT FAILED | doc=%s | %s",
                              st.session_state.doc_path.name, e)
                st.session_state.messages.append(
                    {"role": "assistant", "text": f"⚠️ Error: {e}"}
                )
        st.rerun()
