"""Convert a PDF into clean Markdown + structured JSON using a two-step pipeline.

Pipeline
--------
  Step 0  — Original PDF saved to pdfs/ (done in app.py before calling here).
  Step 1  — Marker (layout-aware ML parser) extracts structured Markdown from
             the PDF.  Output saved as  <stem>.step1_marker.md  for traceability.
  Step 2  — Gemini vision model enriches the Marker output: it looks at the
             original PDF to detect table-cell background colors and replaces
             GFM pipe tables that have color with HTML <table> blocks carrying
             inline  style="background-color: #RRGGBB"  attributes.  It also
             fixes obvious structural errors in the Marker output.
             Output saved as  <stem>.step2_enriched.md  for traceability.
  Step 3  — Final <stem>.md written (trimmed Step-2 output).
             JSON sidecar <stem>.json written (title + sections outline).

Fallback: when Marker is unavailable, Gemini does everything in one shot
(original behaviour, extended to also detect colors).

`convert_markdown_to_pdf` provides the back-conversion: Markdown (with optional
HTML table blocks) → HTML → PDF via WeasyPrint, so  background-color  CSS on
table cells is preserved in the generated PDF.

`get_pipeline_step_paths` lets the UI discover which intermediate files exist
for a given final .md path so the user can inspect each step.
"""

from __future__ import annotations

import json
import re
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from app_logger import get_logger, truncate

log = get_logger()

CONVERT_MODEL = "gemini-2.5-flash"

# ---------------------------------------------------------------------------
# Gemini prompt — Step 2: enrich Marker output with colors + structural fixes
# ---------------------------------------------------------------------------

ENRICH_PROMPT_TEMPLATE = """\
You are a document-enrichment engine. A layout parser (Marker) has already \
extracted the following Markdown from the PDF:

<marker_markdown>
{marker_markdown}
</marker_markdown>

Your tasks — apply ALL of them carefully:

1. TABLE COLORS
   For every table in the PDF that has non-white background colors on ANY \
cell, header, or row:
   - Replace the corresponding GFM pipe table in the Markdown with an HTML \
<table> block.
   - Add  style="background-color: #RRGGBB"  to every <th> and <td>.
   - For cells with no color (white / transparent), use #ffffff.
   - If text on a colored background is not black, also add  color: #RRGGBB  \
to the same style attribute.
   - Leave ALL plain (all-white) tables as GFM pipe tables.

   Example HTML table cell:
     <td style="background-color: #2e74b5; color: #ffffff">Module Code</td>

2. STRUCTURAL FIXES
   Fix obvious Marker errors: dropped rows, merged cell content, wrong reading \
order.  Do NOT rewrite or paraphrase content.

3. EVERYTHING ELSE
   Keep headings, lists, and body text exactly as Marker produced them, unless \
they are clearly wrong.

Return ONE JSON object — no commentary outside the JSON, no code fences:

{{
  "title": "<document title>",
  "markdown_content": "<the enriched Markdown, same structure as input but \
with HTML tables where colors exist>",
  "sections": [
    {{"heading": "<heading text>", "level": <1-6>, \
"content": "<markdown body directly under this heading>"}}
  ]
}}"""

# ---------------------------------------------------------------------------
# Gemini prompt — fallback when Marker is unavailable (Gemini-only, one shot)
# ---------------------------------------------------------------------------

CONVERT_PROMPT_FALLBACK = """\
You are a document-conversion engine. The user has uploaded a PDF.

Your job: extract ALL text and tables from the PDF and return ONE JSON object \
with this exact shape (no extra keys, no commentary outside the JSON):

{
  "title": "<best-guess document title, or the filename stem if unclear>",
  "markdown_content": "<the entire document rendered as Markdown — see rules>",
  "sections": [
    {"heading": "<heading text>", "level": <1-6>, \
"content": "<markdown body under this heading, may be empty>"}
  ]
}

RULES for markdown_content:
- Use `#`, `##`, `###` for headings; `-` for bullet lists; `1.` for numbered \
lists; `**bold**`; `*italic*`.
- For tables with non-white background colors on cells/rows/headers: emit an \
HTML <table> block with style="background-color: #RRGGBB" on each <th>/<td>. \
For white/transparent cells use #ffffff. If text on a colored background is \
not black, also add color: #RRGGBB.
- For plain tables (all white): use GFM pipe syntax (| col | col | with a \
| --- | --- | separator row).
- Do NOT wrap the output in code fences.
- Preserve reading order. Drop page numbers, running headers/footers.
- Keep the original document language.

Return ONLY the JSON object. No prose, no markdown fences around the JSON."""

# Shared JSON schema for both Gemini calls
CONVERT_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "markdown_content": {"type": "string"},
        "sections": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "heading": {"type": "string"},
                    "level": {"type": "integer"},
                    "content": {"type": "string"},
                },
                "required": ["heading", "level", "content"],
            },
        },
    },
    "required": ["title", "markdown_content", "sections"],
}


# ---------------------------------------------------------------------------
# Result dataclass
# ---------------------------------------------------------------------------

@dataclass
class ConvertResult:
    title: str
    markdown_content: str
    sections: list[dict]
    markdown_path: Path
    json_path: Path
    step1_marker_path: Optional[Path] = field(default=None)
    step2_enriched_path: Optional[Path] = field(default=None)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _safe_stem(name: str) -> str:
    stem = Path(name).stem
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", stem).strip("._-")
    return stem or "document"


def _unique_path(folder: Path, stem: str, suffix: str) -> Path:
    target = folder / f"{stem}{suffix}"
    n = 1
    while target.exists():
        target = folder / f"{stem} ({n}){suffix}"
        n += 1
    return target


def _parse_or_repair_json(raw: str) -> dict:
    """Best-effort JSON parse — recovers from token-limit truncation."""
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass

    s = raw
    fence = re.match(r"```(?:json)?\s*(.*?)\s*```", s, re.DOTALL)
    if fence:
        s = fence.group(1)

    start = s.find("{")
    if start == -1:
        raise RuntimeError("No JSON object found in model output.")
    s = s[start:]

    stack: list[str] = []
    in_str = False
    escape = False
    for ch in s:
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch in "{[":
            stack.append("}" if ch == "{" else "]")
        elif ch in "}]":
            if stack and stack[-1] == ch:
                stack.pop()

    repaired = s
    if in_str:
        repaired += '"'
    while stack:
        repaired += stack.pop()

    try:
        return json.loads(repaired)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"Could not parse JSON from model: {e}") from e


# ---------------------------------------------------------------------------
# Marker singleton — models are heavy; load once and reuse
# ---------------------------------------------------------------------------

_MARKER_MODELS: dict | None = None


def _get_marker_models() -> dict:
    global _MARKER_MODELS
    if _MARKER_MODELS is None:
        log.info("MARKER | loading models (first call — may take ~30 s) …")
        from marker.models import create_model_dict  # type: ignore[import]
        _MARKER_MODELS = create_model_dict()
        log.info("MARKER | models loaded and cached.")
    return _MARKER_MODELS


# ---------------------------------------------------------------------------
# Step 1: Marker extraction
# ---------------------------------------------------------------------------

def _run_marker(pdf_bytes: bytes, actual_stem: str, step_dir: Path) -> str | None:
    """Extract structured Markdown from the PDF using Marker.

    Returns the raw Markdown string, or None if Marker is unavailable/fails.
    Saves output as  <actual_stem>.step1_marker.md  for traceability.
    """
    try:
        from marker.converters.pdf import PdfConverter  # type: ignore[import]
    except ImportError:
        log.warning("MARKER | marker-pdf not installed — will use Gemini-only fallback.")
        return None

    try:
        models = _get_marker_models()
        converter = PdfConverter(artifact_dict=models)

        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
            tmp.write(pdf_bytes)
            tmp_path = Path(tmp.name)

        log.info("MARKER | STEP 1 START | converting %s.pdf …", actual_stem)
        rendered = converter(str(tmp_path))
        marker_md: str = rendered.markdown
        tmp_path.unlink(missing_ok=True)

        step1_path = step_dir / f"{actual_stem}.step1_marker.md"
        step1_path.write_text(marker_md, encoding="utf-8")
        log.info(
            "MARKER | STEP 1 DONE  | %d chars → saved %s",
            len(marker_md),
            step1_path.name,
        )
        return marker_md

    except Exception as exc:  # noqa: BLE001
        log.exception(
            "MARKER | STEP 1 FAILED (%s) — falling back to Gemini-only.", exc
        )
        return None


# ---------------------------------------------------------------------------
# Step 2a: Gemini enrichment (when Marker succeeded)
# ---------------------------------------------------------------------------

def _run_enrich(
    provider,
    pdf_bytes: bytes,
    marker_md: str,
    actual_stem: str,
    step_dir: Path,
) -> dict:
    """Enrich Marker output: detect table colors, emit HTML tables, fix errors.

    Saves enriched Markdown as  <actual_stem>.step2_enriched.md.
    Returns the parsed JSON dict from the model.
    """
    prompt = ENRICH_PROMPT_TEMPLATE.format(marker_markdown=marker_md)
    log.info(
        "ENRICH | STEP 2 START | model=%s | prompt_len=%d chars",
        provider.model,
        len(prompt),
    )

    raw = provider.generate_json(
        prompt=prompt,
        pdf_bytes=pdf_bytes,
        json_schema=CONVERT_SCHEMA,
        temperature=0.1,
        max_output_tokens=32000,
    )
    if not raw:
        raise RuntimeError("Model returned an empty response during enrichment.")

    data = _parse_or_repair_json(raw)
    enriched_md = (data.get("markdown_content") or "").strip()

    step2_path = step_dir / f"{actual_stem}.step2_enriched.md"
    step2_path.write_text(enriched_md, encoding="utf-8")
    log.info(
        "ENRICH | STEP 2 DONE  | %d chars → saved %s",
        len(enriched_md),
        step2_path.name,
    )
    return data


# ---------------------------------------------------------------------------
# Step 2b: Gemini-only fallback (when Marker unavailable)
# ---------------------------------------------------------------------------

def _run_fallback(
    provider,
    pdf_bytes: bytes,
    actual_stem: str,
    step_dir: Path,
) -> dict:
    """Fallback: the model extracts everything in one shot (no Marker).

    Saves output as  <actual_stem>.step1_gemini_fallback.md.
    Returns the parsed JSON dict.
    """
    log.info(
        "FALLBACK | STEP 1 START | model=%s | extracting from scratch …",
        provider.model,
    )

    raw = provider.generate_json(
        prompt=CONVERT_PROMPT_FALLBACK,
        pdf_bytes=pdf_bytes,
        json_schema=CONVERT_SCHEMA,
        temperature=0.1,
        max_output_tokens=32000,
    )
    if not raw:
        raise RuntimeError("Model returned an empty response.")

    data = _parse_or_repair_json(raw)
    fallback_md = (data.get("markdown_content") or "").strip()

    fallback_path = step_dir / f"{actual_stem}.step1_gemini_fallback.md"
    fallback_path.write_text(fallback_md, encoding="utf-8")
    log.info(
        "FALLBACK | STEP 1 DONE  | %d chars → saved %s",
        len(fallback_md),
        fallback_path.name,
    )
    return data


# ---------------------------------------------------------------------------
# Public conversion entry point
# ---------------------------------------------------------------------------

def convert_pdf_to_markdown(
    provider,
    pdf_bytes: bytes,
    pdf_filename: str,
    out_dir: Path,
) -> ConvertResult:
    """Two-step PDF → Markdown pipeline (Marker + LLM color enrichment).

    ``provider`` is an ``llm_provider.Provider`` (Gemini or OpenAI).

    Intermediate files saved at each step for full traceability:
      <stem>.step1_marker.md          — raw Marker output  (Step 1)
      <stem>.step2_enriched.md        — LLM-enriched output  (Step 2)
      <stem>.md                       — final canonical Markdown  (Step 3)
      <stem>.json                     — structured JSON sidecar  (Step 3)

    If Marker is unavailable the fallback is saved as:
      <stem>.step1_gemini_fallback.md
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = _safe_stem(pdf_filename)

    # Reserve final output paths first so step files share the same stem
    md_path = _unique_path(out_dir, stem, ".md")
    json_path = md_path.with_suffix(".json")
    actual_stem = md_path.stem  # may be "XYZ-Training (1)" etc.

    log.info(
        "PDF→MD | PIPELINE START | file=%s | output=%s | model=%s",
        pdf_filename,
        md_path.name,
        provider.model,
    )

    # ── Step 1: Marker ────────────────────────────────────────────────────
    marker_md = _run_marker(pdf_bytes, actual_stem, out_dir)

    # ── Step 2: LLM ───────────────────────────────────────────────────────
    if marker_md is not None:
        data = _run_enrich(provider, pdf_bytes, marker_md, actual_stem, out_dir)
        step1_path: Path | None = out_dir / f"{actual_stem}.step1_marker.md"
        step2_path: Path | None = out_dir / f"{actual_stem}.step2_enriched.md"
    else:
        data = _run_fallback(provider, pdf_bytes, actual_stem, out_dir)
        step1_path = None
        step2_path = None

    # ── Step 3: write final outputs ───────────────────────────────────────
    title = (data.get("title") or _safe_stem(pdf_filename)).strip()
    markdown = (data.get("markdown_content") or "").strip()
    sections = data.get("sections") or []
    if not isinstance(sections, list):
        sections = []

    md_path.write_text(markdown + "\n", encoding="utf-8")
    json_path.write_text(
        json.dumps(
            {"title": title, "source_pdf": pdf_filename, "sections": sections},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    log.info(
        "PDF→MD | PIPELINE DONE  | md=%s | json=%s | sections=%d | title=%r",
        md_path.name,
        json_path.name,
        len(sections),
        truncate(title, 80),
    )

    return ConvertResult(
        title=title,
        markdown_content=markdown,
        sections=sections,
        markdown_path=md_path,
        json_path=json_path,
        step1_marker_path=step1_path,
        step2_enriched_path=step2_path,
    )


# ---------------------------------------------------------------------------
# Back-conversion: Markdown → PDF via WeasyPrint
# ---------------------------------------------------------------------------

def convert_markdown_to_pdf(md_path: Path, out_path: Path | None = None) -> Path:
    """Convert a Markdown file (with optional HTML table blocks) to PDF.

    Pipeline: Markdown → HTML (via markdown2) → PDF (via xhtml2pdf).
    Inline  style="background-color: #RRGGBB"  attributes on <th>/<td>
    are preserved in the generated PDF.

    Args:
        md_path:  Path to the source .md file.
        out_path: Destination PDF path. Defaults to <md_path>.pdf.

    Returns the path to the written PDF.
    """
    try:
        import io
        import markdown2  # type: ignore[import]
        from xhtml2pdf import pisa  # type: ignore[import]
    except ImportError as exc:
        raise RuntimeError(
            "markdown2 and xhtml2pdf are required for PDF export. "
            "Run: pip install markdown2 xhtml2pdf"
        ) from exc

    if out_path is None:
        out_path = md_path.with_suffix(".pdf")

    md_text = md_path.read_text(encoding="utf-8")
    log.info("MD→PDF | START | %s → %s", md_path.name, out_path.name)

    # Markdown → HTML (extras handle fenced code, GFM tables, etc.)
    html_body = markdown2.markdown(
        md_text,
        extras=[
            "fenced-code-blocks",
            "tables",
            "header-ids",
            "strike",
            "break-on-newline",
        ],
    )

    page_css = """
@page { margin: 2cm 2.5cm; }
body {
  font-family: Helvetica, Arial, sans-serif;
  font-size: 11pt;
  line-height: 1.4;
  color: #202020;
}
h1 { font-size: 16pt; color: #2e74b5; margin: 14pt 0 6pt 0; }
h2 { font-size: 13pt; color: #2e74b5; margin: 12pt 0 4pt 0; }
h3 { font-size: 12pt; color: #5b9bd5; font-style: italic; margin: 10pt 0 4pt 0; }
h4 { font-size: 11pt; margin: 8pt 0 4pt 0; }
p  { margin: 0 0 8pt 0; }
ul, ol { margin: 4pt 0 8pt 24pt; padding: 0; }
li { margin: 0 0 3pt 0; }
table {
  border-collapse: collapse;
  width: 100%;
  margin: 8pt 0;
  font-size: 10pt;
}
th, td {
  border: 1px solid #c0c0c0;
  padding: 4pt 8pt;
  vertical-align: top;
}
th { font-weight: bold; }
code {
  font-family: Courier, monospace;
  font-size: 9pt;
  background: #f5f5f5;
  padding: 1pt 3pt;
}
pre { background: #f5f5f5; padding: 6pt; }
pre code { background: none; padding: 0; }
"""

    full_html = (
        "<!DOCTYPE html>\n<html>\n<head>\n"
        "<meta charset='utf-8'>\n"
        f"<style>{page_css}</style>\n"
        "</head>\n<body>\n"
        f"{html_body}\n"
        "</body>\n</html>"
    )

    buf = io.BytesIO()
    result = pisa.CreatePDF(full_html, dest=buf)
    if result.err:
        raise RuntimeError(f"xhtml2pdf reported errors: {result.err}")

    out_path.write_bytes(buf.getvalue())
    size_kb = len(buf.getvalue()) // 1024
    log.info("MD→PDF | DONE  | %s (%d KB)", out_path.name, size_kb)
    return out_path


# ---------------------------------------------------------------------------
# UI helper: discover pipeline step files for a final .md path
# ---------------------------------------------------------------------------

def get_pipeline_step_paths(md_path: Path) -> dict[str, Path | None]:
    """Return a dict of intermediate pipeline file paths for a given .md.

    Values are None when the file does not exist.
    Keys: "step1_marker", "step2_enriched", "step1_fallback"
    """
    parent = md_path.parent
    stem = md_path.stem
    candidates = {
        "step1_marker": parent / f"{stem}.step1_marker.md",
        "step2_enriched": parent / f"{stem}.step2_enriched.md",
        "step1_fallback": parent / f"{stem}.step1_gemini_fallback.md",
    }
    return {k: (v if v.exists() else None) for k, v in candidates.items()}


# ---------------------------------------------------------------------------
# Markdown editing helpers (used by the chat agent)
# ---------------------------------------------------------------------------

def read_markdown(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_markdown(path: Path, text: str) -> None:
    path.write_text(text if text.endswith("\n") else text + "\n", encoding="utf-8")


def replace_in_markdown(path: Path, find: str, replace: str) -> int:
    if not find:
        return 0
    text = read_markdown(path)
    count = text.count(find)
    if count:
        write_markdown(path, text.replace(find, replace))
    return count


# Tool schema and dispatch for the chat agent
MD_TOOLS = [
    {
        "name": "read_markdown",
        "description": "Read the full markdown document. Returns its current text.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "write_markdown",
        "description": "Overwrite the markdown document with the provided text. "
                       "Use this for whole-document rewrites.",
        "parameters": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "replace_in_markdown",
        "description": "Find-and-replace across the markdown document. "
                       "Returns the number of replacements made.",
        "parameters": {
            "type": "object",
            "properties": {
                "find": {"type": "string"},
                "replace": {"type": "string"},
            },
            "required": ["find", "replace"],
        },
    },
]


MD_SYSTEM_PROMPT = """\
You are a markdown-document editing agent. The user has converted a PDF into \
a Markdown file and wants your help editing it.

Tools you can call:
- `read_markdown()` — read the current markdown.
- `write_markdown(text)` — overwrite the entire file (use for large rewrites).
- `replace_in_markdown(find, replace)` — exact find/replace, returns count.

Guidance:
- ALWAYS call `read_markdown` first so you know the current state.
- Make focused edits. Don't rewrite content the user didn't ask you to change.
- Output must remain valid GitHub-flavored Markdown.
- For plain tables (no color), use GFM pipe syntax: `| col | col |` with a \
`| --- | --- |` separator row.
- IMPORTANT: If a table is already expressed as an HTML <table> block \
(with inline style attributes for colors), do NOT convert it to GFM pipe \
syntax.  Preserve the HTML table structure exactly — only edit the text \
content inside <th> and <td> cells.
- After editing, briefly describe what you changed.
"""


def md_dispatch(path: Path, name: str, args: dict) -> str:
    """Dispatch a tool call from the chat agent."""
    try:
        if name == "read_markdown":
            return read_markdown(path)
        if name == "write_markdown":
            write_markdown(path, args.get("text", ""))
            return "Wrote markdown file."
        if name == "replace_in_markdown":
            n = replace_in_markdown(path, args.get("find", ""), args.get("replace", ""))
            return f"Replaced {n} occurrence(s)."
        return f"Unknown tool: {name}"
    except Exception as e:  # noqa: BLE001
        return f"Error in {name}: {e}"
