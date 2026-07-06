# docx4j Agent Server — Implementation Stages

**Project folder:** `docx4j-agent-server/` (new; Syncfusion `doc-agent-server/` stays as reference only)  
**Design references:** [`MUTATION_CONTRACT_DESIGN.md`](MUTATION_CONTRACT_DESIGN.md), [`DOCX4J_MIGRATION_PLAN.md`](DOCX4J_MIGRATION_PLAN.md)

---

## How to use this document

Each step has a unique id: **`S{stage}.{step}`** (e.g. `S2.4`).

When requesting implementation, say:

> *Implement through **S2.4** and run all tests listed for Stage 2.*

Rules:

1. **Do not skip steps** within a stage unless the doc says optional.
2. **Stop at the checkpoint** — run manual + automated tests before continuing.
3. **Defer** anything marked *Later* unless explicitly requested.

### Stage map (at a glance)

| Stage | Id range | Theme | Stop after this to have… |
|-------|----------|-------|---------------------------|
| 0 | S0.1–S0.4 | Bootstrap | Maven project loads/saves docx |
| 1 | S1.1–S1.6 | Addressing | Stable `target_id` list for a document |
| 2 | S2.1–S2.7 | Modify engine | ID-based edit with format preservation |
| 3 | S3.1–S3.4 | Safety | Hash guard + validator |
| 4 | S4.1–S4.4 | Insert / delete | Paragraph insert and delete ops |
| 5 | S5.1–S5.5 | Proposal workflow | Propose → approve without LLM |
| 6 | S6.1–S6.4 | LLM integration | AI-generated mutation batches |
| 7 | S7.1–S7.3 | Documents API | Upload, list, download |
| 8 | S8.1–S8.3 | *Later* | PDF, preview, table rows |

---

## Prerequisites (before S0.1)

- [ ] JDK 21 installed
- [ ] Maven 3.9+
- [ ] Branch `feature/docx4j-agent-server` created from current mainline
- [ ] At least **one real `.docx`** from your pdf2docx pipeline copied to a local fixtures folder (not committed if gitignored)
- [ ] Read `MUTATION_CONTRACT_DESIGN.md` §1–3 (ID-based modify, schema, format preservation)

---

## Stage 0 — Bootstrap

**Goal:** Empty Spring Boot app; docx4j loads and saves a file without corrupting it.

### S0.1 — Create project skeleton

**Work:**

- Create `docx4j-agent-server/pom.xml` (Spring Boot 3.4.x, Java 21)
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation`, `docx4j-JAXB-ReferenceImpl` (pin version in pom), JUnit 5
- `com.docgen.Docx4jAgentApplication`
- `application.yml`: `server.port` (e.g. 8081), `app.docs-dir`
- `.gitignore`: `target/`, `docs/*.docx`, `logs/`

**Files:**

```
docx4j-agent-server/
├── pom.xml
├── src/main/java/com/docgen/Docx4jAgentApplication.java
├── src/main/resources/application.yml
└── src/test/java/com/docgen/Docx4jAgentApplicationTests.java
```

**Tests:**

| Id | Command / test | Pass criteria |
|----|----------------|---------------|
| T0.1 | `mvn -f docx4j-agent-server test` | Context loads |
| T0.2 | `mvn -f docx4j-agent-server spring-boot:run` | Starts on configured port |

**Done when:** `T0.1` and `T0.2` pass.

---

### S0.2 — DocumentLoader

**Work:**

- `com.docgen.document.DocumentLoader`
  - `WordprocessingMLPackage load(Path)`
  - `void save(WordprocessingMLPackage, Path)`
  - `void validate(byte[])` — parse or throw
- `com.docgen.config.AppProperties` — `docsDir` path resolution (mirror `doc-agent-server`)

**Tests:**

| Id | Test class | Pass criteria |
|----|------------|---------------|
| T0.3 | `DocumentLoaderTest` | Load fixture docx → `getMainDocumentPart()` non-null |
| T0.4 | `DocumentLoaderTest` | Save to temp path → reload → same plain text from first paragraph |

**Done when:** `T0.3`, `T0.4` pass.

---

### S0.3 — Test fixtures layout

**Work:**

- `src/test/resources/fixtures/README.md` — how fixtures are produced
- Add **minimal** fixture `single-paragraph.docx` (one `w:p`, one `w:r`, text `"Hello"`) — commit if small
- Document path for **local** real pdf2docx sample (gitignored): `fixtures/local/`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T0.5 | `DocumentLoaderTest` | Loads `single-paragraph.docx` from classpath |

**Done when:** `T0.5` passes.

---

### S0.4 — Health endpoint

**Work:**

- `com.docgen.api.HealthController`
- `GET /api/health` → `{ "status": "ok", "engine": "docx4j" }`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T0.6 | Manual or `@SpringBootTest` | `GET /api/health` returns 200 |

---

### Stage 0 checkpoint ✓

```bash
mvn -f docx4j-agent-server test
mvn -f docx4j-agent-server spring-boot:run
curl http://localhost:8081/api/health
```

**You can stop here and say:** *Implemented through S0.4.*

---

## Stage 1 — Addressing (bookmarks + index)

**Goal:** Every editable block has a stable `dg_*` id; server produces `StructuralIndex` / `BlockDescriptor` list.

### S1.1 — TextNormalizer

**Work:**

- `com.docgen.document.TextNormalizer`
  - `normalize(String)` — NFC, collapse whitespace, trim

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T1.1 | `TextNormalizerTest` | `"a  b"` and `"a b"` normalize equal |
| T1.2 | `TextNormalizerTest` | Unicode NFC equivalence |

---

### S1.2 — Model records (index only)

**Work:**

- `com.docgen.model.BlockDescriptor` — `targetId`, `type`, `text`, optional `style`, `tableIndex`, `row`, `col`
- `com.docgen.model.StructuralIndex` — `documentId`, `List<BlockDescriptor> blocks`
- Jackson annotations for JSON; no mutation types yet

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T1.3 | `StructuralIndexTest` | Round-trip JSON serialize/deserialize |

---

### S1.3 — Plain text extraction helper

**Work:**

- `com.docgen.index.PlainTextExtractor`
  - `extractFromParagraph(P)` — concatenate all `w:t` in order
  - `extractFromCell(Tc)` — first `w:p` only (v1)

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T1.4 | `PlainTextExtractorTest` | Multi-run paragraph → correct concatenated string |

---

### S1.4 — BookmarkIndexer

**Work:**

- `com.docgen.index.BookmarkIndexer`
  - `ensureBookmarks(WordprocessingMLPackage)` — idempotent
  - Naming: `dg_p{n}` body paragraphs; `dg_tbl{t}_r{r}_c{c}` cells
  - Walk body in document order (sections → body → `p` / `tbl`)
  - Insert `w:bookmarkStart` / `w:bookmarkEnd` wrapping each editable unit
  - Assign unique `w:id` for bookmark pairs

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T1.5 | `BookmarkIndexerTest` | After index, document contains `dg_p0` |
| T1.6 | `BookmarkIndexerTest` | Second call does not duplicate bookmarks |
| T1.7 | `BookmarkIndexerTest` | Table fixture has `dg_tbl0_r0_c0` etc. |

**Fixture:** add `table-3x3.docx` (small table, simple text).

---

### S1.5 — BookmarkResolver

**Work:**

- `com.docgen.index.BookmarkResolver`
  - `resolve(String targetId)` → `P` (paragraph or cell’s primary paragraph)
  - Clear exception if unknown id

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T1.8 | `BookmarkResolverTest` | `dg_p0` resolves to paragraph with expected text |
| T1.9 | `BookmarkResolverTest` | Unknown id throws |

---

### S1.6 — StructuralIndexBuilder + dev API

**Work:**

- `com.docgen.index.StructuralIndexBuilder` — `build(package, documentId)` → `StructuralIndex`
- `GET /api/documents/{name}/index` — load doc, ensure bookmarks, return JSON (dev endpoint)

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T1.10 | `StructuralIndexBuilderTest` | Block count matches paragraph + cell count |
| T1.11 | `StructuralIndexBuilderTest` | Each `block.text` equals `PlainTextExtractor` output |
| T1.12 | Manual | `GET /index` on fixture returns valid JSON |

---

### Stage 1 checkpoint ✓

```bash
mvn -f docx4j-agent-server test
# Place single-paragraph.docx in docs/ then:
curl http://localhost:8081/api/documents/single-paragraph.docx/index
```

**Verify:**

- [ ] Every block has unique `target_id`
- [ ] `text` fields match what you see in Word
- [ ] Re-open saved doc → same ids after `BookmarkIndexer` + save

**Stop phrase:** *Implemented through S1.6.*

---

## Stage 2 — Modify engine (core)

**Goal:** Apply `modify` mutation to one paragraph or table cell; preserve run formatting; no global search.

### S2.1 — Mutation model records

**Work:**

- `com.docgen.model.MutationBatch` — `schemaVersion`, `explanation`, `mutations`
- `com.docgen.model.ModifyMutation` — `op`, `targetId`, `oldText`, `occurrence` (default 0), `newText`
- Sealed type or `@JsonTypeInfo` stub for future `insert`/`delete`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T2.1 | `MutationBatchTest` | Parse sample JSON from `MUTATION_CONTRACT_DESIGN.md` |

---

### S2.2 — BlockTextIndex

**Work:**

- `com.docgen.index.BlockTextIndex`
  - Build from one `P`: list of `{ run, textStart, textEnd }`
  - `findSpan(oldText, occurrence)` → `[start, end)` or empty

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T2.2 | `BlockTextIndexTest` | Single-run paragraph: span covers full text |
| T2.3 | `BlockTextIndexTest` | Multi-run: `"soy, wheat"` spans runs 1–2 |
| T2.4 | `BlockTextIndexTest` | `occurrence=1` finds second match in same block |

**Fixture:** add `multi-run-paragraph.docx` (e.g. bold `"soy"` + normal `", wheat"`).

---

### S2.3 — RunEditor (single-run)

**Work:**

- `com.docgen.mutation.RunEditor`
  - `replaceSpanSingleRun(R, startInRun, endInRun, newText)` — keep `rPr`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T2.5 | `RunEditorTest` | Replace middle of run; `rPr` unchanged (XML assert or docx4j compare) |
| T2.6 | `RunEditorTest` | Runs before/after span untouched |

---

### S2.4 — RunEditor (cross-run)

**Work:**

- `replaceSpan(P, start, end, newText)` — full paragraph span API
  - Clone `rPr` from first run in span
  - Trim/delete spanned runs; insert new `R`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T2.7 | `RunEditorTest` | Cross-run replace; result text correct |
| T2.8 | `RunEditorTest` | Replacement run has bold if first spanned run was bold |
| T2.9 | `RunEditorTest` | Run outside span unchanged |

---

### S2.5 — ModifyApplier

**Work:**

- `com.docgen.mutation.ModifyApplier`
  1. `BookmarkResolver` → `P`
  2. `BlockTextIndex` + verify `oldText` at `occurrence` (use `TextNormalizer`)
  3. If not found → throw `StaleTargetException`
  4. `RunEditor.replaceSpan`
- `com.docgen.document.DocumentSession` — hold package; `snapshot()` / `restore(byte[])`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T2.10 | `ModifyApplierTest` | Happy path single-run fixture |
| T2.11 | `ModifyApplierTest` | Happy path multi-run fixture |
| T2.12 | `ModifyApplierTest` | Table cell `dg_tbl0_r1_c1` modify |
| T2.13 | `ModifyApplierTest` | Wrong `oldText` → exception, session restored |
| T2.14 | `ModifyApplierTest` | Other blocks’ text unchanged (manual assert) |

---

### S2.6 — Dev apply endpoint (temporary)

**Work:**

- `POST /api/dev/apply/{docName}` — body: `MutationBatch` (modify only)
  - Load → apply → save → return `{ "status": "ok", "target_ids": [...] }`
  - **Mark deprecated** in code; remove in Stage 5

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T2.15 | Manual curl | POST modify JSON → download docx → only target cell/para changed in Word |

---

### S2.7 — Golden test (one scenario)

**Work:**

- `src/test/resources/golden/modify-cell/`
  - `input.docx`
  - `mutation.json`
  - `expected-text.json` — map `target_id` → expected plain text after apply
- `GoldenModifyTest` — apply batch; assert text per id (not binary docx compare yet)

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T2.16 | `GoldenModifyTest` | Expected texts match |

---

### Stage 2 checkpoint ✓

```bash
mvn -f docx4j-agent-server test
curl -X POST http://localhost:8081/api/dev/apply/table-3x3.docx \
  -H "Content-Type: application/json" \
  -d @src/test/resources/golden/modify-cell/mutation.json
```

**Verify in Word:**

- [ ] Only targeted block text changed
- [ ] Bold/font preserved where fixture had mixed runs
- [ ] Wrong `old_text` rejected (T2.13)

**Stop phrase:** *Implemented through S2.7.*

---

## Stage 3 — Safety (validator + hash guard)

**Goal:** Batch apply proves no collateral text change; invalid LLM-shaped JSON rejected before apply.

### S3.1 — MutationValidator

**Work:**

- `com.docgen.mutation.MutationValidator`
  - Input: `MutationBatch`, `StructuralIndex`
  - Rules: closed `target_id` set; `old_text` non-empty; one mutation per `target_id`; `op` supported
  - Return `List<ValidationError>` or throw

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T3.1 | `MutationValidatorTest` | Unknown `target_id` → error |
| T3.2 | `MutationValidatorTest` | Duplicate `target_id` in batch → error |
| T3.3 | `MutationValidatorTest` | Valid batch → no errors |

---

### S3.2 — NodeHashGuard

**Work:**

- `com.docgen.mutation.NodeHashGuard`
  - `begin(index)` — snapshot all `target_id` → SHA-256(normalized text)
  - `verify(targetedIds)` — re-hash; `changed` must equal `targeted`
  - On failure: `DocumentSession.restore()`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T3.4 | `NodeHashGuardTest` | Single modify → only that id in `changed` |
| T3.5 | `NodeHashGuardTest` | Simulated extra change → verify fails |
| T3.6 | `NodeHashGuardTest` | After fail, session matches `begin()` snapshot |

**Note:** Hash is **plain text only**, not formatting (see design doc).

---

### S3.3 — MutationApplier (orchestrator)

**Work:**

- `com.docgen.mutation.MutationApplier`
  - `apply(session, batch, index)` — validate → guard.begin → each modify → guard.verify
  - Wire into dev apply endpoint

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T3.7 | `MutationApplierTest` | End-to-end with guard on golden fixture |
| T3.8 | `MutationApplierTest` | Invalid batch never mutates document |

---

### S3.4 — Batch dev endpoint update

**Work:**

- Dev endpoint runs validator before apply
- Response includes `changed_ids` on success; validation errors on 400

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T3.9 | Manual | Invalid `target_id` returns 400, file on disk unchanged |

---

### Stage 3 checkpoint ✓

```bash
mvn -f docx4j-agent-server test
# Valid + invalid POST to /api/dev/apply/...
```

**Stop phrase:** *Implemented through S3.4.*

---

## Stage 4 — Insert and delete (paragraphs)

**Goal:** Support full v1 mutation ops for body paragraphs (not table rows yet).

### S4.1 — Model: InsertMutation, DeleteMutation

**Work:**

- `InsertMutation` — `anchorId`, `position`, `nodeType`, `text`, optional `style`
- `DeleteMutation` — `targetId`
- Extend `MutationBatch` polymorphic list

**Tests:** T4.1 — JSON round-trip for insert/delete samples.

---

### S4.2 — InsertApplier

**Work:**

- `com.docgen.mutation.InsertApplier`
  - Resolve anchor `P` → parent body content list
  - Insert new `P` before/after; clone `pPr` from anchor; new bookmark `dg_p{next}`
  - Re-run bookmark id assignment or allocate next free id

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T4.2 | `InsertApplierTest` | Insert after `dg_p1` → new paragraph appears |
| T4.3 | `InsertApplierTest` | Existing ids unchanged except new block |

---

### S4.3 — DeleteApplier

**Work:**

- `com.docgen.mutation.DeleteApplier` — remove `P` by bookmark; reject deleting table cells in v1

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T4.4 | `DeleteApplierTest` | Delete `dg_p2` → gone from index |
| T4.5 | `DeleteApplierTest` | Cannot delete `dg_tbl0_r0_c0` (validation error) |

---

### S4.4 — Wire into MutationApplier + validator

**Work:**

- Validator rules for insert/delete
- Hash guard: inserts add new id to `targeted`; deletes remove id from index hashes before compare

**Tests:** T4.6 — Golden insert + delete scenarios.

---

### Stage 4 checkpoint ✓

**Stop phrase:** *Implemented through S4.4.*

---

## Stage 5 — Proposal workflow (no LLM)

**Goal:** Propose → review → approve/reject; no auto-save on propose.

### S5.1 — ProposalStore

**Work:**

- `com.docgen.proposal.ProposalStore`
  - `docs/.proposals/{id}/proposal.json`, `before.docx`, `index.json`
  - Status: `PENDING`, `APPROVED`, `REJECTED`

**Tests:** T5.1 — save/load round-trip.

---

### S5.2 — ProposalService (manual batch)

**Work:**

- `propose(docName, MutationBatch)` — snapshot before, store pending
- `approve(proposalId)` — load, apply, save working doc
- `reject(proposalId)`

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T5.2 | `ProposalServiceTest` | Approve applies to `docs/{name}.docx` |
| T5.3 | `ProposalServiceTest` | Reject leaves working doc unchanged |

---

### S5.3 — REST: ProposeController, ApproveController

**Work:**

- `POST /api/proposals` — body: `{ "doc_name", "batch" }` (manual JSON for now)
- `GET /api/proposals/{id}`
- `POST /api/proposals/{id}/approve`
- `POST /api/proposals/{id}/reject`

**Tests:** T5.4 — Manual curl full workflow.

---

### S5.4 — Remove dev apply endpoint

**Work:**

- Delete `POST /api/dev/apply` (or gate behind `app.dev-mode=false` default)

---

### S5.5 — Simple diff for review (text only)

**Work:**

- `ProposalService` returns `before_text` / `after_text` per targeted id (predict apply in memory without save, or compute from batch)

**Tests:** T5.5 — Response shows correct before/after strings.

---

### Stage 5 checkpoint ✓

```bash
# POST proposal with mutation.json → GET proposal → POST approve → verify docx
```

**Stop phrase:** *Implemented through S5.5.*

---

## Stage 6 — LLM integration

**Goal:** User prompt + index → `MutationBatch` via structured output.

### S6.1 — MutationJsonSchema

**Work:**

- `com.docgen.llm.MutationJsonSchema` — OpenAI `response_format` / strict JSON schema
- Align with `MUTATION_CONTRACT_DESIGN.md` §2.6

**Tests:** T6.1 — Schema validates golden JSON files.

---

### S6.2 — ComplianceClient

**Work:**

- `com.docgen.llm.ComplianceClient`
  - Input: prompt + `StructuralIndex`
  - Output: `MutationBatch`
  - Config: `OPENAI_API_KEY`, model from `application.yml`
  - Retry ≤ 2 on validation errors (feed errors back to model)

**Tests:**

| Id | Test | Pass criteria |
|----|------|---------------|
| T6.2 | Integration test (optional, `@EnabledIfEnvironmentVariable`) | Real API returns parseable batch |
| T6.3 | Unit test with mocked HTTP | Validator errors trigger retry |

---

### S6.3 — ProposalService + LLM

**Work:**

- `POST /api/proposals` body: `{ "doc_name", "message", "model?" }` — builds index, calls LLM, validates, stores

**Tests:** T6.4 — End-to-end with simple prompt on small fixture.

---

### S6.4 — Agent prompts

**Work:**

- `com.docgen.llm.CompliancePrompts` — rules from design doc §3.7
- System prompt: only use provided ids; copy `old_text` exactly; smallest unique substring

**Tests:** Manual — compliance edit on real pdf2docx sample.

---

### Stage 6 checkpoint ✓

**Stop phrase:** *Implemented through S6.4.*

---

## Stage 7 — Document library API

**Goal:** Upload, list, download — minimal parity with `doc-agent-server` document endpoints.

### S7.1 — DocumentController: list + download

**Work:**

- `GET /api/documents`
- `GET /api/documents/{name}` — bytes
- On upload path: run `BookmarkIndexer` before save

---

### S7.2 — Upload

**Work:**

- `POST /api/documents/upload` — multipart; `DocumentLoader.validate`
- Filename sanitization

**Tests:** T7.1 — Upload real pdf2docx doc → index returns blocks.

---

### S7.3 — Metadata (optional minimal)

**Work:**

- `docs/.meta/{name}.json` — uploaded_at, size (copy pattern from `doc-agent-server` if useful)

---

### Stage 7 checkpoint ✓

**Stop phrase:** *Implemented through S7.3* — **MVP complete** for mutation workflow.

---

## Stage 8 — Later (out of MVP)

Implement only when explicitly requested.

| Step | Id | Feature | Notes |
|------|-----|---------|-------|
| S8.1 | Table row insert/delete | Merged-cell rules required |
| S8.2 | LibreOffice PDF | `GET /api/documents/{name}/pdf` |
| S8.3 | HTML preview | Mammoth or LO; not required for mutation correctness |
| S8.4 | React UI | Wire to new port 8081 |
| S8.5 | XML/hash strict mode | Detect formatting-only collateral |

---

## Test commands reference

```bash
# All unit tests
mvn -f docx4j-agent-server test

# Single test class
mvn -f docx4j-agent-server test -Dtest=ModifyApplierTest

# Run server
mvn -f docx4j-agent-server spring-boot:run

# Health
curl http://localhost:8081/api/health

# Index
curl http://localhost:8081/api/documents/{name}/index
```

---

## Fixture checklist

| Fixture | Stage needed | Description |
|---------|--------------|-------------|
| `single-paragraph.docx` | S0 | One para, one run |
| `table-3x3.docx` | S1 | Small table |
| `multi-run-paragraph.docx` | S2 | Bold + normal runs |
| `golden/modify-cell/*` | S2 | Input + mutation + expected |
| Local pdf2docx sample | S1+ | Realistic; keep gitignored |

---

## Suggested implementation requests (copy-paste)

| Goal | Request |
|------|---------|
| Minimal spike | *Implement through **S0.4** and run Stage 0 checkpoint.* |
| Index only | *Implement through **S1.6** and run Stage 1 checkpoint.* |
| Core value | *Implement through **S2.7** and run Stage 2 checkpoint.* |
| Safe batch | *Implement through **S3.4** and run Stage 3 checkpoint.* |
| Full ops (no AI) | *Implement through **S4.4** and run Stage 4 checkpoint.* |
| Review workflow | *Implement through **S5.5** and run Stage 5 checkpoint.* |
| MVP with AI | *Implement through **S6.4** or **S7.3** and run corresponding checkpoint.* |

---

## Related documents

| Doc | Contents |
|-----|----------|
| [`MUTATION_CONTRACT_DESIGN.md`](MUTATION_CONTRACT_DESIGN.md) | JSON schema, class list, format preservation |
| [`DOCX4J_MIGRATION_PLAN.md`](DOCX4J_MIGRATION_PLAN.md) | Syncfusion comparison, PDF strategy, risks |
| `doc-agent-server/` | Reference implementation (integer indices, Syncfusion) |
