"""
Probe a .docx for per-block text and formatting, keyed by the dg_* bookmark
names the agent server uses as stable target ids.

The server marks every addressable block with <w:bookmarkStart w:name="dg_pN">
(paragraphs) or "dg_tblI_rR_cC" (table cells). This walks the OOXML and, for
each such bookmark, reports the enclosing paragraph's text + resolved run/
paragraph formatting so evals can assert on bold/italic/underline/size/align
without the structural index having to carry that.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

from docx import Document
from docx.oxml.ns import qn

_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


@dataclass
class BlockFormat:
    target_id: str
    text: str
    # Paragraph-level
    align: Optional[str] = None          # left|center|right|justify|None
    style: Optional[str] = None
    # Dominant run-level formatting across the block's runs (True only if ALL
    # non-empty runs carry it; None if the block has no text runs).
    bold: Optional[bool] = None
    italic: Optional[bool] = None
    underline: Optional[bool] = None
    sizes_pt: list[float] = field(default_factory=list)   # distinct run sizes


_ALIGN_MAP = {
    "0": "left", "start": "left", "left": "left",
    "1": "center", "center": "center",
    "2": "right", "end": "right", "right": "right",
    "3": "both", "both": "justify", "justify": "justify", "distribute": "justify",
}


def _para_align(p_elem) -> Optional[str]:
    pPr = p_elem.find(qn("w:pPr"))
    if pPr is None:
        return None
    jc = pPr.find(qn("w:jc"))
    if jc is None:
        return None
    val = jc.get(qn("w:val"))
    return _ALIGN_MAP.get(val, val)


def _para_style(p_elem) -> Optional[str]:
    pPr = p_elem.find(qn("w:pPr"))
    if pPr is None:
        return None
    ps = pPr.find(qn("w:pStyle"))
    return ps.get(qn("w:val")) if ps is not None else None


def _run_toggle(rPr, tag: str) -> Optional[bool]:
    if rPr is None:
        return None
    el = rPr.find(qn(f"w:{tag}"))
    if el is None:
        return None
    v = el.get(qn("w:val"))
    if v is None:
        return True
    return v not in ("0", "false", "none")


def _iter_runs(p_elem):
    for r in p_elem.iter(qn("w:r")):
        text = "".join(t.text or "" for t in r.iter(qn("w:t")))
        yield r, text


def _block_format(target_id: str, p_elem) -> BlockFormat:
    text = "".join(t.text or "" for t in p_elem.iter(qn("w:t")))
    bf = BlockFormat(
        target_id=target_id,
        text=text,
        align=_para_align(p_elem),
        style=_para_style(p_elem),
    )
    bolds, italics, unders, sizes = [], [], [], set()
    for r, rtext in _iter_runs(p_elem):
        if not rtext.strip():
            continue
        rPr = r.find(qn("w:rPr"))
        bolds.append(_run_toggle(rPr, "b") or False)
        italics.append(_run_toggle(rPr, "i") or False)
        unders.append(rPr is not None and rPr.find(qn("w:u")) is not None)
        if rPr is not None:
            sz = rPr.find(qn("w:sz"))
            if sz is not None and sz.get(qn("w:val")):
                sizes.add(int(sz.get(qn("w:val"))) / 2.0)
    if bolds:
        bf.bold = all(bolds)
        bf.italic = all(italics)
        bf.underline = all(unders)
    bf.sizes_pt = sorted(sizes)
    return bf


def probe(path: str) -> dict[str, BlockFormat]:
    """target_id -> BlockFormat for every dg_* bookmark in the document."""
    doc = Document(path)
    body = doc.element.body
    out: dict[str, BlockFormat] = {}
    for bm in body.iter(qn("w:bookmarkStart")):
        name = bm.get(qn("w:name"))
        if not name or not name.startswith("dg_"):
            continue
        # nearest enclosing <w:p>
        node = bm.getparent()
        while node is not None and node.tag != qn("w:p"):
            node = node.getparent()
        if node is None:
            continue
        out[name] = _block_format(name, node)
    return out


if __name__ == "__main__":
    import sys

    for tid, bf in probe(sys.argv[1]).items():
        print(f"{tid}\t{bf.align}\tb={bf.bold} i={bf.italic} u={bf.underline} "
              f"sz={bf.sizes_pt}\t{bf.text[:60]!r}")
