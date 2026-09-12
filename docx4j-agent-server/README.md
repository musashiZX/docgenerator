# docx4j Agent Server

An AI-powered Word document editor. Describe an edit in plain English; the AI
proposes the exact change as a reviewable diff (via docx4j, targeting stable
per-block bookmark IDs); you approve it before anything is saved. Every
approved edit is versioned like git (commits, auto-checkpoints, restore,
diff), so nothing is ever a one-way door.

- **Using the app?** See **[USER_GUIDE.md](USER_GUIDE.md)** — functions,
  how-to, a prompt cookbook with real tested examples, and the version-control
  ("word-git") walkthrough.
- **Checking accuracy/reliability?** See **[evals/README.md](evals/README.md)**
  — the automated eval suite, current measured success rate, cost, and every
  real bug it has found and fixed.

## Run

```bash
cp ../.env.example ../.env   # then fill in OPENAI_API_KEY or GEMINI_API_KEY (see USER_GUIDE.md)
mvn spring-boot:run
```

Open <http://localhost:8081>. Upload a `.docx` (or drop one into `docs/`),
then use the AI chat panel to describe an edit.

## Test

```bash
mvn test                                              # backend unit/integration tests
python evals/run.py --catalog evals/catalog-engine.json   # deterministic eval tier, free, ~5s
python evals/run.py                                        # LLM eval tier, needs a provider key
```

## What's built

- **Editing**: modify, insert, delete (paragraphs and table rows/columns),
  formatting (bold/italic/underline/size/align), bulk find-and-replace.
- **AI chat**: a conversational session that refines one proposal across
  multiple messages, plus a concurrent batch endpoint (`POST
  /api/proposals/batch`) for running several independent edit requests in
  parallel instead of one after another.
- **Version control**: commits (named milestones), automatic checkpoints
  (one per approved edit), restore, and a block-level diff between any two
  snapshots.
- **Performance**: the structural index and rendered preview are cached by
  file version — repeat views are single-digit milliseconds; only an actual
  edit pays the real recompute cost.
- **Reliability**: a 60-case automated eval suite across 7 real and
  synthetic documents (see `evals/`), plus ~180 backend unit/integration
  tests.

## What's not built yet

Image insert/editing, font-family changes, bulleted/numbered list-style
toggles. See `USER_GUIDE.md` §6 for the current, maintained list.

## Architecture notes

- `com.docgen.mutation` — the deterministic engine: validates and applies a
  mutation batch against a `WordprocessingMLPackage`, guarded by a
  text-content hash check so an edit can never silently touch an unrelated
  block.
- `com.docgen.llm` — turns a natural-language request into a mutation batch
  via an OpenAI-compatible chat/completions endpoint (OpenAI or Gemini).
- `com.docgen.proposal` / `com.docgen.conversation` — the propose → review →
  approve workflow and the chat session layer on top of it.
- `com.docgen.recovery` — commits, checkpoints, restore, diff.
- `com.docgen.document` — load/save, structural indexing, HTML preview
  rendering, and the file-version cache.
