# docx4j Agent Server

ID-based Word document mutation service (docx4j). See [`../docs/DOCX4J_IMPLEMENTATION_STAGES.md`](../docs/DOCX4J_IMPLEMENTATION_STAGES.md).

## Run

```bash
mvn spring-boot:run
# http://localhost:8081/api/health
# http://localhost:8081/api/documents/{name}/index
```

Copy a `.docx` into `docx4j-agent-server/docs/` then:

```powershell
Invoke-RestMethod http://localhost:8081/api/documents/your-file.docx/index | ConvertTo-Json -Depth 6
```

Each block in the JSON shows its **identity** (`target_id`, `type`, table coords) and **size** (`char_count`, `run_count`, `ordinal`).

## Test

```bash
mvn test
```

## Status

**Stage 1 complete** (S1.1–S1.6): bookmarks, structural index, `GET /api/documents/{name}/index`.
