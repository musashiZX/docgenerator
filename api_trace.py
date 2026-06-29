"""Persist exact frontend↔backend JSON for every API call.

Each request creates a folder under ``logs/traces/`` containing:
  meta.json       — method, path, status, duration, trace_id
  request.json    — exact inbound JSON (large fields externalised)
  response.json   — exact outbound JSON (large fields externalised)
  *.extra.json    — optional extras (e.g. llm_raw, client execution)

Set ``API_TRACE=0`` in the environment to disable.
"""

from __future__ import annotations

import json
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

TRACE_ROOT = Path(__file__).parent / "logs" / "traces"
LARGE_FIELD_THRESHOLD = 2_000
LARGE_FIELDS = frozenset({"sfdt", "preview_html"})

_active: dict[str, Path] = {}


def tracing_enabled() -> bool:
    return os.getenv("API_TRACE", "1").strip().lower() not in {"0", "false", "no", "off"}


def _utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%f")[:-3] + "Z"


def _slug(path: str) -> str:
    slug = re.sub(r"[^\w.-]+", "_", path.strip("/").replace("/", "_"))
    return slug[:80] or "root"


def _write_json(path: Path, data: Any) -> None:
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8",
    )


def _externalise_large_fields(obj: Any, trace_dir: Path, prefix: str = "") -> Any:
    """Return a JSON-safe copy; spill oversized known fields to sidecar files."""
    if isinstance(obj, dict):
        out: dict[str, Any] = {}
        for key, value in obj.items():
            field_prefix = f"{prefix}{key}_" if prefix else f"{key}_"
            if key in LARGE_FIELDS and isinstance(value, str) and len(value) > LARGE_FIELD_THRESHOLD:
                sidecar = trace_dir / f"{field_prefix}full.txt"
                sidecar.write_text(value, encoding="utf-8")
                out[key] = {
                    "_externalised": sidecar.name,
                    "bytes": len(value.encode("utf-8")),
                    "preview": value[:500],
                }
            else:
                out[key] = _externalise_large_fields(value, trace_dir, field_prefix)
        return out
    if isinstance(obj, list):
        return [_externalise_large_fields(item, trace_dir, prefix) for item in obj]
    return obj


def _parse_body(body: bytes, content_type: str | None) -> Any:
    if not body:
        return None
    ct = (content_type or "").lower()
    if "json" in ct:
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {"_raw_bytes": len(body), "_preview": body[:200].decode("utf-8", errors="replace")}
    if "multipart" in ct:
        return {
            "_type": "multipart",
            "bytes": len(body),
            "note": "Binary upload — body not stored verbatim.",
        }
    text = body.decode("utf-8", errors="replace")
    if len(text) <= LARGE_FIELD_THRESHOLD:
        return {"_type": "text", "body": text}
    return {"_type": "text", "bytes": len(text), "preview": text[:500]}


class ApiTrace:
    """One trace folder per HTTP exchange."""

    def __init__(self, method: str, path: str, query: str = "") -> None:
        self.trace_id = uuid.uuid4().hex[:8]
        self.method = method.upper()
        self.path = path
        self.query = query
        self.started = datetime.now(timezone.utc)
        self.dir = TRACE_ROOT / f"{_utc_stamp()}_{_slug(path)}_{self.trace_id}"
        self.dir.mkdir(parents=True, exist_ok=True)
        _active[self.trace_id] = self.dir

    def save_request(self, body: bytes, content_type: str | None) -> None:
        payload: dict[str, Any] = {
            "method": self.method,
            "path": self.path,
        }
        if self.query:
            payload["query"] = self.query
        parsed = _parse_body(body, content_type)
        if parsed is not None:
            payload["body"] = _externalise_large_fields(parsed, self.dir)
        _write_json(self.dir / "request.json", payload)

    def save_response(
        self,
        status_code: int,
        body: bytes,
        content_type: str | None,
        duration_ms: float,
    ) -> None:
        parsed = _parse_body(body, content_type)
        response_doc: dict[str, Any] = {
            "status_code": status_code,
            "duration_ms": round(duration_ms, 2),
        }
        if parsed is not None:
            response_doc["body"] = _externalise_large_fields(parsed, self.dir)
        _write_json(self.dir / "response.json", response_doc)

        _write_json(
            self.dir / "meta.json",
            {
                "trace_id": self.trace_id,
                "method": self.method,
                "path": self.path,
                "query": self.query or None,
                "status_code": status_code,
                "duration_ms": round(duration_ms, 2),
                "started_at": self.started.isoformat(),
                "trace_dir": str(self.dir.relative_to(Path(__file__).parent)),
            },
        )

    def save_extra(self, name: str, data: Any) -> None:
        safe = re.sub(r"[^\w.-]+", "_", name).strip("_") or "extra"
        _write_json(self.dir / f"{safe}.extra.json", _externalise_large_fields(data, self.dir))


def begin_trace(method: str, path: str, query: str = "") -> ApiTrace | None:
    if not tracing_enabled():
        return None
    TRACE_ROOT.mkdir(parents=True, exist_ok=True)
    return ApiTrace(method, path, query)


def append_extra(trace_id: str, name: str, data: Any) -> Path | None:
    trace_dir = _active.get(trace_id)
    if trace_dir is None:
        # Fall back to scanning recent folders (e.g. after server reload).
        if not TRACE_ROOT.exists():
            return None
        for folder in sorted(TRACE_ROOT.iterdir(), reverse=True):
            meta_path = folder / "meta.json"
            if not meta_path.exists():
                continue
            try:
                meta = json.loads(meta_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            if meta.get("trace_id") == trace_id:
                trace_dir = folder
                break
    if trace_dir is None:
        return None
    safe = re.sub(r"[^\w.-]+", "_", name).strip("_") or "extra"
    out = trace_dir / f"{safe}.extra.json"
    _write_json(out, _externalise_large_fields(data, trace_dir))
    return out


def list_recent_traces(limit: int = 30) -> list[dict[str, Any]]:
    if not TRACE_ROOT.exists():
        return []
    traces: list[dict[str, Any]] = []
    for folder in sorted(TRACE_ROOT.iterdir(), reverse=True):
        if len(traces) >= limit:
            break
        meta_path = folder / "meta.json"
        if not meta_path.is_file():
            continue
        try:
            traces.append(json.loads(meta_path.read_text(encoding="utf-8")))
        except json.JSONDecodeError:
            continue
    return traces
