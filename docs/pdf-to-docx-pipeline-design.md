# PDF → DOCX Ingestion Pipeline — Design Notes

Design reference for feeding arbitrary customer PDFs into an existing docx4j-based
AI document editor.

**Status:** design draft, not yet implemented
**Context:** existing system is a Java/docx4j AI Word editor. LLM emits structured
JSON mutation intent against a document index; deterministic Java applies surgical
docx4j edits. Run-scoped editing preserves `CharacterFormat`. Structural ID-based
addressing, `old_text` optimistic-lock verification, one-mutation-per-node-per-batch.

**Problem:** the PDF → DOCX step. Third-party converters (iLovePDF etc.) silently
flatten tables into body text, which breaks indexing and makes downstream edits
unsafe.

---

## 0. TL;DR — the minimum viable path

The full architecture below is deliberately complete. **Do not build all of it.**
Build in this order and stop when the accuracy is good enough:

| Phase | Work | Why first |
|---|---|---|
| **0** | Capability probe only. Run it over ~50 real customer PDFs. Publish the signal distribution. | Determines which extraction route covers the bulk. Everything else is guesswork until this exists. Cost: ~2 days. |
| **1** | IR schema + rect-grid normalization + sub-table splitting + `IR → docx4j` generator. Single extraction route (whichever Phase 0 says dominates). | This is the actual deliverable. Replaces the third-party converter. |
| **2** | Confidence scoring + `editability class` + executor guard. | This is what stops the AI from corrupting documents. Highest value per line of code in the whole design. |
| **3** | Verification loop (re-render diff + VLM check) + golden set. | Required before this can be trusted on unseen customer input. |
| **4** | Additional extraction routes (vision/OCR) as the probe distribution demands. | Deferrable, possibly indefinitely. |

If only two things get built: **Phase 1 and Phase 2.**

---

## 1. Design principle

Structure recovery from arbitrary PDFs is an unsolved problem. Adobe and Apryse have
large teams on it and have not solved it. Any architecture that assumes "conversion
will succeed" fails in production in the worst possible way: it *silently* produces
a structurally wrong docx, the AI edits on top of it, and the user discovers the
document is ruined several steps later.

So the target is two things, and the second matters more than the first:

1. Accurate on the majority of documents.
2. **Reliably aware of when it is not accurate**, and able to refuse to let the AI
   touch those regions.

The observed failure mode ("tables are easy to break when editing") is not caused by
imperfect table detection. It is caused by the system *not knowing* its table
detection was imperfect and letting the AI operate anyway. The design below is
organized around closing that specific gap.

### Two framings that must not be conflated

| Goal | Output shape | Fit for this use case |
|---|---|---|
| **Visual fidelity** — output looks pixel-identical | Every text block wrapped in a floating textbox / absolutely-positioned frame | **Poison.** Indexing sees isolated textboxes; run-scoped editing and structural ID addressing both fail. |
| **Semantic reconstruction** — real flow document | True `<w:p>` paragraphs, true `<w:tbl>` grids, real heading levels | **Required.** |

Only the second matters here. Any converter offering a "preserve original layout"
mode is offering the first.

### Corollary: do not outsource docx generation

```
Current:  PDF → [3rd-party docx] → index → LLM → docx4j edit
Target:   PDF → [extractor] → IR JSON → [own docx4j generator] → index → LLM → docx4j edit
```

The IR is **not** an editing target. It is a single-use conversion intermediate whose
lifecycle ends when the docx is generated. Its only purpose is to split "infer
structure from coordinates" from "generate docx", so the second half is owned
in-house.

Consequences:

- Every node ID and table cell address is **assigned at construction time**, not
  reverse-engineered from someone else's output.
- Converter artifacts disappear at source: no textbox wrapping, no runs shredded into
  dozens of fragments, no phantom empty paragraphs used for visual spacing.
- The index becomes a byproduct of generation rather than a parsing problem.

---

## 2. Architecture

```
                       ┌─────────────────────┐
                       │  Capability probe   │
                       │  what signals exist │
                       └──────────┬──────────┘
                    ┌─────────────┼─────────────┐
                    ▼             ▼             ▼
             ┌────────────┐ ┌────────────┐ ┌────────────┐
             │  Tagged    │ │ Geometric  │ │  Vision    │
             │ struct tree│ │  borders   │ │ OCR+layout │
             └──────┬─────┘ └──────┬─────┘ └─────┬──────┘
                    └─────────────┐│┌─────────────┘
                                  ▼▼▼
                       ┌─────────────────────────┐
                       │     Normalized IR       │
                       │ rect grids, sub-tables  │
                       └──────────┬──────────────┘
                                  ▼
                       ┌─────────────────────┐        ┌───────────────┐
                       │  Confidence gate    ├───────►│ Human review  │
                       │  per-block scoring  │        └───────────────┘
                       └──────────┬──────────┘
                                  ▼
                       ┌─────────────────────────────┐
                       │     docx4j generator        │
                       │ docx + bookmarks + id map   │
                       └─────────────────────────────┘
```

The single reason this generalizes: **upstream diversity is absorbed by the probe and
the routing. Everything downstream of the IR sees exactly one data structure.**

---

## 3. Stage 1 — Capability probe

A cheap (tens of ms) diagnostic that classifies the PDF and selects a route.

| Signal | How to obtain (PDFBox) | Meaning |
|---|---|---|
| Structure tree present | `catalog.getStructureTreeRoot()`, `catalog.getMarkInfo().isMarked()` | Logical structure available |
| Structure tree non-degenerate | Count `Table` / `TR` / `TD` / `TH` elements, tree depth | A tree existing ≠ a tree being useful |
| Text layer | `PDFTextStripper` char count; font resources per page | Whether OCR is needed at all |
| Vector border density | `PDFGraphicsStreamEngine` → count `rect` / `line` ops per page | Whether geometric table detection is viable |
| Text coverage ratio | Σ char bbox area / page area | Detects image-heavy or hybrid scanned pages |
| Producer / Creator | Document information dictionary | Strong prior. `Microsoft Word` → expect tagged, expect table-heavy forms. `Scanner` / image-only → vision route. |

**Persist the probe result as document metadata.** It is the input to all confidence
scoring downstream, and it is the only forensic trail for "why did this document
convert badly" six months later.

Reference implementation of the probe (Python, for prototyping — port to PDFBox):

```python
import pikepdf
from collections import Counter

def probe(path):
    pdf = pikepdf.open(path)
    root = pdf.Root
    out = {
        "pages": len(pdf.pages),
        "producer": str(root.get("/Producer", "")) if "/Producer" in root else None,
        "marked": bool(root.get("/MarkInfo", {}).get("/Marked", False)),
        "has_struct_tree": "/StructTreeRoot" in root,
    }

    tags = Counter()
    if out["has_struct_tree"]:
        def walk(n):
            if isinstance(n, pikepdf.Array):
                for k in n: walk(k)
                return
            if not isinstance(n, pikepdf.Dictionary): return
            s = n.get("/S")
            if s is not None: tags[str(s).lstrip("/")] += 1
            if n.get("/K") is not None: walk(n["/K"])
        walk(root["/StructTreeRoot"].get("/K"))

    out["struct_tags"] = dict(tags)
    out["table_like"] = tags["TR"] + tags["TD"] + tags["TH"]
    return out
```

### Routing rules

```
no text layer (chars ≈ 0)                          → VISION
struct tree present AND table_like > threshold     → TAGGED
text layer AND rect density high                   → GEOMETRIC
text layer AND rect density low                    → VISION (borderless tables)
```

---

## 4. Stage 2 — Extraction routes

All three emit the same IR. Do not attempt to make one route handle everything.

### 4.1 Tagged route

Structure tree provides cell → row membership for free. This is substantially more
reliable than pure geometric inference.

**Known gap:** Word does not write `/ColSpan` and `/RowSpan` into the structure tree.
The tree tells you a row has 4 cells; it does not tell you whether cell 1 spans 2 of
7 grid columns. **Logical grid geometry must be recovered from coordinates.**

Span recovery algorithm:

1. Collect all vertical edge x-coordinates from drawn borders across all rows.
2. **Cluster with ~1.5pt tolerance.** Word draws borders as thin filled rectangles, so
   each logical line produces *two* edges (observed: `48.8/50.3`, `59.0/59.5`,
   `129.9/130.4`). Skipping this step yields a grid with double the real column count.
3. The clustered x-positions form the master column grid.
4. For each cell, `colspan` = number of grid columns its bbox spans.
5. `rowspan` similarly from horizontal edges + cell bbox height.

### 4.2 Geometric route

Text layer present, no usable structure tree, but borders drawn.

PDFBox for chars + graphics ops; cluster into lines, then cells. Same 1.5pt edge
clustering applies.

Useful reference point: `pdfplumber.find_tables()` returns *what a human sees as
separate tables*, not the OOXML truth. For this use case that "error" is the desired
behaviour — worth studying its algorithm rather than aiming for structural fidelity.

### 4.3 Vision route

Scanned documents, or borderless-and-untagged tables. **Only** here.

Candidate: **Docling** (IBM Research). Code MIT-licensed; `granite-docling` model
weights Apache 2.0; genuinely free for commercial use. Runs locally — no data egress.
Architecture: `docling-serve` (FastAPI REST) in a container, `docling-java` as the
JVM-side client. Suits an OpenShift deployment.

`TableFormer` handles partial/absent borders, empty cells, cell spans, and
hierarchical headers — designed for exactly the cases where geometry fails.

**Do not use it on the other two routes.** An extra container, several hundred MB of
weights, and seconds of inference per page, in exchange for possibly *worse* accuracy
than geometry on a document that already has clean borders and a structure tree.

### 4.4 Explicitly rejected

| Option | Reason |
|---|---|
| **LibreOffice headless `--convert-to docx`** | Imports PDF as positioned textboxes. Reproduces the current problem, worse. (Using LibreOffice to *render* docx is fine — see §8.) |
| **iLovePDF / Smallpdf / online converters** | Black box, no structural guarantees, and uploading customer documents to a third party is likely a compliance problem independent of quality. |
| **Aspose.PDF `RecognitionMode.Textbox`** | Explicitly the visual-fidelity mode. If Aspose is used at all, `Flow` / `EnhancedFlow` only. |
| **Aspose / Apryse / Nutrient as primary path** | Viable and high quality, but they own docx generation — which forfeits the entire benefit in §1. Reasonable as a *baseline for comparison*. |

---

## 5. Stage 3 — IR normalization

**The most important stage.** This is where "tables are easy to break when editing"
actually gets solved.

### 5.1 Decision one: normalize to rectangular grids

Word's real structure for form documents is often "one giant table, per-row
independent grids, 1..N cells per row" (see Appendix A). **Do not reproduce it.**

The requirements are only:

- (a) the docx looks right
- (b) it can be safely edited

Structural identity with the original OOXML is **not** a requirement.

So normalize every visual sub-table into a **regular rectangular grid with explicit
colspan/rowspan**:

```
Original (per-row grids)          Normalized (7-column grid)
  row A: [1 cell]          →        row A: [colspan=7]
  row B: [cell | cell]     →        row B: [colspan=1][colspan=6]
  row C: [7 cells]         →        row C: [1][1][1][1][1][1][1]
```

Benefits compound:

- Always valid OOXML
- `(row, col)` addressing is unique and total
- Word renders it without surprises
- Row/column insertion has well-defined semantics — under per-row independent grids,
  an AI inserting a row cannot know how many cells to create

**This step alone eliminates most of the structural-edit hazard.**

### 5.2 Decision two: split sub-tables, and err toward over-splitting

A 238-row table is unusable as an addressing unit: too large for context, unreliable
to locate within, and the blast radius of a wrong edit is unbounded.

Split signals, ordered by reliability:

1. **Column grid discontinuity** — adjacent rows have substantially different column
   boundary sets. Most reliable signal.
2. **Full-width single-cell row with fill colour** — section headers. (In the example
   document: `Description`, `Component list`, `Additives declaration`, etc.)
3. Empty row / border discontinuity.
4. Page boundary — usable as a soft boundary, conservatively.

### 5.3 The asymmetry rule — encode this explicitly

Two directional biases, both derived from *which errors are recoverable*:

| Ambiguous case | Bias toward | Because |
|---|---|---|
| Split vs. keep together | **Split** | Over-splitting turns one table into two — looks the same to the user, still fully editable. Under-splitting produces a 238-row monster the AI will eventually break. |
| `table` vs. `paragraph` | **table** | Misreading a table as body text (the iLovePDF failure) is irreversible information loss. Wrapping a paragraph in a single-cell table is merely ugly — content and editability both survive. |

Write these biases into the thresholds, aggressively. They are not tie-breakers; they
are the core safety property of the normalization stage.

---

## 6. Stage 4 — Confidence and the FROZEN escape hatch

### 6.1 Per-block confidence

Derived from computable signals, not heuristic vibes:

| Level | Conditions |
|---|---|
| **High** | Structure tree supplied cell membership **AND** geometric span count agrees with structure tree cell count **AND** every character falls inside some cell bbox |
| **Medium** | Geometric detection only, but grid closes cleanly (consistent column count per row, borders complete) |
| **Low** | Grid does not close / span inference ambiguous / any character falls outside all cell bboxes |

The last condition is disproportionately useful: **it automatically catches reading-order
corruption.** Stray text bleeding between columns manifests exactly as "a character
belongs to no identified cell." This class of error needs no human inspection to detect.

### 6.2 FROZEN

For low-confidence regions: **do not force a structure. Rasterize the region, embed it
as an image, mark it FROZEN.**

This reads like giving up. In production it is clearly correct:

- Visual fidelity is 100% by construction
- The AI cannot edit it, therefore cannot corrupt it
- The user can see at a glance that the region was preserved verbatim

Strictly better than emitting a structurally wrong table and then letting an AI operate
on it.

Typical FROZEN candidates: vector-drawn process flowcharts (arrows are drawing ops and
vanish from text extraction; adjacent columns cross-contaminate), signature blocks,
stamps, complex nested multi-column regions.

---

## 7. Stage 5 — docx4j generation and integration

### 7.1 docx4j does *more* work, not less

```
IR JSON ──[docx4j write]──→ docx          ① generator (new)
                             │
                             ├──→ blockId → docx4j object reference    ② index (free at generation)
                             │
LLM edit intent ─────────────┴─[docx4j mutate]──→ docx   ③ executor (existing, unchanged)
```

Only one thing changes: **the index is recorded during generation rather than derived
by re-parsing the docx.** Same `P` / `Tbl` / `Tc` objects — they just have known names
in advance.

Existing run-scoped surgical editing, `CharacterFormat` preservation, `old_text`
optimistic lock, and per-node one-mutation-per-batch all operate on docx4j objects and
require **no changes**.

### 7.2 Reopen problem → bookmarks

On process restart or a new session the in-memory `blockId → object` map is gone.
Solution: emit `<w:bookmarkStart w:name="b0007.r3c1"/>` for every block at generation
time. One walk of the reloaded document rebuilds the map — **precisely rebuilt, not
re-inferred.**

This capability only exists because generation is owned in-house. It is the concrete
payoff of §1.

### 7.3 The one new concept: editability class

Derived from §6.1 confidence.

| Class | Permitted operations | From confidence |
|---|---|---|
| `SAFE` | Text replacement within a cell / run | High, Medium |
| `RESTRICTED` | Structural ops (insert row, merge cells, change column width) | High only |
| `FROZEN` | None (read-only, or whole-block replacement with a new image) | Low |

Add one check at the mutation executor entry point: **declared operation type vs.
target node class.** Mismatch → reject with an explicit error returned to the LLM.

This converts "the AI might corrupt the document" from a probability into a
**structural impossibility**.

Note the complementarity: `old_text` optimistic lock guards *"has this content changed
under me"*; editability class guards *"is the structure at this position trustworthy"*.
Both are needed; neither substitutes for the other.

### 7.4 Expose confidence to the LLM

Include `confidence` and `editable` in the document index handed to the model. A model
that sees `confidence: low, editable: frozen` will route around it, or proactively tell
the user that region cannot be reliably edited. Cheaper than letting it hit the
executor rejection and retry.

---

## 8. Stage 6 — Verification loop

Arbitrary input means manual test coverage is impossible. Two automated checks:

**Re-render diff.** Render the generated docx to PNG via LibreOffice headless; compare
structural similarity against the original PDF page raster. Below threshold → route to
review. (LibreOffice is being used to *render docx*, which is its strength — distinct
from using it to *import PDF*, which is rejected in §4.4.)

**Multimodal check.** Feed `original page raster + generated IR` to a VLM: "does this IR
faithfully represent this page, and where does it not?" Holds for arbitrary layouts
without per-document-type rules. Far cheaper than a user discovering the document is
broken.

Together these form the regression suite. Accumulate a **golden set** of real customer
PDFs; re-run on every extraction-logic change.

> The quality ceiling of a general solution is set by the size of this golden set, not
> by the cleverness of the algorithm.

---

## Appendix A — Worked example diagnostic

Real customer document: an 11-page supplier product specification form
(Asian Food Group `PS-002.10 Blanco productspecificatie`, v1.1). Chosen as a hard case
because it is almost entirely tables.

### Probe output

| Field | Value |
|---|---|
| Creator / Producer | **Microsoft® Word 2019** |
| Tagged | **yes** (`/MarkInfo /Marked true`, `/Lang zh`) |
| Fonts | 10, all embedded + subset (TrueType + CID TrueType Identity-H) |
| Images | 12 — 11× logo (494×220 JPEG, one per page header) + 1 signature (241×142, p7) |
| AcroForm | none |
| Pages | 11, letter |
| Vector rects | 452 (p1) / 699 (p2) per page |
| Chars | 1166 (p1) / 1681 (p2) per page |

Born-digital and tagged. No OCR needed. Best possible input class.

### Structure tree

```
Document
├── P × 112              ← body text, pages 7–11
└── Table × 1            ← ALL of pages 1–6, a single table
    ├── THead → TR × 1
    └── TBody → TR × 237
```

238 `TR` total, distributed p1–p6 as 34/38/50/43/36/37 (= 238 exactly).

What the eye reads as separate tables (`Description`, `Component list`,
`Nutritional Values`, `Additives declaration`, the allergen list) are **not separate
tables in OOXML** — they are rows of one table, disguised by grey fill and merged cells.

Cells per row, wildly irregular:

```
1 cell: 28 rows   2: 41   3: 33   4: 28   5: 31   6: 26   7: 51
```

This is Word's per-row independent grid pattern (`tblGrid` + `gridSpan`) — legal, but it
means **there is no single consistent column structure**.

### The critical finding

```
cells carrying /ColSpan or /RowSpan: {}
```

**Zero.** Word wrote no span information into the structure tree. Logical grid geometry
must be recovered from coordinates (§4.1) — and that is precisely the part that matters
most.

### Reading-order corruption in the text layer

Page 2 — the `Yes` belongs to a cell on the right-hand side, injected mid-sentence:

> Other **Yes** values (than per 100g / 100ml) are not allowed in EU legislation!

Page 5 — trailing `acceptance` bleeds in from the right-hand CCP column:

> Soybean acceptance → Shelling soybean → Squeeze out soybean oil → Stir soybean powder
> with **acceptance**

Also: `productsuitable`, `Isthe`, `Totalshelf`, `productsin` — spaces are not encoded in
the PDF, only glyph advances, so word boundaries are unrecoverable by naive extraction.

Both classes of error are **detectable** by the character-outside-cell-bbox rule in
§6.1. The vector arrows (`⟶`) in the process description are drawing ops and vanish
entirely from text extraction — a FROZEN candidate.

### Route decision for this document

`TAGGED`, with geometric span recovery. `pdfplumber.find_tables()` on this file returns
1 table / 39 rows (p1) and 2 tables / 42 + 11 rows (p2) — i.e. the human-visible
tables. Useful cross-check against the sub-table splitting in §5.2.

Docling is **not** needed here.

---

## Appendix B — IR schema draft

```json
{
  "docId": "doc_7f3a",
  "sourcePages": 11,
  "probe": {
    "route": "TAGGED",
    "producer": "Microsoft® Word 2019",
    "marked": true,
    "structTags": { "Table": 1, "TR": 238, "TD": 751, "TH": 238 },
    "spansPresent": false
  },
  "blocks": [
    {
      "id": "b0001",
      "type": "heading",
      "level": 2,
      "page": 1,
      "bbox": [72, 690, 480, 712],
      "confidence": "high",
      "editable": "SAFE",
      "runs": [
        { "text": "Component list", "bold": true, "size": 11.0, "font": "Calibri" }
      ]
    },
    {
      "id": "b0007",
      "type": "table",
      "page": 1,
      "nRows": 4,
      "nCols": 3,
      "confidence": "high",
      "editable": "RESTRICTED",
      "sourceRowRange": [22, 25],
      "cells": [
        {
          "id": "b0007.r0c0",
          "row": 0, "col": 0, "rowSpan": 1, "colSpan": 2,
          "isHeader": true,
          "bbox": [94, 452, 600, 478],
          "blocks": [
            { "id": "b0007.r0c0.p0", "type": "paragraph", "runs": [] }
          ]
        }
      ]
    },
    {
      "id": "b0031",
      "type": "frozen_image",
      "page": 5,
      "bbox": [94, 410, 878, 720],
      "confidence": "low",
      "editable": "FROZEN",
      "reason": "vector flowchart; 14 chars outside all cell bboxes",
      "imageRef": "raster/p5_r0.png"
    }
  ]
}
```

Design notes:

- **IDs are assigned at construction.** `b0007.r0c0` is written down, not inferred.
  LLM edit intent references it directly — no fragile "third table, first row, first
  column" positional addressing.
- **`runs[]` carries character-level formatting** → maps directly onto docx4j `RPr`.
  Existing run-scoped editing needs no changes.
- **`bbox` is retained but not written into the docx.** Purpose is verification and
  debugging: locate the origin in the source PDF when something is wrong. Also keep a
  per-page PNG raster of the original for visual diff and VLM checks.
- **Declare what is explicitly abandoned.** Multi-column flow, exact page-break
  positions, headers/footers, textboxes, WordArt. Pick a set, drop them, document it.
  Attempting full preservation is how this class of project dies.
- `sourceRowRange` traces a normalized sub-table back to the original giant-table row
  indices — needed for debugging the splitter.

---

## Appendix C — Library and licensing notes

| Library | License | Role |
|---|---|---|
| Apache PDFBox | Apache 2.0 | Chars, coordinates, font info, graphics ops, structure tree. JVM-native. |
| tabula-java | MIT | Geometric table detection (reference / cross-check) |
| docx4j | LGPL / Apache dual | Already in use. Generator + executor. |
| Docling (`docling`, `docling-java`, `docling-serve`) | MIT | Vision route. Model weights (`granite-docling`) Apache 2.0. |
| pdfplumber / pikepdf | MIT | Python prototyping and probe development only |
| PyMuPDF (transitively via `pdf2docx`) | **AGPL-3.0** (Artifex dual-licenses commercially) | Flagged. Avoid in a closed-source commercial product without procurement sign-off. |
| Aspose.PDF for Java | Commercial | Optional accuracy baseline for comparison. `Flow`/`EnhancedFlow` only. |

Run all of these past legal review before committing — Apache 2.0 and MIT are clean in
principle, but bank environments have their own process.

---

## Open questions

1. **Signal distribution across real customer PDFs.** The single most important unknown
   in this entire design. Phase 0 exists to answer it. If ~80% look like Appendix A
   (Word export, tagged), the tagged route alone covers the bulk and the vision route
   can be deferred indefinitely.
2. **Final deliverable format.** If customers need a PDF back, the round trip is
   `PDF → docx → edit → export PDF`, and the exported PDF will not be byte-identical:
   kerning, line breaks, and page-break positions may shift. If any use case demands
   pixel-identical output (regulated filings), that case needs separate treatment —
   likely redaction + overlay on the original PDF rather than round-tripping.
3. **Confidence thresholds.** No principled way to set these a priori. Requires the
   golden set from Phase 3.
4. **FROZEN region granularity.** Per-region, per-page, or per-sub-table? Finer is
   better for editability, coarser is safer. Needs real data.
