# Evals — automated accuracy tests for the AI doc-editor

Replaces the interactive `scripts/functional_test_runner.py` (which needed a human
to press p/f/a for every case). Two tiers:

| Tier | Catalog | Needs LLM? | Cost | Speed | What it proves |
|------|---------|-----------|------|-------|----------------|
| **Engine** | `catalog-engine.json` | no | free | ~5 s | the applier/validator/hash-guard do exactly what a well-formed mutation batch says; bad batches are rejected cleanly |
| **LLM** | `catalog.json` | yes | ~$0.0015/case (Gemini flash-lite) | ~1–3 s/case | the model turns a plain-language request into the *right* mutation batch |

Run the engine tier in CI on every change. Run the LLM tier before releases and
when changing prompts / the model.

## Running

```bash
cd docx4j-agent-server && mvn spring-boot:run        # in one terminal

# engine tier (deterministic, free)
python evals/run.py --catalog evals/catalog-engine.json

# LLM tier (real calls — needs a provider key in repo-root .env; see below)
python evals/run.py                                  # all
python evals/run.py --only format                    # id/category/op_type substring
python evals/run.py --model gpt-4.1-mini              # model override
python evals/run.py --budget-usd 0.50                 # abort once cumulative cost would exceed this

# just dump a fresh golden index (to write new expectations against)
python evals/run.py --baseline XYZ-Training-and-Instruction-Program-version0.docx
```

### LLM provider (repo-root `.env`)

| Provider | `.env` lines | default model |
|----------|-------------|---------------|
| OpenAI (default) | `OPENAI_API_KEY=sk-...` | `gpt-4o-mini` |
| Gemini (temp switch) | `LLM_PROVIDER=gemini`<br>`GEMINI_API_KEY=...` | `gemini-3.5-flash-lite` (cheapest lite model still open to new keys — `gemini-2.x-flash-lite` 404s on new keys); override with `GEMINI_MODEL=...` |

Restart `mvn spring-boot:run` after editing `.env`. To go back to OpenAI, remove
the `LLM_PROVIDER` line (or set it to `openai`).

Results are written to `evals/results/<timestamp>.json` and `results/latest.json`.
Exit code is non-zero if any case FAILs or ERRORs (PARTIAL/XFAIL do not fail the run).

## How a case runs

1. The case's `golden` .docx is copied into the server's `docs/` as `doc`.
2. `prompt` → `POST /api/proposals` (LLM), **or** `batch` → same endpoint as a
   manual batch (engine tier).
3. If a proposal comes back it is auto-approved.
4. The result is graded: structural index (`/index`) for text + structure,
   downloaded .docx (`docx_probe.py`, walks the `dg_*` bookmarks) for formatting.
   Every `expect` sub-check is scored individually (see Scoring below).
5. The golden is restored so the next case starts clean.

## Scoring

Each case's `expect` block can carry several independent sub-checks (a
`blocks` text assertion, a `structure.removed_blocks` entry, a `format`
check, ...). All of them are graded, not just "did it fully match":

- **PASS** (weight 1.0) — every sub-check held.
- **PARTIAL** (weight 0.5) — some but not all sub-checks held. The model did
  *something* right but not everything asked.
- **FAIL** (weight 0.0) — zero sub-checks held, or the model produced a wrong
  generation outright (invalid batch, wrong op, HTTP/validation error) where
  `applied` was expected. Negative cases (`outcome: no_change`) are binary —
  either the model correctly declined or it made an edit it shouldn't have.
- **XFAIL** — the case has a `known_gap` note (a documented product-level
  limitation, not an LLM mistake) and failed as expected. Excluded from the
  KPI and from pass/fail totals; tracked so we notice if it starts passing.
- **ERROR** — infra problem (LLM rate-limit / 5xx, server unreachable). Not a
  model verdict. Retried automatically before being counted; excluded from
  the KPI.

### KPI: modify/insert/delete success rate (target 97%)

Every case is tagged `op_type: modify | insert | delete | format | negative`.
The headline number — **"success rate of the LLM for normal change, add,
delete operations"** — is the weighted PASS/PARTIAL/FAIL average computed
**only** over `op_type` ∈ {modify, insert, delete} cases that actually hit
the LLM (`mode: llm`; engine-tier cases don't count — they never call a
model). `format` and `negative` cases are graded and reported but excluded
from this number, per how the target was defined.

```
KPI = Σ weight(status) / count(cases)   over {modify, insert, delete} ∩ mode=llm
```

**Latest measured result (2026-09-12, 60-case catalog across 7 documents,
`gemini-3.5-flash-lite`): 100% (55/55 weighted), zero XFAIL** — the one
remaining known-gap case (`delete-empty-paragraph`) is now fixed (see "Real
bugs this suite has found and fixed" below) and passes for real. This is a
moderate sample (n=55); treat "100%" as strong evidence at this sample
size, not a permanent guarantee — keep adding cases and re-running
periodically, especially after prompt or model changes.

### Model comparison (Gemini tiers)

No OpenAI access at measurement time, so comparison is within Gemini's own
lineup: `gemini-3.5-flash-lite` (default, cheapest) vs `gemini-3.5-flash`
(pricier, nominally more capable). Ran the same 60-case catalog on both:

| | `gemini-3.5-flash-lite` | `gemini-3.5-flash` |
|---|---|---|
| KPI (modify/insert/delete) | **100%** (54/54) | did not finish (see below) |
| Latency/case | 1–3s | 4–11s, one request exceeded the 240s HTTP timeout entirely |
| Negative case (`neg-delete-table-cell`) | correctly declined, every run | **incorrectly deleted the cell** — a real safety-relevant miss |
| Cost/case | ~$0.0014 | not measured (run didn't complete) |

The pricier model was slower, less reliable on request latency, and got a
guardrail case wrong that the cheap model got right every time. On this
evidence, `gemini-3.5-flash-lite` is not just the cheaper choice, it's the
better one — bigger/pricier isn't a safe default assumption here. Re-run
this comparison once OpenAI access is available, and re-run it against
`gemini-3.5-flash` again with a longer timeout to get a complete picture
(the partial run is suggestive, not conclusive, on its own).

## Cost monitoring

Every LLM proposal response includes `usage` (prompt/completion/total
tokens, from the provider's own response) and `cost_usd` (computed
server-side from `LlmPricing`, a small static USD-per-1M-token rate table —
estimates, not billing-accurate; update it as pricing changes). The runner
sums both across the run and prints/records them; `--budget-usd` aborts the
run early if cumulative cost would exceed it. A full 47-case run currently
costs about **$0.075** and ~736K tokens on `gemini-3.5-flash-lite`.

## Case schema

```jsonc
{
  "id": "text-effective-date",
  "category": "text-modify",
  "op_type": "modify",                   // modify | insert | delete | format | negative — drives the KPI
  "doc": "XYZ-....docx",                 // name the server sees
  "golden": "docs/XYZ-....docx",         // path from repo root, restored each run
  "prompt": "Change the effective date ...",   // LLM tier
  "batch": { ... },                      // engine tier (mutually exclusive with prompt-driven grading)
  "known_gap": "why this is expected to fail (optional -> XFAIL)",
  "expect": {
    "outcome": "applied" | "no_change",  // no_change is the pass state for negatives
    "blocks":   { "dg_p4": { "text_equals": "...", "text_contains": "..." } },
    "text_present": ["phrase that must appear in some block"],
    "unchanged": ["dg_p2"],              // must still exist
    "structure": { "removed_blocks": ["dg_p9"], "inserted_min": 2 },
    "format":   { "dg_p4": { "bold": true, "italic": false, "underline": false,
                             "align": "center", "size_pt": 14 } }
  }
}
```

## Documents covered

- `XYZ-Training-and-Instruction-Program-version0.docx` — text-heavy policy doc.
- `21260  GUANGDELI, Dried Beancurd Roll, 25x300g.docx` — the complicated one:
  ~48 tables, merged cells, Chinese text, floating text boxes, 3 real
  embedded images (one true picture, several floating shape/textbox anchors).
- `my-doc.docx` — a short resume: plain paragraphs, no tables, repeated
  placeholder text (`[add details here]` appears 6+ times) — a good stress
  test for evidence-text disambiguation.
- `docs/samples/cv_v13.docx`, `docs/samples/cv_latest.docx` — two of Zixuan's
  real CVs (copied in read-only from OneDrive, originals never touched —
  checksummed before and after). Dense, professional English, no tables,
  special characters (em-dash, middle dot, tab stops) — genuinely different
  register from the food-safety/policy documents.
- `docs/samples/it_purchase_order.docx`, `docs/samples/lab_report.docx` —
  synthetic, generated via `python-docx` specifically to test generality
  outside the food industry (IT procurement, materials testing) while still
  exercising complex table structure (multiple tables, merged-looking total
  rows, approval-status tables).
- Tried and **rejected**: the pdf2docx-converted `multicol.docx`/`form.docx`
  synthetic bakeoff files (from the earlier PDF→Word pipeline experiment) —
  both 500 on `/index`. Root cause: a malformed numeric OOXML attribute
  (`"8.0"` where an integer is expected) that docx4j's strict JAXB parser
  can't unmarshal. Pre-existing pdf2docx conversion-quality bug, out of
  scope for this eval suite — not real production documents anyway.

## Image-position stability

Verified directly (not just asserted): editing unrelated text/table content
must not move, resize, or corrupt an embedded image elsewhere in the
document. Tested on the real GUANGDELI document (3 media files, 7 floating
anchors + 1 inline drawing — the harder, more realistic case, not a toy
inline picture) across both a simple text-modify edit and a bigger
structural table-row-insert edit: image bytes byte-identical, every anchor's
position offset and extent unchanged, both times. Now a permanent JUnit
regression test: `src/test/java/com/docgen/document/ImagePositionStabilityTest.java`.

## Real bugs this suite has found and fixed

1. **Table-row caption-row hijack** — `TableRowAnchorRepair` redirected a
   table-row insert onto the table's own single-cell caption row whenever
   the request's phrasing happened to text-match the caption (e.g. "the
   additives declaration table" matching a caption literally reading
   "Additives declaration"). Fixed: single-cell rows in an otherwise
   multi-column table are excluded from anchor candidates.
2. **Table-row wrong-table hijack via a bare common word** — same repair
   mechanism redirected an insert to an unrelated table just because one
   word ("additives") appeared as a cell value there. Fixed: single-cell
   phrase matches must be ≥12 chars, contain a space, and be unique — one
   bare word can no longer steal the anchor.
3. **Column-header echo hijack** — a request describing a new row's values
   using column-name phrasing (e.g. "...country of origin China") could
   text-match an unrelated row whose cell literally reads "Country of
   origin" (the header, or a same-named label elsewhere), overriding an
   already-correct anchor and producing a cells-length mismatch. Fixed: (a)
   a redirect target must have the same physical cell count as the mutation
   being inserted, and (b) a cell-text phrase that repeats verbatim as a
   value in more than one row anywhere in the document (a sure sign it's a
   field label, not a unique row identifier) no longer counts as a match.
4. **Multi-edit index-scoping data loss** — `IndexScoper` shrinks the block
   list sent to the LLM based on quoted terms in the request. A multi-edit
   request naturally quotes the *new* value for one edit (which matches no
   block — it doesn't exist yet) alongside the *old* value for another. With
   more than one quoted term, the old code skipped its own safety floor
   entirely and could scope down to just the one block matched by the old
   value, silently dropping the block the other edit needed — the model then
   saw an incomplete index and wrongly declined the whole request as
   impossible. Fixed: the floor (don't shrink below 8 matched blocks) now
   applies regardless of how many terms were quoted.

5. **Empty paragraphs couldn't be deleted** — the delete mutation required a
   non-blank `evidence_text` that's a verbatim substring of the target block;
   an empty block can never satisfy that, so a correct-looking delete was
   always rejected. Fixed (2026-09-12): a delete with blank/absent
   `evidence_text` is now accepted *only* when the target block's own
   indexed text is verifiably empty too (checked against the real index, not
   just the caller's claim) — paragraph deletes only, table row/column
   deletes still require evidence since they span multiple cells that may
   not all be empty. `delete-empty-paragraph` / `eng-delete-empty-paragraph`
   now genuinely PASS instead of XFAIL.

## TODO / next

- Broaden further: font family, image insert, list-style toggle, larger
  multi-step (3+ edit) requests.
- Re-run the model comparison against OpenAI once available, and complete
  the `gemini-3.5-flash` run with a longer timeout.
- Grade "unchanged" blocks against a stored golden text snapshot, not just
  "still exists".

**Explicitly deferred (2026-09-11, per Zixuan):** per-run regression
tracking against `results/latest.json` and CI wiring. Not pursuing until
there's a concrete plan for what it should cover.
