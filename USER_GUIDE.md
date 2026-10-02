# AI Word Editor — User Guide

A Word-document editor where you describe an edit in plain English and an AI
proposes the exact change as a reviewable diff — you approve it, or keep
refining it in chat, before anything is saved. Every edit is versioned like
git, so nothing is ever truly lost.

- App: `http://localhost:8081` (after `mvn spring-boot:run` from the repo root)
- Status: internal tool, standalone (not yet integrated into the main DoQcheck app)

---

## 1. What it does

| Function | What it means |
|---|---|
| **Upload / pick a document** | Drop a `.docx` on the left panel, or click an existing one. |
| **Blocks view** | Every paragraph and table cell gets a stable ID (`dg_p4`, `dg_tbl0_r1_c2`) — the address the AI and you both use to refer to a specific piece of content. |
| **Preview view** | Renders the document as it will look, with the same rich-text editing you'd expect from a normal editor (click a paragraph/cell → bold/italic/underline/size/align toolbar). |
| **AI chat** | Type a request in plain English; the AI returns a proposed set of changes (a *proposal*) shown as a diff. You **Approve & save** it, or send another message to refine it — refinements adjust the *same* proposal, they don't start over. |
| **Manual batch** | Advanced/scripted path: write the exact JSON mutation batch yourself and apply it directly, bypassing the AI. Used by the eval suite and functional tests. |
| **Functional tests panel** | Runs a canned list of prompts against a golden copy of the document, one at a time, and lets you mark each Pass/Fail. Restores the document between tests. |
| **Version control ("word-git")** | Commits, checkpoints, restore, and diff — see §4. |

### What it can edit today

- **Modify** existing text (a word, a number, a sentence, a whole paragraph).
- **Insert** a new paragraph, or a new table row/column.
- **Delete** a paragraph, or a table row/column.
- **Format** — bold / italic / underline / font size / paragraph alignment (left/center/right/justify) — on existing text.
- **Bulk find-and-replace** across many identical occurrences in one request.

### What it can't do yet

- Insert or edit images (see §6 — but editing *other* content around an existing image is safe, see below).
- Change font family, or toggle bulleted/numbered list style.

---

## 2. The core idea: propose, then approve

Nothing is ever saved to disk just because the AI suggested it. Every AI
edit — chat or manual batch — goes through:

```
1. PROPOSE   →  AI reads the document, returns a batch of changes
2. REVIEW    →  You see a diff: what block(s) change, before/after text
3. APPROVE   →  Only now is the document actually rewritten on disk
   (or REJECT / keep chatting to adjust the proposal instead)
```

This means: you can ask for something, not like the result, and just not
approve it — the document is untouched. If you *do* approve and regret it,
that's what checkpoints and commits are for (§4).

---

## 3. How to use it

1. **Pick or upload a document** — left panel.
2. Open the **Blocks** tab to see its structure (every paragraph/cell + its `target_id`), or **Preview** to see it rendered.
3. In the **AI chat** panel (right side), type your request in plain English — see the prompt cookbook below for what works best — and hit **Send**.
4. The proposal appears above the chat box: each changed block, old → new. Read it.
5. **Approve & save** if it's right. If it's close but not quite, just send another message ("also change X", "no, keep the old wording for Y") — it refines the *same* proposal, it doesn't discard it.
6. **Reject** to throw the proposal away and start over, or **New session** to reset the AI's memory of the conversation entirely.
7. In **Preview**, you can also click any paragraph or table cell directly to open a rich-text editor (its own Bold/Italic/Underline/Size/Align toolbar) for hands-on edits that don't need the AI at all.

Changed blocks are marked like a diff in Preview: **green = new**, **blue =
modified**, relative to your last commit.

---

## 4. Version control ("word-git")

Two layers, one purpose — you can always get back to an earlier state:

| | **Checkpoints** | **Commits** |
|---|---|---|
| Created | **Automatically**, right before every "Approve & save" | **Manually**, when you click Commit and type a message (plus one automatic "Initial import" the first time) |
| Named? | No — just "Before approve `<id>`" | Yes — your message (e.g. "Baseline", "v1.1", "Before adding additives") |
| Purpose | A fine-grained safety net around each individual AI edit | A deliberate milestone you'll want to come back to or hand off |
| Where | **History** tab / drawer | **History** tab / drawer, **Commit…** button above Preview |

### How to find an old version back

1. Click **History** (top nav, or the History button above the Preview pane).
2. You'll see two lists: **Commits** (your named milestones) and **Checkpoints** (auto-saved, one per approved AI edit).
3. Each entry has a **Restore** button. Click it.
4. Restoring **rejects any pending AI proposal** first, then overwrites the live document with that snapshot — reverting to exactly how the document looked at that moment, which discards *everything* after it (not just "the last edit"). Verified directly: approve edit 1, approve edit 2, restore the checkpoint from before edit 1 → both edits are gone, not just edit 2.
5. **Restoring a commit also moves HEAD to it.** Restoring a checkpoint does **not** move HEAD — it only overwrites the live document. In practice this rarely matters, but it means "restore commit" is the closer git analogy (`git reset --hard <commit>`), while "restore checkpoint" is closer to a one-off file overwrite.
6. Restoring does **not** create a new commit by itself — if you want to lock in "the restored state" as a named milestone, commit again afterward. (Verified: commit count stayed the same across two restores.)
7. Commits (not checkpoints) also have a **download** link, so you can save a milestone `.docx` outside the tool entirely.

### Comparing two versions

`GET /api/documents/{name}/diff?fromType=commit&from={id}&toType=live` (or
`checkpoint`/`commit`/`live` on either side) returns a block-level diff —
what was added, removed, or modified — between any two snapshots, or a
snapshot and the current live document. This is what powers the diff
markup in Preview; there's no dedicated UI button to diff two *arbitrary*
old snapshots against each other yet (only "vs. last commit" and "vs. HEAD"
are wired into the UI).

**Practical habit:** commit before starting a risky batch of AI edits
("Baseline before additives update"), then lean on checkpoints for the
per-edit undo. If a whole session goes wrong, restore the commit; if just
the last edit was wrong, restore the checkpoint before it.

---

## 5. Prompt cookbook

The AI works from the document's block structure, not your memory of where
things are — so the most reliable prompts **name what you're changing** the
way a person reading the document would (a heading, a distinctive phrase,
"the X table"), not a `target_id`.

### Pattern

> **[Action] [what/where] [from → to, or the new content].**

### Modify (change existing text)

| Say this... | ...not this |
|---|---|
| "Change the net contents from 300G to 250G." | "Change dg_tbl0_r2_c1 to 250G." |
| "In the training modules table, change module code TRN-01 to TRN-01A." | "Update the code." |
| "Update the company name from 'XYZ Frozen Foods B.V.' to 'XYZ Frozen Foods International B.V.'" | "Fix the company name." |

**When the same text appears more than once**, say which one:
> "Change the frequency of the HACCP refresher (TRN-04) to every 9 months —
> there are several rows saying 'Every 12 months', only change that one."

**You don't need to find every occurrence yourself.** "Bump the version
from 1.0 to 1.1" on a document that states its version in two places (a
cover-page line *and* a revision-history table) correctly updated **both** —
verified directly. Ask for the fact you want changed, not necessarily every
place it appears.

### Insert (add a new paragraph)

> "Insert a new paragraph immediately after **[distinctive existing text]**
> with the text: **[new content]**."

Example: *"Insert a new paragraph immediately after 'This program applies
to:' with the text 'Contractors and visitors with plant access must
complete induction before entry.'"*

### Insert a table row

> "In the **[table name]** table, add a new row: **[value A]**, **[value B]**,
> **[value C]**."

Example: *"In the additives declaration table, add 2 more rows: 1. E224
Netherlands Drink additives. 2. E225 Spain Snack additives."* — asking for
several rows in one message works; they stack in the order you list them.

To control where it lands: *"...insert a new row **before** the E223 row..."*
(default is "after" the row you named).

### Delete

> "Delete the paragraph that **[starts with / mentions] [enough of the text
> to be unambiguous]**."

Example: *"Delete the paragraph that starts with 'Specify the raw material
for vegetable oils'."* A short, vague reference also works if it's the only
thing on that topic — *"Remove the paragraph about GMO labelling
requirements"* — but a verbatim opening phrase is the safest bet.

To delete a genuinely **empty** paragraph (a blank line), just say so —
*"Delete the empty paragraph between the Company line and the Purpose and
Scope heading"* works; no text to quote is needed there.

### Format (bold / italic / underline / size / align)

> "Make **[the text]** **[bold / italic / underlined / size N / centered /
> right-aligned]**."

Example: *"Make the 'Effective Date: January 15, 2025' line bold."*
Example: *"Center the 'Purpose and Scope' heading."*

### Multiple edits in one message

Works, and is efficient — but name each edit as clearly as you would
separately:

> "Change the version from 1.0 to 2.0 and change the effective date to June
> 1, 2025."

### What tends to go wrong

- **Vague location + a table with generic column names** ("add a row with
  the country of origin China") can occasionally confuse which table you
  mean if two tables share a column name — naming the table helps ("in the
  **component list** table...").
- **A request that only says the *new* value**, never the old ("change the
  name to 'Zixuan Chen, MSc'") is fine on its own, but if you combine it
  with a *second* unrelated edit in the same message, name what's being
  changed for both, not just one ("...and change the country line from
  'China.' to 'China, Netherlands.'" — quoting the *old* value for at least
  one edit helps the AI locate the right paragraph).

---

## 6. Known limitations

- **No image insert/replace.** You can't ask the AI to add a picture or
  swap one out.
- **Images already in the document are safe.** Editing text or tables
  elsewhere in the document does **not** move, resize, or corrupt existing
  images — verified directly (byte-identical image data, unchanged anchor
  position) across both a simple text edit and a bigger structural edit
  (table row insert), including on a real document with floating
  logo/shape images, not just a simple inline picture.
- **No font-family change or bullet/numbered-list style toggle** — not
  implemented.
- Very large documents get their block list *scoped* (narrowed) before
  being shown to the AI, for cost/speed — in rare cases this can hide a
  block the AI needed; if an edit is wrongly declined as "not found," try
  naming the target text more explicitly.

---

## 7. Cost and speed

**Cost:** roughly **$0.001–0.003 per AI request** on the default model
(`gemini-3.5-flash-lite`) — a few thousand tokens of the document's
structure plus your prompt. Multi-part requests (several table rows at
once) run a bit higher, still under a cent. See
`evals/README.md` for how this is measured.

**Speed:**

- **Viewing** (Blocks index, Preview render) is cached per document, keyed
  to the file's own modified-time — the first view after an edit does the
  real work (index: ~0.3s; preview render on a complex document: several
  seconds, it's a full HTML re-render), every view after that until the next
  edit is a cache hit: **~6–8ms**, measured directly. Approving an edit,
  restoring a checkpoint/commit, or uploading a new file naturally
  invalidates the cache (the file's mtime changes) — nothing extra needed on
  your end.
- **Generation** (the LLM call itself) has a floor that caching can't touch —
  network + model inference, **1–3 seconds per request**, same as any AI
  chat tool. What's controllable is *not paying that cost N times* for N
  requests:
  - One message describing **several edits at once** ("change the version
    to 2.0 and the date to June 1, 2025") already goes through as ONE LLM
    call — same latency as a single-field edit.
  - For edits that don't fit naturally into one sentence, `POST
    /api/proposals/batch` (body: `{"doc_name", "messages": ["...", "...", ...]}`)
    runs each message as its own LLM call **concurrently** and merges
    everything into one proposal. Measured directly: 3 requests sent
    **sequentially** took 4.7s; the same 3 requests through `/batch` took
    **1.4s** — close to a single request's latency, not 3×. There's no UI
    button for this yet — it's an API-level capability today.

---

## 8. Where things live (for reference)

| Thing | API |
|---|---|
| List / upload documents | `GET/POST /api/documents` |
| Block index | `GET /api/documents/{name}/index` |
| Rendered preview | `GET /api/documents/{name}/preview` |
| AI chat (session, refines one proposal) | `GET/POST /api/documents/{name}/session/*` |
| One-shot propose (manual batch or message) | `POST /api/proposals` |
| Batch propose (N messages, run concurrently, merged into 1 proposal) | `POST /api/proposals/batch` |
| Approve / reject a proposal | `POST /api/proposals/{id}/approve` \| `/reject` |
| Commits | `GET/POST /api/documents/{name}/commits` |
| Checkpoints | `GET /api/documents/{name}/checkpoints` |
| Restore | `POST /api/documents/{name}/restore/commit/{id}` \| `/restore/checkpoint/{id}` |
| Diff two snapshots | `GET /api/documents/{name}/diff?fromType=...&toType=...` |

---

## 9. How this guide was tested

Every prompt in §5's cookbook is a real prompt that was actually sent
through the live system and verified to produce the documented result —
most as part of the 60-case automated eval suite (`evals/README.md`), which
re-runs them on every check; the ones that don't map to an eval case
(multi-fact updates, "before" positioning) were run live against a real
document while writing this guide. The version-control walkthrough in §4
(commit → approve → checkpoint → approve again → restore checkpoint →
verify both edits undone → restore commit → verify HEAD moves → confirm no
extra commit was created) was run live end-to-end against a real document,
since the eval suite doesn't exercise commits/checkpoints/restore/diff at
all yet — that gap is what surfaced the two nuances now written into §4
(restoring reverts *everything* after that point, not just one edit;
restoring a commit moves HEAD but restoring a checkpoint doesn't).

**Weaknesses this pass surfaced** (beyond what's already in §6):

- The version-control API is well-tested at the HTTP level now, but has **no
  eval coverage** — a regression there wouldn't be caught by the automated
  suite. Worth a small dedicated eval/test tier if this feature gets used
  heavily.
- The diff endpoint (`/diff`) has no dedicated UI to compare two *arbitrary*
  old snapshots against each other — only "vs. last commit" and "vs. live"
  are wired into Preview. Documented as a known gap in §4 rather than
  something broken, but worth flagging if you need to compare two old
  checkpoints directly.
