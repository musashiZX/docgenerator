#!/usr/bin/env python3
"""
Interactive functional test runner for docx4j-agent-server.

Before EACH test the working .docx is restored from the golden copy so every
prompt starts from the same baseline document.

Human workflow:
  1. Start the Java app (mvn spring-boot:run in docx4j-agent-server).
  2. Run:  python scripts/functional_test_runner.py
  3. For each test: read the prompt + proposal, then press:
       p = pass (proposal OK, reject without applying)
       f = fail
       a = apply (approve on server), show post-apply index snippet, then restore golden
       s = skip
       q = quit

Results are appended to docs/xyz-functional-test-results.jsonl
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import textwrap
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib import error, request

# Paths relative to repo layout:
#   docGenerator/docs/XYZ....docx          (golden — do not mutate)
#   docGenerator/docx4j-agent-server/docs/XYZ....docx  (working copy for server)
SCRIPT_DIR = Path(__file__).resolve().parent
SERVER_ROOT = SCRIPT_DIR.parent
REPO_ROOT = SERVER_ROOT.parent

DEFAULT_API = "http://localhost:8081"
DEFAULT_TESTS = REPO_ROOT / "docs" / "xyz-functional-tests.json"
DEFAULT_RESULTS = REPO_ROOT / "docs" / "xyz-functional-test-results.jsonl"


class ApiClient:
    def __init__(self, base_url: str) -> None:
        self.base = base_url.rstrip("/")

    def _call(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        timeout: float = 180.0,
    ) -> tuple[int, Any]:
        url = f"{self.base}{path}"
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = request.Request(url, data=data, headers=headers, method=method)
        try:
            with request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8")
                return resp.status, json.loads(raw) if raw else {}
        except error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw) if raw else {"error": e.reason}
            except json.JSONDecodeError:
                payload = {"error": raw or e.reason}
            return e.code, payload

    def health(self) -> bool:
        code, body = self._call("GET", "/api/health", timeout=10)
        return code == 200 and body.get("status") == "ok"

    def propose(self, doc_name: str, message: str, model: str | None = None) -> tuple[int, Any]:
        payload: dict[str, Any] = {"doc_name": doc_name, "message": message}
        if model:
            payload["model"] = model
        return self._call("POST", "/api/proposals", payload)

    def list_proposals(self, doc_name: str) -> list[dict]:
        code, body = self._call("GET", f"/api/proposals?doc={doc_name}", timeout=30)
        if code != 200 or not isinstance(body, list):
            return []
        return body

    def reject(self, proposal_id: str) -> None:
        self._call("POST", f"/api/proposals/{proposal_id}/reject", timeout=30)

    def approve(self, proposal_id: str) -> tuple[int, Any]:
        return self._call("POST", f"/api/proposals/{proposal_id}/approve", timeout=120)

    def index(self, doc_name: str) -> dict:
        code, body = self._call("GET", f"/api/documents/{doc_name}/index", timeout=60)
        if code != 200:
            raise RuntimeError(f"Index failed ({code}): {body}")
        return body


def restore_golden(golden: Path, working: Path) -> None:
    if not golden.is_file():
        raise FileNotFoundError(
            f"Golden document not found: {golden}\n"
            "Place the pristine copy at docs/XYZ-Training-and-Instruction-Program-version0.docx"
        )
    working.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(golden, working)


def clear_pending_proposals(api: ApiClient, doc_name: str) -> int:
    n = 0
    for p in api.list_proposals(doc_name):
        if p.get("status") == "PENDING":
            api.reject(p["id"])
            n += 1
    return n


def format_proposal(proposal: dict) -> str:
    lines = [
        f"  Proposal id: {proposal.get('id')}",
        f"  Status:      {proposal.get('status')}",
        f"  Model:       {proposal.get('model')}",
    ]
    batch = proposal.get("batch") or {}
    if batch.get("explanation"):
        lines.append(f"  Explanation: {batch['explanation']}")
    mutations = batch.get("mutations") or []
    lines.append(f"  Mutations ({len(mutations)}):")
    for i, m in enumerate(mutations):
        op = m.get("op")
        if op == "modify":
            lines.append(
                f"    [{i}] modify {m.get('target_id')}: "
                f"\"{m.get('old_text')}\" -> \"{m.get('new_text')}\""
            )
        elif op == "insert":
            lines.append(
                f"    [{i}] insert {m.get('position')} {m.get('anchor_id')}: "
                f"\"{m.get('text')}\""
            )
        elif op == "delete":
            lines.append(f"    [{i}] delete {m.get('target_id')}")
        else:
            lines.append(f"    [{i}] {m}")
    diffs = proposal.get("diffs") or []
    lines.append(f"  Diffs ({len(diffs)}):")
    for d in diffs:
        tid = d.get("target_id")
        op = d.get("op")
        before = d.get("before_text")
        after = d.get("after_text")
        if before is not None:
            lines.append(f"    - {tid} ({op}) BEFORE: {short(before)}")
        if after is not None:
            lines.append(f"    + {tid} ({op}) AFTER:  {short(after)}")
    return "\n".join(lines)


def short(s: str, n: int = 100) -> str:
    s = (s or "").replace("\n", " ")
    return s if len(s) <= n else s[: n - 1] + "…"


def diff_blocks_in_proposal(proposal: dict) -> set[str]:
    ids: set[str] = set()
    for d in proposal.get("diffs") or []:
        if d.get("target_id"):
            ids.add(d["target_id"])
    return ids


def hint_check(proposal: dict, test: dict) -> str:
    """Automated hint: do diff target_ids overlap expected_blocks?"""
    expected = set(test.get("expected_blocks") or [])
    if "NEW_INSERT" in expected:
        expected.discard("NEW_INSERT")
    got = diff_blocks_in_proposal(proposal)
    inserts = sum(1 for d in (proposal.get("diffs") or []) if d.get("op") == "insert")
    missing = expected - got
    extra = got - expected
    parts = []
    if not missing and (not extra or "NEW_INSERT" in (test.get("expected_blocks") or [])):
        parts.append("  [hint] Diff blocks match expected ids (inserts counted separately).")
    if missing:
        parts.append(f"  [hint] Expected blocks NOT in diff: {sorted(missing)}")
    if extra:
        parts.append(f"  [hint] Unexpected blocks in diff: {sorted(extra)}")
    if "NEW_INSERT" in (test.get("expected_blocks") or []):
        want_ins = test["expected_blocks"].count("NEW_INSERT")
        if inserts >= want_ins:
            parts.append(f"  [hint] Insert count OK ({inserts} >= {want_ins}).")
        else:
            parts.append(f"  [hint] Expected >={want_ins} inserts, got {inserts}.")
    return "\n".join(parts) if parts else ""


def show_index_snippet(api: ApiClient, doc_name: str, block_ids: list[str]) -> None:
    try:
        idx = api.index(doc_name)
        by_id = {b["target_id"]: b for b in idx.get("blocks", [])}
        print("\n  --- Post-apply index snippet ---")
        for bid in block_ids:
            if bid == "NEW_INSERT":
                continue
            b = by_id.get(bid)
            if b:
                print(f"    {bid}: {short(b.get('text') or '(empty)', 120)}")
            else:
                print(f"    {bid}: (not in index — deleted?)")
        print("  --- end snippet ---\n")
    except Exception as ex:
        print(f"  [warn] Could not fetch index: {ex}")


def append_result(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


def run_test(
    api: ApiClient,
    test: dict,
    *,
    doc_name: str,
    golden: Path,
    working: Path,
    model: str | None,
    results_path: Path,
    expect_failure: bool = False,
) -> str:
    """Returns 'pass', 'fail', 'skip', or 'quit'."""
    tid = test.get("id")
    name = test.get("name", "")
    prompt = test["prompt"]

    print("\n" + "=" * 72)
    print(f"TEST {tid} — {name}")
    print("=" * 72)
    print(textwrap.fill(f"Prompt: {prompt}", width=72))
    print(f"\nExpected blocks: {test.get('expected_blocks')}")
    print(f"Expected result: {test.get('expected_result')}")
    if expect_failure:
        print("(negative test — API error / empty proposal may be expected)")

    print("\nRestoring golden document…")
    restore_golden(golden, working)
    cleared = clear_pending_proposals(api, doc_name)
    if cleared:
        print(f"  Cleared {cleared} pending proposal(s).")
    time.sleep(0.3)  # let filesystem settle on Windows

    print("Calling POST /api/proposals (LLM)…")
    code, body = api.propose(doc_name, prompt, model)

    record: dict[str, Any] = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "test_id": tid,
        "test_name": name,
        "prompt": prompt,
        "http_status": code,
        "expect_failure": expect_failure,
    }

    if code != 200:
        print(f"\n  API returned HTTP {code}:")
        print(json.dumps(body, indent=2, ensure_ascii=False))
        record["outcome"] = "api_error"
        record["response"] = body
        if expect_failure:
            print("\n  (Negative test — error may be expected.)")
        while True:
            choice = input("\nMark as [p]ass [f]ail [s]kip [q]uit? ").strip().lower()
            if choice in ("p", "f", "s", "q"):
                break
        record["human_verdict"] = choice
        append_result(results_path, record)
        return {"p": "pass", "f": "fail", "s": "skip", "q": "quit"}[choice]

    proposal = body
    record["proposal_id"] = proposal.get("id")
    print("\n" + format_proposal(proposal))
    hint = hint_check(proposal, test)
    if hint:
        print(hint)

    while True:
        choice = input(
            "\n[p]ass  [f]ail  [a]pply then review  [s]kip  [q]uit? "
        ).strip().lower()
        if choice in ("p", "f", "a", "s", "q"):
            break

    if choice == "a":
        pid = proposal["id"]
        print(f"Approving proposal {pid}…")
        acode, abody = api.approve(pid)
        record["approve_status"] = acode
        record["approve_response"] = abody
        if acode == 200:
            print(f"  Applied: {abody.get('changed_ids')}")
            show_index_snippet(
                api,
                doc_name,
                [b for b in (test.get("expected_blocks") or []) if b != "NEW_INSERT"],
            )
        else:
            print(f"  Approve failed ({acode}): {json.dumps(abody, indent=2)}")
        print("Restoring golden for next test…")
        restore_golden(golden, working)
        clear_pending_proposals(api, doc_name)
        sub = input("After apply, mark test [p]ass or [f]ail? ").strip().lower()
        record["human_verdict"] = sub if sub in ("p", "f") else choice
        record["outcome"] = "applied"
        append_result(results_path, record)
        return "pass" if record["human_verdict"] == "p" else "fail" if record["human_verdict"] == "f" else "skip"

    if choice in ("p", "f", "s"):
        api.reject(proposal["id"])
    record["human_verdict"] = choice
    record["outcome"] = "proposal_only"
    append_result(results_path, record)
    return {"p": "pass", "f": "fail", "s": "skip", "q": "quit"}[choice]


def main() -> int:
    parser = argparse.ArgumentParser(description="Interactive XYZ functional test runner")
    parser.add_argument("--api", default=DEFAULT_API, help="Server base URL")
    parser.add_argument("--tests", type=Path, default=DEFAULT_TESTS, help="Test catalog JSON")
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS, help="Results JSONL")
    parser.add_argument("--model", default=None, help="OpenAI model override")
    parser.add_argument("--include-negative", action="store_true", help="Run negative_tests too")
    parser.add_argument("--from", dest="from_id", type=int, default=None, help="Start at test id")
    args = parser.parse_args()

    if not args.tests.is_file():
        print(f"Test catalog not found: {args.tests}", file=sys.stderr)
        return 1

    catalog = json.loads(args.tests.read_text(encoding="utf-8"))
    doc_name = catalog["document"]
    golden = REPO_ROOT / catalog.get("golden_path", f"docs/{doc_name}")
    working = SERVER_ROOT / "docs" / doc_name

    api = ApiClient(args.api)
    print(f"API:    {args.api}")
    print(f"Golden: {golden}")
    print(f"Working:{working}")
    print(f"Tests:  {args.tests}")

    if not api.health():
        print("\nServer not reachable. Start the Java app first:", file=sys.stderr)
        print("  cd docx4j-agent-server && mvn spring-boot:run", file=sys.stderr)
        return 1
    print("Health: OK\n")

    if not golden.is_file():
        print(f"ERROR: Golden file missing: {golden}", file=sys.stderr)
        return 1

    tests: list[dict] = catalog["tests"]
    if args.include_negative:
        for nt in catalog.get("negative_tests", []):
            tests.append({**nt, "expected_blocks": []})

    if args.from_id is not None:
        tests = [t for t in tests if int(t.get("id", 0)) >= args.from_id or str(t.get("id", "")).startswith("N")]

    passed = failed = skipped = 0
    for test in tests:
        expect_failure = bool(test.get("expect_failure"))
        verdict = run_test(
            api,
            test,
            doc_name=doc_name,
            golden=golden,
            working=working,
            model=args.model,
            results_path=args.results,
            expect_failure=expect_failure,
        )
        if verdict == "quit":
            print("Quit requested.")
            break
        if verdict == "pass":
            passed += 1
        elif verdict == "fail":
            failed += 1
        else:
            skipped += 1

    print("\n" + "=" * 72)
    print(f"Summary: {passed} passed, {failed} failed, {skipped} skipped")
    print(f"Results log: {args.results}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
