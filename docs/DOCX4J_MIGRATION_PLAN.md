# Syncfusion DocIO → docx4j Migration Plan

**Branch:** `feature/docx4j-migration` (planning branch)  
**Status:** Review-only — no implementation in this document  
**Date:** 2026-06-29

---

## Executive summary

The production document-editing path is **Java Spring Boot + Syncfusion DocIO** (`doc-agent-server`). The LLM drives eight OpenAI function tools via `ToolExecutor`, which mutates an in-memory `WordDocument` and saves once per chat turn. **There is no PDF export today** — preview uses DocIO `FormatType.Html`. Final customer deliverables (docx → PDF) are a **new capability** to be built with **LibreOffice headless**, not a Syncfusion replacement of an existing feature.

This migration has two coupled goals:

1. **Replace Syncfusion DocIO** with **docx4j** for `.docx` load/save/mutate (licensing).
2. **Evolve the mutation model** from fragile integer indices + immediate save toward the architecture invariants you specified (structural IDs, mutation JSON batch, hash invariant, human review gate).

The plan keeps both engines behind a `DocumentMutationEngine` interface until parity is proven.

---

## A. Inventory — Syncfusion usage in this repository

### A.1 Java backend (active production path)

| File | Syncfusion API / capability | Operation class | docx4j equivalent | Gap flag |
|------|------------------------------|-----------------|---------------------|----------|
| `pom.xml` | `syncfusion-docio`, `syncfusion-javahelper`, `syncfusion-licensing` v29.1.33 | Dependency | `org.docx4j:docx4j-JAXB-ReferenceImpl` (+ optional `docx4j-export-FO`) | Maven repo change; no license jar |
| `SyncfusionLicenseConfig.java` | `SyncfusionLicenseProvider.registerLicense()` | Startup | **Remove** | — |
| `DocIoService.java` | `new WordDocument()` | Create empty doc | `WordprocessingMLPackage.createPackage()` + minimal body | Build minimal doc helper |
| `DocIoService.java` | `new WordDocument(path\|stream)` | **Read** / validate upload | `WordprocessingMLPackage.load(path\|stream)` | Validation = try parse + walk main document part |
| `DocIoService.java` | `document.save(..., FormatType.Docx)` | **Save** | `wordMLPackage.save(out)` | Round-trip fidelity test required |
| `DocIoService.java` | `document.save(..., FormatType.Html)` | **Preview render** | No first-class HTML in docx4j core; options below | **BUILD** — see §B.7 |
| `DocIoService.java` | `document.replace(find, replace, false, false)` | **Find/replace** (global, no format save) | No built-in API | **BUILD** run-aware scoped replace |
| `DocIoService.java` | `document.ensureMinimal()`, `getLastParagraph()` | Bootstrap | Manual JAXB paragraph insert | Trivial |
| `AgentService.java` | Holds `WordDocument` for full agent turn | Session DOM | Hold `WordprocessingMLPackage` or engine wrapper | — |
| `ToolExecutor.java` | `document.replace(...)` | Global replace | Custom `ModifyOperation` on resolved block | **BUILD** |
| `ToolExecutor.java` | `getLastSection().addParagraph()`, `appendText` | **Insert** (append) | Insert `P` after last block in body | Preserve sectPr |
| `ToolExecutor.java` | `body.getChildEntities().insert/removeAt` | **Insert/delete** paragraph | Insert/remove `P` or `Tbl` in body content list | Index stability → bookmarks |
| `ToolExecutor.java` | `clearParagraphText` + `appendText` | **Modify** (whole paragraph) | Replace runs in `P` or set first `R` text | **BUILD** rPr preservation |
| `ToolExecutor.java` | `WTable` row/cell iteration, `setCellText` | **Table read/modify cell** | Navigate `Tbl` → `Tr` → `Tc` → `P` | **BUILD** cell text replace with rPr |
| `DocumentSnapshot.java` | Walk sections/body/paragraphs/tables | **Structure extract** | `TraversalUtil` / body content walk | Map styles via `PPr`/`Style` |
| `DocumentSnapshot.java` | `BuiltinStyle`, `ListFormat.applyDefBulletStyle()` | Style apply | `PPr` + `NumPr` / style ID lookup | **BUILD** style name → styleId map |
| `DocumentController.java` | Delegates to `DocIoService.toHtmlBytes` | Preview endpoint | Via `DocumentRenderer` abstraction | — |
| `HealthController.java` | Reports `"engine": "syncfusion-docio"` | Metadata | Report active engine implementation | — |

**Not used in code (Syncfusion provides, we don't call):**

| Capability | Notes |
|------------|-------|
| `replace(..., saveFormatting=true)` | Planned in `JAVA_DOCIO_PLAN.md` but **not implemented** — current replace is format-lossy on cross-run matches |
| Bookmarks API | Zero usage — addressing is integer indices only |
| `FormatType.Pdf` | Zero usage — **no docx→PDF in Java service** |
| Mail merge, fields, tracked changes | Not touched |

### A.2 Python legacy stack (not primary; reference only)

| File | Engine | Role |
|------|--------|------|
| `doc_editor.py` | python-docx | Run-level then paragraph-level replace; same 8-tool contract |
| `api.py` | mammoth | HTML preview |
| `api.py` `save_sfdt` | Syncfusion **cloud** SFDT→docx | Unused by React UI |

The Python stack is useful as a **reference for run-level replace logic** to port into docx4j, not as a migration target.

### A.3 Frontend (Syncfusion — dormant)

| File | Status |
|------|--------|
| `frontend/package.json` | `@syncfusion/ej2-react-documenteditor` declared but **not imported** |
| `frontend/src/utils/selectionReplace.js` | Client-side batch replace — **orphaned** |
| Preview | iframe HTML from Java `GET /preview` |

Frontend Syncfusion removal is cleanup, not blocking for docx4j migration.

### A.4 Gap summary — what Syncfusion gave vs what we must build

| Capability | Syncfusion (available) | Current usage | docx4j | Action |
|------------|------------------------|---------------|--------|--------|
| Parse/save DOCX | ✅ High-level DOM | ✅ Used | ✅ `WordprocessingMLPackage` | Parity test |
| Global find/replace | ✅ One call | ✅ Used (no format save) | ❌ No equivalent | **Build** `RunSpanResolver` + scoped replace |
| Format-preserving replace | ✅ `saveFormatting` flag | ❌ Not used | ❌ Must hand-roll per-run `rPr` clone | **Build** — critical for compliance edits |
| Cross-run text spans | ✅ Internal | ❌ Not used | ❌ Split/merge `R` elements | **Build** |
| Table cell navigation | ✅ `WTable`/`WTableCell` | ✅ Basic set text | ✅ JAXB `Tbl/Tr/Tc` | Port logic; add row insert/delete |
| Table row insert/delete | ✅ API exists | ❌ Not implemented | ✅ JAXB insert `Tr` | **Implement** (required by invariants) |
| Merged cells | ✅ Supported in DOM | ❌ Not handled | ⚠️ `gridSpan`, `vMerge` | **Risk** — read-only support first |
| Bookmarks | ✅ API | ❌ Not used | ✅ `CTBookmark` / `CTMarkupRange` | **Implement** for stable IDs |
| HTML preview | ✅ `FormatType.Html` | ✅ Used | ⚠️ FO/XHTML export or alternate | **Abstract** `DocumentRenderer` |
| DOCX → PDF | ✅ `FormatType.Pdf` (unused) | ❌ None | ❌ Not in core | **LibreOffice headless** (see §B.7) |
| Upload validation | ✅ Open + getSections | ✅ Used | ✅ Load package | Equivalent |

### A.5 Current vs target architecture invariants

| Invariant | Current state | Target (this migration) |
|-----------|---------------|---------------------------|
| LLM emits structured intent, not OOXML | ✅ Tool calls (8 tools), not OOXML | ✅ Mutation batch JSON with `target_id` |
| modify / insert / delete | Partial — no table row ops, global replace | Full op set |
| Structural address, not text search | ❌ Integer indices + global `find` | Bookmarks + layered span |
| Hash invariant / rollback | ❌ Trace snapshots only, auto-save | Batch hash check + explicit approve |
| Human review gate | ❌ Save at end of chat turn | Persist proposals → approve → apply |
| DocumentMutationEngine abstraction | ❌ Direct `ToolExecutor` → Syncfusion | ✅ Interface + two impls |

**Important:** The migration is not a library swap alone — it implements the mutation contract your invariants describe, using the current Syncfusion code as a behavioral baseline for parity tests.

---

## B. Target design

### B.1 Layer diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  React frontend                                                  │
│  • Chat (propose) → Review diff → Approve/Reject → Download    │
│  • Preview: HTML iframe; optional PDF preview tab               │
└────────────────────────────┬────────────────────────────────────┘
                             │ REST
┌────────────────────────────▼────────────────────────────────────┐
│  Spring Boot                                                     │
│                                                                  │
│  ComplianceAgentService                                          │
│    → LLM structured output (MutationBatch JSON)                │
│    → MutationValidator (closed target set, op semantics)         │
│    → MutationProposalStore (pending batches)                     │
│    → on approve: DocumentMutationEngine.applyBatch()           │
│                                                                  │
│  DocumentMutationEngine  ◄── interface                           │
│    ├── SyncfusionMutationEngine   (Phase 1–3, flag off default)  │
│    └── Docx4jMutationEngine       (Phase 4+, flag on)            │
│                                                                  │
│  DocumentRenderer  ◄── interface (preview ≠ PDF fidelity)        │
│    ├── SyncfusionHtmlRenderer | Docx4jHtmlRenderer | Mammoth     │
│    └── LibreOfficePdfRenderer                                    │
│                                                                  │
│  BookmarkIndexer — inject/read dg_* bookmarks on ingest          │
│  NodeHashIndex   — SHA-256 of normalized text per addressable node│
└────────────────────────────┬────────────────────────────────────┘
                             │
                    docs/*.docx  +  proposals/*.json
```

### B.2 `DocumentMutationEngine` interface (sketch)

```java
public interface DocumentMutationEngine extends AutoCloseable {

    /** Load from path; optionally ensure bookmarks exist (inject if missing). */
    static DocumentMutationEngine open(Path docx, EngineConfig config);

    /** Deterministic structural index sent to LLM (closed set of target_ids). */
    StructuralIndex buildIndex();

    /** Snapshot node text hashes before apply — for invariant check. */
    Map<String, String> snapshotNodeHashes();

    /** Apply validated batch; throws MutationInvariantViolation if collateral change. */
    ApplyResult applyBatch(MutationBatch batch);

    /** Persist to path. */
    void save(Path target);

    /** Engine id for health/metrics. */
    String engineId();  // "syncfusion-docio" | "docx4j"
}

record StructuralIndex(
    String documentId,
    List<BlockDescriptor> blocks,   // target_id, type, text, runs[], table coords
    Set<String> validTargetIds
) {}

record ApplyResult(
    int appliedCount,
    Map<String, String> changedNodeIds,  // id → new hash
    List<String> warnings
) {}
```

**Coexistence:** Spring `@ConditionalOnProperty("app.mutation-engine")` or explicit factory reading `syncfusion` | `docx4j`. Both implementations must pass the same golden-file suite.

### B.3 Mutation contract schema

Single batch object returned by LLM (structured output / strict JSON schema):

```json
{
  "schema_version": 1,
  "explanation": "Replace allergen wording in row 3 of ingredients table.",
  "mutations": [
    {
      "op": "modify",
      "target_id": "dg_tbl0_r3_c2",
      "old_text": "Contains: soy, wheat",
      "old_text_occurrence": 0,
      "new_text": "Contains: soy, wheat, sesame"
    },
    {
      "op": "insert",
      "anchor_id": "dg_p14",
      "position": "after",
      "node_type": "paragraph",
      "content": { "text": "Revised per FDA guidance 2026.", "style": "Normal" }
    },
    {
      "op": "delete",
      "target_id": "dg_p99"
    },
    {
      "op": "insert",
      "anchor_id": "dg_tbl0_r5",
      "position": "after",
      "node_type": "table_row",
      "content": {
        "cells": [
          { "text": "New row col0" },
          { "text": "New row col1" }
        ]
      }
    }
  ]
}
```

**Rules enforced server-side (`MutationValidator`):**

| Rule | Enforcement |
|------|-------------|
| `target_id` / `anchor_id` ∈ `validTargetIds` from this request's index | Reject + repair prompt |
| `op` valid for node type | e.g. no `delete` on non-deletable container |
| `old_text` non-empty for `modify` | Reject |
| `old_text` matches live text at target (after normalize) | Optimistic lock failure → reject |
| At most one mutation per `target_id` per batch | Reject duplicates |
| `insert` requires `anchor_id` + `position` | Never locate by free text |

**LLM delivery:** Replace ad-hoc tool loop for compliance step with **one structured output call** (OpenAI `response_format` / strict tool) producing `MutationBatch`. Keep exploratory `read_document` as a separate optional tool only during development; production compliance path is **index in prompt → batch out**.

**Repair loop:** On validation failure, append error list to messages; retry ≤ 2 times; then fail with persisted error trace.

### B.4 Addressing / bookmarking scheme

**Principle:** Position-independent anchors survive insert/delete elsewhere.

**Bookmark naming convention:**

```
dg_p{seq}           — top-level body paragraph (outside tables)
dg_tbl{n}           — table block
dg_tbl{n}_r{r}_c{c} — logical cell (handles merged cells via anchor cell)
dg_tbl{n}_r{r}      — table row (for row insert/delete anchors)
```

**Injection pass (`BookmarkIndexer`):** On first open (or upstream ingest hook after pdf2docx):

1. Walk body blocks in document order; assign sequential IDs.
2. Wrap each addressable unit with Word bookmark pair:
   - `w:bookmarkStart w:name="dg_p12" w:id="…"`
   - content
   - `w:bookmarkEnd w:id="…"`
3. For table cells, bookmark the first paragraph in the cell (or the `Tc` content wrapper).
4. Persist bookmarked docx back to disk (idempotent: skip if `dg_` bookmarks already cover index).

**Layered addressing for `modify`:**

```
target_id  →  resolves to bookmark → block node
old_text + occurrence  →  span within concatenated plain text of block runs
```

Implementation: `BlockTextIndex` flattens runs to `(runRef, startOffset, endOffset)`; find span; apply edit by splitting/merging `w:r` and cloning `w:rPr` from the run at span start.

**Index sent to LLM:** Include `target_id`, `type`, full block text, optional run breakdown — **never** ask model to search by content.

### B.5 Validation pipeline

```
1. Load document → BookmarkIndexer.ensureBookmarks()
2. engine.buildIndex() → StructuralIndex + validTargetIds
3. LLM → MutationBatch (structured)
4. MutationValidator.validate(batch, index, liveEngine)
     - schema (Jackson + JSON Schema / custom)
     - closed world target ids
     - semantic rules (op/type, old_text match)
5. MutationProposalStore.save(pending) — do NOT apply yet
6. DiffService.renderBeforeAfter(pending) — for UI
7. User POST /api/mutations/{id}/approve
8. ApplyPipeline.run(batch) — see §B.6
9. engine.save(working copy)
```

### B.6 Apply algorithm (docx4j)

**Precondition:** `beforeHashes = engine.snapshotNodeHashes()`

For each mutation in order (or reject batch if reordering unsafe — **v1: document order sort by target path**):

#### `modify`

```
resolve(target_id) → block (P or Tc's primary P)
assert plainText(block).contains(old_text) at occurrence
locate RunSpan via BlockTextIndex
if span within single R:
    replace t nodes, keep rPr
else:
    clone rPr from first run
    delete t across runs, insert new R with cloned rPr at span start
    remove emptied runs
re-hash target_id only
```

#### `insert` (paragraph)

```
resolve(anchor_id) → anchor block
create new P (with PPr cloned from anchor or Normal style)
insert after/before in body content list (or after anchor P within cell)
assign new bookmark dg_p{next} — update index if re-opened
```

#### `insert` (table_row)

```
resolve(anchor_id) → Tr (via dg_tbl{n}_r{r})
clone Tr template from anchor row (preserve tcPr, gridSpan)
clear cell text placeholders
fill from content.cells[]
insert Tr after anchor in tbl content
re-index table bookmarks OR use row-relative ids (prefer re-index on save for v1)
```

#### `delete`

```
paragraph: remove P from parent content list
table_row: remove Tr (reject if last row in header section — validator)
```

**Post-apply invariant:**

```
afterHashes = engine.snapshotNodeHashes()
changed = { id | beforeHashes[id] != afterHashes[id] }
targeted = { all target_ids touched by batch + new ids from inserts }

if changed != targeted:
    restore from in-memory clone taken at batch start
    throw MutationInvariantViolation(changed - targeted, targeted - changed)
```

**Rollback mechanism:**

- **Within batch:** in-memory `WordprocessingMLPackage` clone before apply (docx4j deep copy via reload from byte array or `XmlUtils.deepCopy`).
- **After failed save:** don't write disk.
- **After bad approve:** restore from trace snapshot (`before.docx` already exists in trace module).
- **User rollback:** Version store from `VERSION_CONTROL_PLAN.md` (file copy restore).

### B.7 Rendering — HTML preview and DOCX → PDF

These are **separate concerns** per invariant #9.

#### HTML preview (human review UI)

| Option | Pros | Cons | Recommendation |
|--------|------|------|----------------|
| Keep Syncfusion HTML during migration | Known baseline | Keeps license longer | Phase 1–3 only via `SyncfusionHtmlRenderer` |
| docx4j Export FO → HTML | Same stack | Layout often poor | Fallback |
| **Mammoth** (Java: call via ProcessBuilder or port) | Good enough for text review | Not WYSIWYG | **Candidate** for post-cutover preview |
| LibreOffice → HTML | Consistent with PDF | Slow per preview | Optional |

**Plan:** Introduce `DocumentRenderer` interface:

```java
interface DocumentRenderer {
    byte[] toHtml(Path docx);
    byte[] toPdf(Path docx);  // separate code path
}
```

Preview iframe uses HTML; **do not judge mutation correctness from HTML layout**.

#### DOCX → PDF (final deliverable) — LibreOffice headless

**Recommended approach for OpenShift/Linux:**

1. **Container image:** Extend Java app image (or sidecar) with `libreoffice-writer` / `libreoffice-headless` packages (RHEL UBI or Debian slim). Typical size +400–600 MB.

2. **Conversion service (`LibreOfficePdfRenderer`):**

```text
soffice --headless --norestore --convert-to pdf:writer_pdf_Export \
        --outdir /tmp/out /path/to/doc.docx
```

3. **Java integration options:**

| Approach | Notes |
|----------|-------|
| **JODConverter** (local) | Spring-friendly; wraps `soffice` with process pool; **recommended** |
| Raw `ProcessBuilder` | Minimal deps; must handle timeouts, temp dirs, concurrency |
| Dedicated conversion microservice | Scales LO separately; good for OpenShift if CPU-heavy |

4. **OpenShift deployment patterns:**

   - **Sidecar container** in same pod: shared `emptyDir` volume; Java writes docx, sidecar converts. Isolates LO crashes.
   - **Init not suitable** — conversion is on-demand per request.
   - **Resource limits:** LO is CPU/RAM hungry; set limits (e.g. 1 CPU, 1Gi) and queue conversions.
   - **Read-only root:** LO needs writable `$HOME` and temp; mount `emptyDir` at `/tmp/libreoffice-profile`.

5. **Concurrency:** Single `soffice` instance can handle sequential jobs; for parallel requests use **process pool** (JODConverter `LocalOfficeManager` with `maxTasksPerProcess`) or **semaphore** limiting concurrent conversions.

6. **Fidelity expectations:**

   - pdf2docx-origin docs are messy (floating text boxes, partial tables) — LO PDF output reflects **current docx structure**, not original PDF pixel-perfect.
   - Compare against **current production baseline** once Syncfusion PDF is generated for a sample set (Phase 3 parity) — even though Syncfusion PDF isn't wired today, generate it offline for comparison.
   - **Measurement:** visual diff (pixel) + structural diff (PDF text extraction per page region) on 10–20 hardest real documents.

7. **Alternatives considered (not recommended as primary):**

   - docx4j-export-FO + Apache FOP — poor table fidelity on complex docs.
   - Syncfusion `FormatType.Pdf` — defeats licensing goal.
   - Microsoft Graph / Azure — cost + connectivity.
   - OnlyOffice — similar to LO but heavier licensing review.

8. **API surface:**

```
GET  /api/documents/{name}/preview        → HTML (existing)
GET  /api/documents/{name}/pdf            → application/pdf (new)
POST /api/documents/{name}/pdf            → convert after approve, return job id
GET  /api/documents/{name}/pdf/{jobId}    → async if needed
```

**Workflow:** Mutations apply to docx first (hash-verified); PDF is **render-only** export for delivery — never feed PDF back into mutation engine.

### B.8 Human review gate (manual, no graph framework)

```
POST /api/compliance/propose   { doc_name, prompt, ... }
  → returns { proposal_id, mutations[], diff_html, diff_summary }

GET  /api/compliance/proposals/{id}

POST /api/compliance/proposals/{id}/approve
  → ApplyPipeline → save docx → optional trigger PDF render

POST /api/compliance/proposals/{id}/reject
  → discard pending
```

Persist under `docs/.proposals/{id}.json` + reference `before.docx` snapshot. Reuse existing trace module for audit linking.

### B.9 Refactor map from current code

| Current | Target |
|---------|--------|
| `ToolExecutor` | `SyncfusionMutationEngine` implementing `DocumentMutationEngine`; later slim to adapter |
| `DocumentSnapshot` | `StructuralIndexBuilder` (engine-specific + shared interface) |
| `AgentService.runChat` immediate save | Split: `propose` (no save) vs `approve` (save) — chat UX may show proposal first |
| `DocIoService.replaceText` | Delegate to engine or deprecate |
| `DocIoService.toHtmlBytes` | `DocumentRenderer.toHtml` |
| `SyncfusionLicenseConfig` | Remove after cutover |

---

## C. Risk register

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|------------|--------|------------|
| R1 | **PDF fidelity gap** vs Word desktop / Syncfusion PDF | High | Medium | Separate test suite; treat PDF as delivery artifact; docx hash invariant is source of truth for edits |
| R2 | **Run splitting** after pdf2docx — one word across many `w:r` | High | High | Bookmark + `BlockTextIndex`; cross-run merge with rPr clone; golden tests on real docs |
| R3 | **Bookmarks vs fields/track changes** | Medium | Medium | Strip or ignore field codes in text index; test docs with REF, PAGE; don't bookmark inside field runs |
| R4 | **Merged table cells** — row/col indices ambiguous | High | High | v1: address anchor cell only; document merged cells in index; reject row ops spanning merges |
| R5 | **Table row insert/delete** breaks bookmark IDs | Medium | High | Re-run `BookmarkIndexer` after structural table ops before next LLM pass; or use stable table/row UUIDs in custom doc props |
| R6 | **Anchor stability for inserts** after parallel proposals | Low | Medium | Serialize approvals per document; optimistic lock on doc version |
| R7 | **docx4j round-trip** alters unrelated OOXML | Medium | High | Hash invariant catches; compare XML diff in tests; pin docx4j version |
| R8 | **LibreOffice headless** hangs/crashes on corrupt docx | Medium | Medium | Timeouts, process kill, sidecar isolation, fallback error to user |
| R9 | **OpenShift image size / security** scanning LO | Medium | Low | Sidecar pattern; slim JRE main container |
| R10 | **Performance** — bookmark inject on large docs | Low | Medium | Inject once at ingest; cache index in memory per session |
| R11 | **Regression** — integer-index agent vs new batch model | Medium | Medium | Parallel run both paths under feature flag during Phase 3 |
| R12 | **HTML preview drift** after engine swap | Medium | Low | Preview labeled "approximate"; PDF tab for layout check |

### How to measure PDF fidelity (R1)

1. **Corpus:** 10–20 production-like docx (table-heavy, from pdf2docx pipeline).
2. **Baselines:** Generate PDF via Syncfusion (offline tool) and via LibreOffice.
3. **Metrics:**
   - Page count delta
   - Extracted text diff (Apache PDFBox `PDFTextStripper`)
   - Visual diff (ImageMagick compare or `pdf-diff`) — threshold % pixels changed
   - Table cell count heuristic (text block alignment)
4. **Acceptance:** Define per customer — e.g. "text content 100% match; layout ≤ 5% pixel delta acceptable."

---

## D. Phased migration

Each phase leaves the system **shippable**.

### Phase 0 — Branch & baseline (1 week)

- [ ] Create `feature/docx4j-migration` branch; add this plan.
- [ ] Capture **golden corpus**: 5–10 `.docx` fixtures + recorded mutation batches (from current tool behavior).
- [ ] Offline script: Syncfusion PDF export for corpus (comparison baseline only).
- [ ] Add Maven profile for docx4j deps (no runtime switch yet).

**Exit:** Golden inputs stored under `doc-agent-server/src/test/resources/golden/`.

### Phase 1 — Abstraction without behavior change (1–2 weeks)

- [ ] Define `DocumentMutationEngine`, `MutationBatch`, `MutationValidator` interfaces/records.
- [ ] Implement `SyncfusionMutationEngine` — wrap existing `ToolExecutor` + `DocumentSnapshot` logic.
- [ ] Wire `AgentService` through engine factory; default = Syncfusion.
- [ ] Unit tests: engine open/save/index parity with current snapshot format.

**Exit:** All existing behavior via interface; zero user-visible change.

### Phase 2 — Bookmark indexer + structural IDs (1–2 weeks)

- [ ] Implement `BookmarkIndexer` for Syncfusion first (prove scheme).
- [ ] Port `BookmarkIndexer` to docx4j.
- [ ] `StructuralIndex` uses `dg_*` ids; keep legacy index in parallel for diff logging.
- [ ] Upload/ingest hook: ensure bookmarks on save.

**Exit:** Documents carry stable IDs; LLM prompt can switch to id-based examples.

### Phase 3 — Mutation batch + hash invariant (2 weeks)

- [ ] Implement `MutationValidator` + `ApplyPipeline` on **Syncfusion** engine.
- [ ] New compliance endpoint `POST /propose` returning batch (feature-flagged).
- [ ] Hash invariant + rollback on Syncfusion.
- [ ] Human review: proposal store + approve/reject endpoints (minimal UI or API-only).

**Exit:** Full invariant semantics proven on known engine before docx4j.

### Phase 4 — docx4j engine behind flag (2–3 weeks)

- [ ] `Docx4jMutationEngine` — load/save/index/apply.
- [ ] Build `RunSpanResolver`, cross-run modify, table cell modify.
- [ ] Table row insert/delete.
- [ ] `app.mutation-engine=docx4j` in dev/staging.
- [ ] **Parity suite:** same golden files → compare output docx XML text nodes + hash sets.

**Exit:** docx4j passes ≥ 95% golden tests; failures documented.

### Phase 5 — LibreOffice PDF (1–2 weeks)

- [ ] Add LO to Dockerfile / sidecar manifest.
- [ ] `LibreOfficePdfRenderer` + JODConverter pool.
- [ ] `GET /pdf` endpoint; async for large docs.
- [ ] PDF fidelity suite vs Syncfusion baseline (R1 metrics).

**Exit:** End-to-end: propose → approve → docx + PDF download on OpenShift-like env.

### Phase 6 — Parity hardening & cutover (1–2 weeks)

- [ ] Run both engines in CI on every PR (`syncfusion` + `docx4j` matrix).
- [ ] Fix parity gaps or document accepted diffs.
- [ ] Default `app.mutation-engine=docx4j` in production config.
- [ ] Monitor traces for `MutationInvariantViolation`.

**Exit:** Production on docx4j.

### Phase 7 — Remove Syncfusion (1 week)

- [ ] Remove Maven deps, `SyncfusionLicenseConfig`, license env vars.
- [ ] Remove frontend Syncfusion packages + `selectionReplace.js`.
- [ ] Update `HealthController`, docs, `start-java.ps1`.
- [ ] Optional: remove Python `save_sfdt` cloud dependency.

**Exit:** Zero Syncfusion artifacts; license cost eliminated.

### Parity testing strategy

| Layer | Method |
|-------|--------|
| **Unit** | `RunSpanResolver` on synthetic multi-run paragraphs |
| **Golden docx** | `input.docx` + `mutations.json` → assert `expected.docx` (normalize XML: strip timestamps, rsids) |
| **Hash invariant** | Every golden test asserts `changedIds == targetedIds` |
| **Engine cross-check** | Same batch applied by Syncfusion vs docx4j → compare plain text per `target_id` |
| **PDF** | Render both engines' docx output through LO; compare to Syncfusion PDF baseline |
| **Real docs** | Manual QA on 3 hardest compliance documents per release |

**CI command sketch:**

```bash
mvn test -Pparity -Dapp.mutation-engine=syncfusion
mvn test -Pparity -Dapp.mutation-engine=docx4j
mvn test -Ppdf-fidelity  # requires LO in CI image
```

---

## E. Open questions / assumptions

Confirm before Phase 2 execution:

| # | Question | Default assumption if silent |
|---|----------|------------------------------|
| Q1 | Is **immediate save after chat** acceptable to change to **propose → approve** for all edits, or only compliance workflow? | Compliance uses approve; exploratory chat keeps auto-save until UI ready |
| Q2 | Should bookmarks be injected **upstream at pdf2docx ingest** or **on first open** in this service? | On first open in Java service (simpler); upstream optional later |
| Q3 | **Merged cells** — reject row mutations, or support in v1? | Reject with clear validation error in v1 |
| Q4 | **Header/footer** content — in scope for mutations? | Out of scope v1 (body + tables only), matching current `DocumentSnapshot` |
| Q5 | **LibreOffice in OpenShift** — sidecar vs fat image vs external service? | Sidecar recommended; need cluster policy confirmation |
| Q6 | **PDF acceptance criteria** — text-exact vs visual layout? | Text-exact required; layout tolerance TBD with stakeholders |
| Q7 | Keep **HTML preview** via Syncfusion until Phase 7, or switch preview engine earlier? | Keep Syncfusion HTML until docx4j cutover; then Mammoth or LO HTML |
| Q8 | **Python stack** — deprecate entirely or maintain in parallel? | Deprecate; Java is sole production path |
| Q9 | docx4j variant: **ReferenceImpl vs MOXy** — any enterprise XML restrictions? | JAXB-ReferenceImpl (docx4j default) |
| Q10 | Maximum document size / conversion timeout SLO? | 50 MB upload (current); 120s PDF timeout initial |

---

## Appendix: docx4j capability mapping cheat sheet

| Task | docx4j approach |
|------|-----------------|
| Load/save | `WordprocessingMLPackage.load/save` |
| Walk body | `MainDocumentPart.getContent()` or `TraversalUtil` |
| Get/set run text | `R` → `Text` nodes; preserve `RPr` via `XmlUtils.deepCopy` |
| Insert paragraph | `mainPart.addObject(p)` or list insert at index |
| Table row | `Tbl` content list of `Tr`; clone row XML |
| Bookmarks | `CTBookmark` + `CTMarkupRange` with matching `w:id` |
| Plain text extract | `TextUtils.getText(p)` or custom run walker |
| Clone before mutate | `ByteArrayOutputStream` save → reload |

---

## Recommended immediate next steps (after plan approval)

1. Answer open questions Q1, Q5, Q6.
2. Execute Phase 0 — golden corpus + branch setup.
3. Phase 1 — `DocumentMutationEngine` wrapper (no docx4j yet).
4. Spike (time-boxed 2 days): docx4j `RunSpanResolver` on one pdf2docx fixture with cross-run replace + hash check.

**Estimated total:** 10–14 weeks for Phases 0–7 with one senior engineer, assuming OpenShift LO deployment is approved.
