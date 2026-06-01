"""Document checklist engine.

Two kinds of items:
- "structural": deterministic Python rules over the python-docx Document
- "content": natural-language items judged by an LLM in one batched call

Each document has a sidecar `<name>.checklist.json` next to the .docx.
If missing, a default checklist is seeded so the user gets useful checks
immediately and can edit from there.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Callable

from doc_editor import DocEditor

# Built-in defaults — the "general check procedure".
DEFAULT_CHECKLIST: list[dict] = [
    # Structural
    {"id": "has_title", "label": "Document has a Title paragraph", "kind": "structural", "rule": "has_title"},
    {"id": "has_headings", "label": "At least one Heading 1 section", "kind": "structural", "rule": "has_headings"},
    {"id": "no_empty_headings", "label": "Every heading is followed by body text", "kind": "structural", "rule": "no_empty_headings"},
    {"id": "no_blank_runs", "label": "No 3+ consecutive blank paragraphs", "kind": "structural", "rule": "no_blank_runs"},
    {"id": "min_words", "label": "Document has at least 100 words", "kind": "structural", "rule": "min_words", "args": {"threshold": 100}},
    {"id": "no_placeholder_text", "label": "No leftover TODO / TBD / Lorem ipsum", "kind": "structural", "rule": "no_placeholder_text"},
    # Content (LLM-judged)
    {"id": "intro_states_purpose", "label": "The introduction states the document's purpose", "kind": "content"},
    {"id": "sections_substantive", "label": "Every heading section has substantive content (not just a title)", "kind": "content"},
    {"id": "has_conclusion", "label": "A conclusion or summary section is present", "kind": "content"},
    {"id": "tone_consistent", "label": "Tone is consistent throughout the document", "kind": "content"},
    {"id": "no_obvious_errors", "label": "No obvious typos, broken sentences, or grammar errors", "kind": "content"},
]

HEADING_STYLES = {"Heading 1", "Heading 2", "Heading 3"}
PLACEHOLDER_PATTERNS = [
    r"\bTODO\b",
    r"\bTBD\b",
    r"\bFIXME\b",
    r"\bLorem ipsum\b",
    r"\[\.\.\.\]",
    r"\[…\]",
    r"\bXXX\b",
]


@dataclass
class Result:
    id: str
    label: str
    kind: str  # "structural" | "content"
    passed: bool | None  # None = not run / inconclusive
    note: str = ""


# ---------- sidecar I/O ----------

def sidecar_path(doc_path: Path) -> Path:
    return doc_path.with_suffix(".checklist.json")


def load_checklist(doc_path: Path) -> list[dict]:
    side = sidecar_path(doc_path)
    if side.exists():
        try:
            data = json.loads(side.read_text(encoding="utf-8"))
            if isinstance(data, list):
                return data
        except (OSError, json.JSONDecodeError):
            pass
    return [dict(item) for item in DEFAULT_CHECKLIST]


def save_checklist(doc_path: Path, items: list[dict]) -> None:
    side = sidecar_path(doc_path)
    side.write_text(json.dumps(items, indent=2, ensure_ascii=False), encoding="utf-8")


# ---------- structural rules ----------

def _rule_has_title(editor: DocEditor) -> tuple[bool, str]:
    for p in editor.doc.paragraphs:
        if p.style and p.style.name == "Title" and p.text.strip():
            return True, ""
    return False, "No Title paragraph found."


def _rule_has_headings(editor: DocEditor) -> tuple[bool, str]:
    for p in editor.doc.paragraphs:
        if p.style and p.style.name == "Heading 1" and p.text.strip():
            return True, ""
    return False, "No Heading 1 paragraph found."


def _rule_no_empty_headings(editor: DocEditor) -> tuple[bool, str]:
    paragraphs = editor.doc.paragraphs
    offenders = []
    for i, p in enumerate(paragraphs):
        style = p.style.name if p.style else ""
        if style not in HEADING_STYLES:
            continue
        # Look ahead for body text before the next heading.
        has_body = False
        for q in paragraphs[i + 1:]:
            qs = q.style.name if q.style else ""
            if qs in HEADING_STYLES:
                break
            if q.text.strip():
                has_body = True
                break
        if not has_body:
            offenders.append(p.text.strip() or f"(empty heading at ¶{i})")
    if offenders:
        sample = ", ".join(offenders[:3])
        more = "" if len(offenders) <= 3 else f" (+{len(offenders) - 3} more)"
        return False, f"Heading(s) without body: {sample}{more}"
    return True, ""


def _rule_no_blank_runs(editor: DocEditor) -> tuple[bool, str]:
    streak = 0
    worst = 0
    for p in editor.doc.paragraphs:
        if not p.text.strip():
            streak += 1
            worst = max(worst, streak)
        else:
            streak = 0
    if worst >= 3:
        return False, f"Found a run of {worst} blank paragraphs."
    return True, ""


def _rule_min_words(editor: DocEditor, threshold: int = 100) -> tuple[bool, str]:
    text = " ".join(p.text for p in editor.doc.paragraphs)
    words = len(text.split())
    if words < threshold:
        return False, f"Only {words} word(s); needs at least {threshold}."
    return True, ""


def _rule_no_placeholder_text(editor: DocEditor) -> tuple[bool, str]:
    text = "\n".join(p.text for p in editor.doc.paragraphs)
    hits = []
    for pat in PLACEHOLDER_PATTERNS:
        if re.search(pat, text, flags=re.IGNORECASE):
            hits.append(pat.strip(r"\b"))
    if hits:
        return False, f"Found placeholder text: {', '.join(sorted(set(hits)))}"
    return True, ""


RULES: dict[str, Callable[..., tuple[bool, str]]] = {
    "has_title": _rule_has_title,
    "has_headings": _rule_has_headings,
    "no_empty_headings": _rule_no_empty_headings,
    "no_blank_runs": _rule_no_blank_runs,
    "min_words": _rule_min_words,
    "no_placeholder_text": _rule_no_placeholder_text,
}


def run_structural(editor: DocEditor, items: list[dict]) -> list[Result]:
    out: list[Result] = []
    for item in items:
        if item.get("kind") != "structural":
            continue
        rule_id = item.get("rule") or item.get("id")
        fn = RULES.get(rule_id)
        if fn is None:
            out.append(Result(item["id"], item["label"], "structural", None,
                              f"Unknown rule '{rule_id}'."))
            continue
        try:
            args = item.get("args") or {}
            passed, note = fn(editor, **args)
            out.append(Result(item["id"], item["label"], "structural", passed, note))
        except Exception as e:  # noqa: BLE001
            out.append(Result(item["id"], item["label"], "structural", None, f"Rule error: {e}"))
    return out


# ---------- LLM audit ----------

LLM_AUDIT_PROMPT = """You audit a Word document against a checklist of qualitative items.

For each item, decide if the document satisfies it. Be strict but fair —
if there's clear evidence the item is met, mark passed=true. If the
document is missing the thing or shows clear violations, mark passed=false
and write a one-sentence note pointing to the issue (quote a snippet if
helpful). If you genuinely can't tell, mark passed=null and explain why.

Return ONLY valid JSON in this exact shape:
{"results": [{"id": "<item id>", "passed": true|false|null, "note": "<short>"}]}

DOCUMENT:
---
%(doc_text)s
---

CHECKLIST ITEMS:
%(items_json)s
"""


def _doc_text(editor: DocEditor) -> str:
    lines = []
    for p in editor.doc.paragraphs:
        style = p.style.name if p.style else "Normal"
        lines.append(f"[{style}] {p.text}")
    return "\n".join(lines) or "(empty document)"


def run_llm_audit(client, model: str, editor: DocEditor, items: list[dict]) -> list[Result]:
    """Run the content-style checks via one Gemini call. Returns one Result per item.

    `client` is a google.genai Client. We import google.genai lazily so the
    structural-only path doesn't need it.
    """
    content_items = [it for it in items if it.get("kind") == "content"]
    if not content_items:
        return []

    from google.genai import types  # local import keeps top-level deps light

    payload = [{"id": it["id"], "label": it["label"]} for it in content_items]
    prompt = LLM_AUDIT_PROMPT % {
        "doc_text": _doc_text(editor),
        "items_json": json.dumps(payload, indent=2, ensure_ascii=False),
    }

    response = client.models.generate_content(
        model=model,
        contents=[types.Content(role="user", parts=[types.Part(text=prompt)])],
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
        ),
    )

    raw = (response.text or "").strip()
    parsed: dict
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        # Try to recover JSON from a fenced block.
        m = re.search(r"\{.*\}", raw, flags=re.DOTALL)
        parsed = json.loads(m.group(0)) if m else {"results": []}

    by_id = {r.get("id"): r for r in parsed.get("results", []) if isinstance(r, dict)}
    out: list[Result] = []
    for it in content_items:
        r = by_id.get(it["id"])
        if r is None:
            out.append(Result(it["id"], it["label"], "content", None, "No result returned."))
            continue
        passed = r.get("passed")
        if passed is not None and not isinstance(passed, bool):
            passed = None
        out.append(Result(it["id"], it["label"], "content", passed, str(r.get("note", ""))))
    return out


def run_all(client, model: str, editor: DocEditor, items: list[dict]) -> list[Result]:
    """Convenience: structural first, then LLM (if a client is given)."""
    results = run_structural(editor, items)
    if client is not None:
        results.extend(run_llm_audit(client, model, editor, items))
    # Re-order to match the user's checklist order.
    order = {it["id"]: i for i, it in enumerate(items)}
    results.sort(key=lambda r: order.get(r.id, 9999))
    return results


def result_to_dict(r: Result) -> dict:
    return asdict(r)
