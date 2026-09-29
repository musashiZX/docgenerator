# Test fixtures

## Committed fixtures (`src/test/resources/fixtures/`)

| File | Description |
|------|-------------|
| `single-paragraph.docx` | One `w:p`, one `w:r`, text `"Hello"` |

Regenerate with:

```bash
mvn -f docx4j-agent-server test -Dtest=FixtureGeneratorTest
```

## Local fixtures (gitignored)

Place real pdf2docx-origin samples under:

```
src/test/resources/fixtures/local/
```

Do not commit large or customer-specific documents.
