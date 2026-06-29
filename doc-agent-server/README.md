# doc-agent-server

Java backend for the Word document AI agent, using **Syncfusion Essential DocIO**.

Documents are stored in the local `docs/` folder inside this project.

## Prerequisites

- JDK 21+ (you have JDK 25 — compatible)
- Maven 3.9+
- Syncfusion license key (optional for dev; without it DocIO runs in trial mode)

## License & secrets

Put secrets in **repo-root `.env`** (one file for Java + frontend):

```powershell
# From repo root
copy .env.example .env
# Edit .env — set SYNCFUSION_LICENSE_KEY=...
```

| Variable | Used by |
|----------|---------|
| `SYNCFUSION_LICENSE_KEY` | Java DocIO (`SyncfusionLicenseProvider.registerLicense`) |
| `VITE_SYNCFUSION_LICENSE` | React Document Editor (optional; launcher copies from above) |

**Same key for Java and React?** Yes, if your Syncfusion license includes **Document SDK** (Java DocIO) and **DOCX Editor SDK** (React), or you use **Enterprise Edition**. Generate at [License & Downloads](https://www.syncfusion.com/account/downloads). Without a key, DocIO runs in trial mode (watermark in output).

Spring Boot auto-loads `../.env` via `spring.config.import` in `application.yml`.

## Run (recommended — backend + frontend)

From **repo root**:

```powershell
.\start-java.ps1
```

Git Bash:

```bash
bash start-java.sh
```

This loads `.env`, starts Java on port 8080 (or next free port), writes `frontend/.env.local`, and starts Vite.

## Run (backend only)

```powershell
cd doc-agent-server
# Ensure ../.env exists or export SYNCFUSION_LICENSE_KEY
mvn spring-boot:run
```

Default port: **8080** (override with `SERVER_PORT=8082`).

## API (Phase 0)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Health check |
| GET | `/api/documents` | List `.docx` in `docs/` |
| POST | `/api/documents` | Create empty doc `{"name":"test.docx"}` |
| POST | `/api/documents/upload` | Upload `.docx` (multipart `file`) |
| GET | `/api/documents/{name}/download` | Download `.docx` |
| GET | `/api/documents/{name}/preview` | HTML preview (DocIO export) |
| POST | `/api/documents/{name}/replace` | `{"find":"…","replace":"…"}` — format-preserving replace |

## Quick test

```powershell
# Create a document
curl -X POST http://localhost:8080/api/documents -H "Content-Type: application/json" -d "{\"name\":\"hello.docx\"}"

# Replace text
curl -X POST http://localhost:8080/api/documents/hello.docx/replace -H "Content-Type: application/json" -d "{\"find\":\"New document\",\"replace\":\"Hello from DocIO\"}"

# Preview in browser
start http://localhost:8080/api/documents/hello.docx/preview
```

## Project layout

```
doc-agent-server/
├── docs/                 ← Word files live here
├── pom.xml
└── src/main/java/com/docgen/
    ├── DocAgentApplication.java
    ├── api/              REST controllers
    ├── config/           License, CORS, properties
    └── docio/            DocIO document service
```

## Next steps

See [`../docs/JAVA_DOCIO_PLAN.md`](../docs/JAVA_DOCIO_PLAN.md) for Phase 1 (AI edit API, block extraction).
