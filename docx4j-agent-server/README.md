# docx4j Agent Server

ID-based Word document mutation service (docx4j). See [`../docs/DOCX4J_IMPLEMENTATION_STAGES.md`](../docs/DOCX4J_IMPLEMENTATION_STAGES.md).

## Run

```bash
mvn spring-boot:run
```

Then open the **test console UI**: <http://localhost:8081/>

- Upload a `.docx` (or drop one into `docx4j-agent-server/docs/`)
- Inspect the block index (`target_id`, type, style, run/char counts, text)
- Per block row: **Edit** stages a modify; **+&#8593;/+&#8595;** stage a paragraph insert
  before/after; **Del** stages a paragraph delete (paragraph blocks only)
- Changed blocks flash green; validation errors and rollbacks appear in the activity log
- Download the result to verify formatting in Word

Raw API endpoints:

- `GET  /api/health`
- `GET  /api/documents` — list
- `POST /api/documents/upload` — multipart `.docx`
- `GET  /api/documents/{name}/index` — structural block index
- `GET  /api/documents/{name}/download`

Each block in the index JSON shows its **identity** (`target_id`, `type`, table coords) and **size** (`char_count`, `run_count`, `ordinal`).

## Apply mutations (dev endpoint, temporary)

`POST /api/dev/apply/{name}` with a mutation batch body. Direct apply, bypassing the
future propose/approve workflow — will be removed in Stage 5.

```bash
curl -X POST http://localhost:8081/api/dev/apply/your-file.docx \
  -H "Content-Type: application/json" \
  -d '{
        "schema_version": 1,
        "explanation": "why",
        "mutations": [{
          "op": "modify",
          "target_id": "dg_tbl0_r1_c1",
          "old_text": "exact current text or substring",
          "occurrence": 0,
          "new_text": "replacement"
        }]
      }'
```

Responses:

- `200` — `{ "status": "ok", "applied_count": n, "changed_ids": [...] }`; file saved.
- `400` — validation failed (unknown `target_id`, duplicate target, empty/missing `old_text`,
  `old_text` not present in the block). File untouched.
- `409` — `old_text` matched the index but not the live document (stale). Batch rolled back.
- `500` — hash-guard invariant violation (collateral change detected). Batch rolled back.

## Test

```bash
mvn test
```

## Status

**Stage 4 complete** (through S4.4): bookmarks + structural index (Stage 1); run-preserving
`modify` engine (Stage 2); validator + hash-guard safety layer (Stage 3); `insert` and
`delete` for body paragraphs — `InsertApplier` clones the anchor's `pPr` and allocates the
next free `dg_p*` bookmark, `DeleteApplier` removes by bookmark, table cells are protected
(Stage 4). Plus, pulled forward from Stage 7: document list/upload/download endpoints and
the test console UI at `/`. Next: Stage 5 propose/approve workflow.
