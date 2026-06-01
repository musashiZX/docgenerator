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
from google import genai
from google.genai import types

from doc_editor import DocEditor, dispatch, doc_to_html, html_to_doc
from doc_editor_gemini import GEMINI_TOOL, SYSTEM_PROMPT
import checklist as cl
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


# -------- session state --------

def reset_chat():
    st.session_state.messages = []  # display log: list of {role, text, tool_calls?}
    st.session_state.history = []   # genai Content list


for key, default in [
    ("messages", []),
    ("history", []),
    ("doc_path", None),
    ("model", DEFAULT_MODEL),
    ("last_saved", None),
    ("checklist_results", None),  # list[dict] or None
    ("checklist_sig", None),       # doc signature when results were taken
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

def get_client() -> genai.Client | None:
    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        return None
    return genai.Client(api_key=api_key)


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


def run_agent(client: genai.Client, model: str, editor: DocEditor, user_text: str):
    """Run one agent turn (model + tool loop). Yields display events."""
    history = st.session_state.history
    history.append(types.Content(role="user", parts=[types.Part(text=user_text)]))

    config = types.GenerateContentConfig(
        system_instruction=SYSTEM_PROMPT,
        tools=[GEMINI_TOOL],
    )

    final_text = ""
    tool_log: list[dict] = []

    log.info("AGENT START | model=%s | doc=%s | prompt=%r",
             model, editor.path.name, truncate(user_text, 300))

    for step in range(20):
        response = client.models.generate_content(
            model=model,
            contents=history,
            config=config,
        )
        candidate = response.candidates[0]
        parts = candidate.content.parts or []
        history.append(candidate.content)

        function_calls = [p.function_call for p in parts if p.function_call]
        text_chunks = [p.text for p in parts if getattr(p, "text", None)]
        if text_chunks:
            final_text += "".join(text_chunks)

        if not function_calls:
            log.info("AGENT END | step=%d | reply=%r", step, truncate(final_text, 300))
            break

        function_response_parts = []
        for call in function_calls:
            args = dict(call.args) if call.args else {}
            result = dispatch(editor, call.name, args)
            log.info("TOOL CALL | %s(%s) -> %s",
                     call.name, truncate(json.dumps(args, default=str), 200),
                     truncate(result, 200))
            tool_log.append({"name": call.name, "args": args, "result": result})
            function_response_parts.append(
                types.Part.from_function_response(
                    name=call.name,
                    response={"result": result},
                )
            )
        history.append(types.Content(role="user", parts=function_response_parts))

    return final_text or "_(no reply)_", tool_log


# -------- sidebar --------

with st.sidebar:
    st.title("📝 Word Doc Agent")

    if not (os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")):
        st.error("GEMINI_API_KEY not set in .env")

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

    st.subheader("Model")
    st.session_state.model = st.selectbox(
        "Gemini model",
        [
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-flash-latest",
            "gemini-pro-latest",
        ],
        index=0,
    )

    if st.button("🗑️ Clear chat history", use_container_width=True):
        reset_chat()
        st.rerun()


# -------- main panel --------

if not st.session_state.doc_path:
    st.info("Pick or create a `.docx` file in the sidebar to get started.")
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
            client = None if run_struct_only else get_client()
            mode = "structural-only" if run_struct_only else "structural+llm"
            log.info("CHECKLIST RUN | mode=%s | doc=%s | items=%d",
                     mode, editor_doc.path.name, len(items))
            with st.spinner("Running checks…"):
                try:
                    results = cl.run_all(client, st.session_state.model, editor_doc, items)
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
        client = get_client()
        if client is None:
            st.error("Set GEMINI_API_KEY in .env first.")
            st.stop()

        st.session_state.messages.append({"role": "user", "text": prompt})

        editor = DocEditor(st.session_state.doc_path)
        with st.spinner("Thinking…"):
            try:
                reply, tool_log = run_agent(client, st.session_state.model, editor, prompt)
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
