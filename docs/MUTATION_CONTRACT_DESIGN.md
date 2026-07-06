# Minimal Mutation Contract — docx4j Agent Server

**Purpose:** Design reference for a new `docx4j-agent-server/` project.  
**Core rule:** The LLM never locates content by searching the document. Java resolves `target_id` → exactly one node. `old_text` is a **lock**, not a search key.

---

## 1. Why ID-based modify beats find/replace

| Approach | Problem |
|----------|---------|
| `find "soy"` → replace everywhere | Same word in ingredients, footnotes, headers — collateral damage |
| Document-wide replace | Cannot limit to “this table cell” or “this list item” |

| ID-based approach | Benefit |
|-------------------|---------|
| `target_id: "dg_tbl0_r3_c2"` | Java opens **one** table cell — search is scoped to ~50 chars, not 50 pages |
| `old_text: "Contains: soy"` | Proves the cell still says what the LLM saw; if someone edited it, apply **fails** instead of corrupting |
| Run-aware replace inside block | Bold/italic/font in untouched runs stay intact |

**`old_text` is not a location mechanism.** Location = `target_id` (+ optional `occurrence` when the same phrase appears twice *inside that one block*).

---

## 2. Minimal mutation JSON schema

### 2.1 Batch envelope (LLM output)

```json
{
  "schema_version": 1,
  "explanation": "Human-readable summary for review UI.",
  "mutations": [ ]
}
```

### 2.2 `modify` — replace text inside one block, preserve format

Use for: fix wording in a paragraph, list item, or table cell.

```json
{
  "op": "modify",
  "target_id": "dg_tbl0_r3_c2",
  "old_text": "Contains: soy, wheat",
  "occurrence": 0,
  "new_text": "Contains: soy, wheat, sesame"
}
```

| Field | Required | Rules |
|-------|----------|-------|
| `op` | yes | `"modify"` |
| `target_id` | yes | Must be in closed set from `StructuralIndex` sent with this request |
| `old_text` | yes | Non-empty; must match a substring of block plain text after whitespace normalize |
| `occurrence` | no (default `0`) | 0-based index when `old_text` appears multiple times **in this block only** |
| `new_text` | yes | Replacement for that span only |

**Whole-block rewrite** (rare): set `old_text` to the full block text and `new_text` to the replacement. Still one block, one mutation.

### 2.3 `insert` — add paragraph or table row relative to an anchor

```json
{
  "op": "insert",
  "anchor_id": "dg_p14",
  "position": "after",
  "node_type": "paragraph",
  "text": "Revised per FDA guidance, June 2026.",
  "style": "Normal"
}
```

Table row (v2 — optional in minimal v1):

```json
{
  "op": "insert",
  "anchor_id": "dg_tbl0_r5",
  "position": "after",
  "node_type": "table_row",
  "cells": [
    { "text": "Column A" },
    { "text": "Column B" }
  ]
}
```

| Field | Required | Rules |
|-------|----------|-------|
| `anchor_id` | yes | Existing block id; insert relative to this node |
| `position` | yes | `"before"` \| `"after"` |
| `node_type` | yes | `"paragraph"` \| `"table_row"` |
| `text` / `cells` | conditional | Paragraph: `text`. Row: `cells[]` with same length as table column count (or anchor row) |

**Format on insert:** Clone `pPr` (and first run’s `rPr` if present) from anchor paragraph — do not invent styles.

### 2.4 `delete` — remove one block

```json
{
  "op": "delete",
  "target_id": "dg_p99"
}
```

| Field | Required | Rules |
|-------|----------|-------|
| `target_id` | yes | Paragraph or table row id only (not whole table in v1) |

### 2.5 Structural index (server → LLM context, not mutated by LLM)

Built deterministically before each propose call:

```json
{
  "document_id": "label-spec.docx",
  "blocks": [
    {
      "target_id": "dg_p0",
      "type": "paragraph",
      "text": "PRODUCT SPECIFICATION",
      "style": "Title"
    },
    {
      "target_id": "dg_tbl0_r3_c2",
      "type": "table_cell",
      "table_index": 0,
      "row": 3,
      "col": 2,
      "text": "Contains: soy, wheat"
    }
  ]
}
```

LLM may only reference `target_id` / `anchor_id` values appearing in `blocks[]`.

### 2.6 JSON Schema (draft-07 sketch)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["schema_version", "mutations"],
  "additionalProperties": false,
  "properties": {
    "schema_version": { "const": 1 },
    "explanation": { "type": "string" },
    "mutations": {
      "type": "array",
      "maxItems": 50,
      "items": { "$ref": "#/definitions/mutation" }
    }
  },
  "definitions": {
    "mutation": {
      "oneOf": [
        { "$ref": "#/definitions/modify" },
        { "$ref": "#/definitions/insert" },
        { "$ref": "#/definitions/delete" }
      ]
    },
    "modify": {
      "type": "object",
      "required": ["op", "target_id", "old_text", "new_text"],
      "additionalProperties": false,
      "properties": {
        "op": { "const": "modify" },
        "target_id": { "type": "string", "pattern": "^dg_" },
        "old_text": { "type": "string", "minLength": 1 },
        "occurrence": { "type": "integer", "minimum": 0, "default": 0 },
        "new_text": { "type": "string" }
      }
    },
    "insert": {
      "type": "object",
      "required": ["op", "anchor_id", "position", "node_type"],
      "additionalProperties": false,
      "properties": {
        "op": { "const": "insert" },
        "anchor_id": { "type": "string", "pattern": "^dg_" },
        "position": { "enum": ["before", "after"] },
        "node_type": { "enum": ["paragraph", "table_row"] },
        "text": { "type": "string" },
        "style": { "type": "string" },
        "cells": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["text"],
            "properties": { "text": { "type": "string" } }
          }
        }
      }
    },
    "delete": {
      "type": "object",
      "required": ["op", "target_id"],
      "additionalProperties": false,
      "properties": {
        "op": { "const": "delete" },
        "target_id": { "type": "string", "pattern": "^dg_" }
      }
    }
  }
}
```

---

## 3. Overcoming format preservation (without global find/replace)

### 3.1 Pipeline

```
StructuralIndex.build(doc)
    → LLM receives blocks[] with target_id + text
    → LLM returns MutationBatch (ids + old_text + new_text)
    → MutationValidator
    → MutationApplier (per mutation, one block)
    → NodeHashGuard (collateral-change check)
```

### 3.2 Resolve `target_id` → single edit surface

| `type` | Edit surface |
|--------|----------------|
| `paragraph` | All `w:r` children of the bookmarked `w:p` |
| `table_cell` | All runs in the cell’s primary `w:p` (v1: first paragraph only) |

Bookmark wraps the block at ingest (`BookmarkIndexer`). No text search in the document.

### 3.3 `old_text` verification (optimistic lock)

```text
blockText = flattenRunsToPlainText(block)   // "Contains: soy, wheat"
normalized = normalizeWs(blockText)         // NFC, collapse spaces

find start = nthIndexOf(normalized, old_text, occurrence)
if start < 0 → reject "STALE_TARGET: old_text not found in dg_tbl0_r3_c2"

end = start + len(old_text)
→ span [start, end) in plain-text coordinates
```

If the document changed since the index was built, apply fails **safely** — no silent wrong edit.

### 3.4 Map plain-text span → runs (format preservation)

Build once per block:

```text
BlockTextIndex:
  runs: [
    { runRef: R0, textStart: 0,  textEnd: 10, rPr: ... },   // "Contains: "
    { runRef: R1, textStart: 10, textEnd: 13, rPr: bold },   // "soy"
    { runRef: R2, textStart: 13, textEnd: 21, rPr: ... },  // ", wheat"
  ]
```

**Case A — span inside one run** (easy):

```text
Replace w:t text inside R1 only.
Clone R1.w:rPr unchanged.
```

**Case B — span crosses runs** (common after pdf2docx):

```text
Example: old_text = "soy, wheat" spans R1 + R2

1. Clone rPr from R1 (first run in span)
2. Set R1.w:t = part before span (or remove if empty)
3. Delete R2..Rk in span
4. Insert new R_new with cloned rPr and new_text at span position
5. Keep runs before/after span untouched
```

**Case C — whole-block replace:**

```text
old_text == full blockText → replace text in all runs with single R_new
  OR keep first run's rPr and one w:t with new_text, delete other runs
```

### 3.5 What stays unchanged

| Element | On partial modify |
|---------|-------------------|
| Runs outside span | Untouched XML |
| `w:rPr` in affected runs | Cloned from first run in span |
| `w:pPr` (style, list level) | Never touched on `modify` |
| Other blocks | Never opened |

### 3.6 Collateral-change proof (hash guard)

```text
Before batch:
  hashes[id] = sha256(normalizeWs(blockText(id)))  for every block id

After each mutation:
  re-hash only target_id (and new ids from insert)

After batch:
  changed = { id | hashes_before[id] != hashes_after[id] }
  targeted = { ids from mutations } ∪ { new ids }

  if changed != targeted → rollback entire batch from memory snapshot
```

This replaces the need for “did find/replace hit the wrong place?” — wrong place **cannot happen** if you never search globally.

### 3.7 LLM guidance (prompt rules)

1. Always copy `old_text` **exactly** from the `blocks[].text` field (substring).
2. Never invent a `target_id` — only use ids from the provided list.
3. Prefer the **smallest** `old_text` that uniquely identifies the edit within that block.
4. If the same phrase appears twice in one cell, set `occurrence` to `0` or `1`.
5. One `target_id` at most once per batch.

---

## 4. Java class list (minimal)

New project: `docx4j-agent-server/` (Spring Boot 3, Java 21).

```
docx4j-agent-server/
└── src/main/java/com/docgen/
    ├── DocAgentApplication.java
    │
    ├── config/
    │   ├── AppConfig.java
    │   └── AppProperties.java          # docs-dir, openai key, lo path
    │
    ├── model/                          # JSON records (Jackson)
    │   ├── MutationBatch.java          # schema_version, explanation, mutations
    │   ├── Mutation.java               # sealed interface / @JsonTypeInfo
    │   ├── ModifyMutation.java
    │   ├── InsertMutation.java
    │   ├── DeleteMutation.java
    │   ├── StructuralIndex.java        # document_id, blocks
    │   ├── BlockDescriptor.java        # target_id, type, text, table coords
    │   ├── ProposeRequest.java
    │   ├── ProposeResponse.java        # proposal_id, batch, index snapshot
    │   └── ApplyResult.java            # applied, changed_ids, errors
    │
    ├── index/
    │   ├── BookmarkIndexer.java        # inject dg_* bookmarks on load
    │   ├── BookmarkResolver.java       # target_id → P or Tc
    │   ├── StructuralIndexBuilder.java # walk doc → BlockDescriptor list
    │   └── BlockTextIndex.java         # plain text ↔ run offsets
    │
    ├── mutation/
    │   ├── MutationValidator.java      # closed world, op rules, old_text lock
    │   ├── MutationApplier.java        # dispatch modify/insert/delete
    │   ├── ModifyApplier.java          # span replace, run split/merge
    │   ├── InsertApplier.java
    │   ├── DeleteApplier.java
    │   ├── RunEditor.java              # low-level w:r / w:t / rPr clone
    │   └── NodeHashGuard.java          # before/after hash sets, rollback
    │
    ├── document/
    │   ├── DocumentSession.java        # holds WordprocessingMLPackage in memory
    │   ├── DocumentLoader.java         # load/save path
    │   └── TextNormalizer.java         # NFC, ws collapse for compare
    │
    ├── proposal/
    │   ├── ProposalStore.java          # persist pending batch + before snapshot
    │   └── ProposalService.java        # propose → validate → store; approve → apply
    │
    ├── llm/
    │   ├── ComplianceClient.java       # structured output → MutationBatch
    │   └── MutationJsonSchema.java     # OpenAI response_format / tool schema
    │
    ├── render/
    │   └── LibreOfficePdfRenderer.java # docx → pdf (optional v1)
    │
    └── api/
        ├── DocumentController.java     # upload, download, list
        ├── ProposeController.java      # POST /propose
        └── ApproveController.java      # POST /proposals/{id}/approve|reject
```

### Class responsibilities (one line each)

| Class | Does |
|-------|------|
| `MutationBatch` | Deserialize LLM JSON; list of `Mutation` |
| `MutationValidator` | `target_id ∈ index`, `old_text` matches, no duplicate targets |
| `StructuralIndexBuilder` | Deterministic index for LLM context |
| `BookmarkIndexer` | Idempotent `dg_*` bookmark injection |
| `BookmarkResolver` | `target_id` → JAXB node (`P`, `Tr`, or cell’s `P`) |
| `BlockTextIndex` | Flatten runs + map char offsets to `R` elements |
| `ModifyApplier` | Verify lock → locate span → `RunEditor` → done |
| `RunEditor` | Split/merge runs; `XmlUtils.deepCopy(rPr)` |
| `NodeHashGuard` | `changed == targeted` or rollback |
| `ProposalService` | Orchestrates propose/approve; no save until approve |

### Test classes (minimal)

```
src/test/java/com/docgen/
├── mutation/
│   ├── ModifyApplierTest.java          # single-run, cross-run, stale old_text
│   ├── BlockTextIndexTest.java
│   └── NodeHashGuardTest.java
└── golden/
    └── GoldenMutationTest.java         # input.docx + batch.json → expected text per id
```

---

## 5. v1 scope cut (recommended)

**Include:**

- `modify` on `paragraph` and `table_cell`
- `insert` / `delete` paragraph only
- Bookmarks + structural index
- `old_text` lock + hash guard
- `propose` / `approve` API

**Defer:**

- `insert` / `delete` table row
- Merged cells
- PDF render (add in v1.1)
- HTML preview (download docx only, or Mammoth later)

---

## 6. Example end-to-end

**Index excerpt sent to LLM:**

```json
{ "target_id": "dg_tbl0_r3_c2", "type": "table_cell", "text": "Contains: soy, wheat" }
```

**LLM returns:**

```json
{
  "schema_version": 1,
  "explanation": "Add sesame to allergen declaration in ingredients table.",
  "mutations": [{
    "op": "modify",
    "target_id": "dg_tbl0_r3_c2",
    "old_text": "soy, wheat",
    "new_text": "soy, wheat, sesame"
  }]
}
```

**Java:**

1. Resolve `dg_tbl0_r3_c2` → one `Tc` → one `P` → runs `[R0..Rk]`
2. Verify `"soy, wheat"` at occurrence 0 in that cell only
3. Replace span in runs; clone `rPr` from first run in span
4. Re-hash `dg_tbl0_r3_c2`; assert no other ids changed
5. Save on approve

**No document-wide search. No accidental match in a different table.**

---

## 7. Bookmark ID convention

```
dg_p{n}                 top-level body paragraph
dg_tbl{t}_r{r}_c{c}     table cell (editable unit)
dg_tbl{t}_r{r}          table row (for row insert/delete in v2)
```

Inject on first document open; persist bookmarked docx to disk.
