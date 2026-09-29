# Edit in Word — OnlyOffice integration

A second editing surface alongside the AI chat/propose-approve flow: a full
Word-like WYSIWYG editor (real ribbon UI, not a per-block editor) for direct
manual edits, embedded via a self-hosted
[OnlyOffice Document Server](https://www.onlyoffice.com/document-server.aspx).
Both surfaces write to the same `.docx` — same "last save wins" convention
the project has always used.

## Why OnlyOffice (and not Collabora/LibreOffice)

Evaluated both self-hosted, free options. OnlyOffice's JS API + HTTP
callback protocol is simpler to integrate than Collabora's WOPI protocol
(CheckFileInfo/GetFile/PutFile endpoints), and its ribbon looks closer to
real Word out of the box. Both have similar free-tier connection limits
(~20 concurrent), plenty for an internal tool. Revisit if Miguel specifically
wants Collabora.

## Running it locally

```bash
docker compose -f docker/onlyoffice-compose.yml up -d
```

Document Server comes up on `http://localhost:8082` (healthcheck:
`/healthcheck`). The app's defaults (`app.onlyoffice.*` in
`application.yml`) already point at it — no config needed for local dev.

Open a document in the app, switch to **Preview**, click **Edit in Word…**.

## How it works

- `GET /api/onlyoffice/editor-config/{name}` — hands the frontend the
  Document Server's URL plus a signed config (document URL, a save
  callback URL, an editor key derived from the file's mtime+size so an
  unmodified file resumes the same co-editing session, and a fresh key
  after any change).
- `POST /api/onlyoffice/callback/{name}` — OnlyOffice's own save protocol.
  On a force-save or last-editor-closed event, downloads the new file,
  snapshots the pre-edit bytes as a checkpoint (`DocumentWorkspace
  .createCheckpointBeforeManualEdit`, same mechanism as an AI-approved
  edit), overwrites the doc, then re-runs `BookmarkIndexer.ensureBookmarks`
  so any paragraph/cell the user typed from scratch gets a `dg_*` bookmark
  and stays addressable by the AI-mutation system.

Verified end-to-end (`OnlyOfficeCallbackControllerTest`, plus a manual pass
through the real running app): the project's `dg_*` bookmarks survive a
round-trip through OnlyOffice's save engine intact, and the app's own
diff/checkpoint system picks up a manual edit exactly like an approved AI
edit — same blue "modified" highlight in Preview, same Commit/History flow.

## Known caveat

OnlyOffice re-serializes `word/styles.xml` on save. At least one paragraph's
style came back as OnlyOffice's own internal numeric style id instead of the
named style (`Heading1`) docx4j had written. If `FormatApplier` or other
code ever starts relying on named-style matching for a document that has
been through OnlyOffice, that's worth a closer look. Text content and
bookmarks were unaffected.

## Production notes (not yet done — this is a local-dev setup)

- **JWT**: `app.onlyoffice.jwt-secret` / `ONLYOFFICE_JWT_SECRET` must be set
  and must match the Document Server's own `JWT_SECRET`/`JWT_ENABLED=true`.
  Left blank, JWT verification is skipped entirely — fine for a single
  developer on localhost, not for anything shared.
- **Callback reachability**: `app.onlyoffice.callback-base-url` defaults to
  `http://host.docker.internal:8081`, the Docker Desktop hostname for
  reaching the host from a container — a dev convenience, not a real
  network topology. A real deployment should put the app and Document
  Server on a routable network (or the same docker-compose network) and
  point this at a real hostname.
- **The Document Server's own SSRF guard**: by default it refuses to fetch
  document/callback URLs that resolve to a private IP
  (`request-filtering-agent.allowPrivateIPAddress` in
  `/etc/onlyoffice/documentserver/default.json`, `false` by default). Hit
  during local testing since `host.docker.internal` resolves to a private
  address. Fine to allow for local dev; a real deployment shouldn't need
  the override if the two services are on a normal routable network.
