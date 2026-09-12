#!/usr/bin/env python3
"""
Non-interactive eval runner for the AI doc-editor.

For each case in catalog.json:
  1. Restore the case's golden .docx into the server's docs/ dir.
  2. Send `prompt` through the real LLM proposal API (POST /api/proposals).
  3. If a proposal comes back, auto-approve it.
  4. Download the resulting .docx + re-index, then grade against `expect`
     (each `expect` sub-check is scored individually -> partial credit).
  5. Restore the golden again so the next case starts clean.

Outcomes:
  applied    - a proposal was generated, approved, and grading ran
  no_change  - the model declined / returned nothing / validation rejected it,
               AND the document on disk is byte-identical to the golden
               (this is the PASS state for negative cases)

Scoring (see --help for the KPI this feeds):
  PASS    - every expect sub-check held                         -> weight 1.0
  PARTIAL - some but not all sub-checks held ("partial success") -> weight 0.5
  FAIL    - zero sub-checks held, or the model errored / produced
            a wrong generation (wrong op, invalid batch, HTTP error,
            exception) where "applied" was expected              -> weight 0.0
  XFAIL   - case is tagged known_gap (a documented product limit,
            not an LLM mistake) -> excluded from the KPI denominator
  ERROR   - infra failure (LLM 429/5xx, server unreachable) -> not a model
            verdict, excluded from the KPI denominator, retried first

Cost monitoring: every LLM proposal response carries `usage` (prompt/
completion/total tokens) and `cost_usd` (server-computed from LlmPricing).
This runner sums both per run and prints/records them; --budget-usd aborts
the run early if cumulative cost would exceed it.

KPI: cases are tagged by `op_type` (modify/insert/delete/format/negative).
The headline success-rate the /goal tracks is computed ONLY over
modify/insert/delete cases (Zixuan's "normal change, add, delete operation").
format/negative are reported separately, not counted toward that number.

Usage:
  python evals/run.py                     # all cases
  python evals/run.py --only format       # cases whose id/category/op_type contains "format"
  python evals/run.py --model gpt-4.1-mini
  python evals/run.py --budget-usd 0.50   # abort once cumulative cost would exceed this
  python evals/run.py --baseline XYZ-...docx   # just dump a fresh golden index, no eval
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib import error, request

SCRIPT_DIR = Path(__file__).resolve().parent
SERVER_ROOT = SCRIPT_DIR.parent
REPO_ROOT = SERVER_ROOT.parent
RESULTS_DIR = SCRIPT_DIR / "results"
KPI_OP_TYPES = {"modify", "insert", "delete"}

sys.path.insert(0, str(SCRIPT_DIR))
from docx_probe import probe  # noqa: E402


class BudgetExceeded(Exception):
    pass


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")

    def _call(self, method, path, body=None, timeout=240.0):
        url = f"{self.base}{path}"
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Accept": "application/json"}
        if data:
            headers["Content-Type"] = "application/json"
        req = request.Request(url, data=data, headers=headers, method=method)
        try:
            with request.urlopen(req, timeout=timeout) as r:
                raw = r.read().decode()
                return r.status, (json.loads(raw) if raw else {})
        except error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, (json.loads(raw) if raw else {"error": e.reason})
            except json.JSONDecodeError:
                return e.code, {"error": raw or e.reason}

    def health(self):
        c, b = self._call("GET", "/api/health", timeout=10)
        return c == 200

    def propose_batch(self, doc, batch):
        """Deterministic path: submit a hand-written mutation batch (no LLM)."""
        return self._call("POST", "/api/proposals", {"doc_name": doc, "batch": batch})

    def propose(self, doc, message, model=None, retries=3):
        payload = {"doc_name": doc, "message": message}
        if model:
            payload["model"] = model
        for attempt in range(retries):
            code, body = self._call("POST", "/api/proposals", payload)
            transient = code in (429, 502, 503) or (
                isinstance(body, dict) and "429" in str(body.get("message", "")))
            if not transient or attempt == retries - 1:
                return code, body
            wait = 5 * (attempt + 1)
            print(f"[retry in {wait}s: {code}]", end=" ", flush=True)
            time.sleep(wait)
        return code, body

    def approve(self, pid):
        return self._call("POST", f"/api/proposals/{pid}/approve", timeout=180)

    def index(self, doc):
        c, b = self._call("GET", f"/api/documents/{doc}/index", timeout=90)
        if c != 200:
            raise RuntimeError(f"index {c}: {b}")
        return {blk["target_id"]: blk for blk in b["blocks"]}

    def download(self, doc, dest: Path):
        url = f"{self.base}/api/documents/{doc}/download"
        with request.urlopen(url, timeout=90) as r:
            dest.write_bytes(r.read())


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def restore(golden: Path, working: Path):
    working.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(golden, working)


# --------------------------------------------------------------------------- #
# grading — every sub-check is scored individually so a case can be PARTIAL
# --------------------------------------------------------------------------- #
def grade(case: dict, *, index: dict, fmt: dict, baseline_ids: set[str]) -> tuple[int, int, list[str]]:
    """Returns (passed_checks, total_checks, failure_strings)."""
    exp = case["expect"]
    passed = 0
    total = 0
    fails: list[str] = []

    def check(ok: bool, msg: str):
        nonlocal passed, total
        total += 1
        if ok:
            passed += 1
        else:
            fails.append(msg)

    def text_of(tid):
        return (index.get(tid) or {}).get("text")

    for tid, checks in exp.get("blocks", {}).items():
        got = text_of(tid)
        if "text_equals" in checks:
            check(got == checks["text_equals"],
                  f"{tid}: text_equals expected {checks['text_equals']!r} got {got!r}")
        if "text_contains" in checks:
            check(bool(got) and checks["text_contains"] in got,
                  f"{tid}: text_contains {checks['text_contains']!r} not in {got!r}")

    for phrase in exp.get("text_present", []):
        check(any(phrase in (b.get("text") or "") for b in index.values()),
              f"text_present: {phrase!r} not found in any block")

    for tid in exp.get("unchanged", []):
        check(tid in index, f"unchanged: {tid} disappeared")

    st = exp.get("structure", {})
    for tid in st.get("removed_blocks", []):
        check(tid not in index, f"structure: {tid} should have been removed but is still present")
    if "inserted_min" in st:
        new_ids = set(index) - baseline_ids
        check(len(new_ids) >= st["inserted_min"],
              f"structure: expected >= {st['inserted_min']} new blocks, got {len(new_ids)} ({sorted(new_ids)})")

    for tid, checks in exp.get("format", {}).items():
        bf = fmt.get(tid)
        if bf is None:
            check(False, f"format: {tid} not found in probed docx")
            continue
        if "align" in checks:
            want = checks["align"]
            ok = bf.align == want or (want == "left" and bf.align in (None, "left"))
            check(ok, f"format {tid}: align expected {want} got {bf.align}")
        for tog in ("bold", "italic", "underline"):
            if tog in checks:
                check(getattr(bf, tog) is checks[tog],
                      f"format {tid}: {tog} expected {checks[tog]} got {getattr(bf, tog)}")
        if "size_pt" in checks:
            check(checks["size_pt"] in bf.sizes_pt,
                  f"format {tid}: size {checks['size_pt']}pt not among {bf.sizes_pt}")

    return passed, total, fails


def classify(passed: int, total: int, known_gap: bool) -> str:
    if total == 0:
        return "PASS"  # nothing to check (shouldn't happen with a well-formed case)
    if passed == total:
        return "PASS"
    if known_gap:
        return "XFAIL"
    if passed == 0:
        return "FAIL"
    return "PARTIAL"


WEIGHT = {"PASS": 1.0, "PARTIAL": 0.5, "FAIL": 0.0}


def run_case(api: Api, case: dict, model: str | None) -> dict:
    doc = case["doc"]
    golden = REPO_ROOT / case["golden"]
    working = SERVER_ROOT / "docs" / doc
    rec: dict = {"id": case["id"], "category": case["category"],
                 "op_type": case.get("op_type", "unknown")}

    if not golden.is_file():
        rec.update(status="ERROR", detail=f"golden missing: {golden}")
        return rec

    restore(golden, working)
    time.sleep(0.2)
    try:
        baseline = api.index(doc)
    except RuntimeError as e:
        rec.update(status="ERROR", detail=str(e))
        return rec
    baseline_ids = set(baseline)
    baseline_text = {k: v.get("text") for k, v in baseline.items()}

    t0 = time.time()
    if case.get("batch"):
        rec["mode"] = "engine"
        code, body = api.propose_batch(doc, case["batch"])
    else:
        rec["mode"] = "llm"
        code, body = api.propose(doc, case["prompt"], model)
    rec["propose_http"] = code
    proposal_made = code == 200 and isinstance(body, dict) and body.get("id")
    mutation_ops: dict = {}
    approved = False

    if proposal_made:
        rec["usage"] = body.get("usage")
        rec["cost_usd"] = body.get("cost_usd")
        for m in (body.get("batch") or {}).get("mutations", []):
            mutation_ops[m.get("op")] = mutation_ops.get(m.get("op", "?"), 0) + 1
        acode, abody = api.approve(body["id"])
        rec["approve_http"] = acode
        approved = acode == 200
        if not approved:
            rec["approve_error"] = abody
    else:
        rec["propose_error"] = body if code != 200 else "empty/declined"

    rec["elapsed_s"] = round(time.time() - t0, 1)
    rec["mutations"] = mutation_ops

    # Infra failure (rate limit / upstream 5xx) is an ERROR, not a model FAIL.
    if not proposal_made and code != 200 and code != 400 and code != 422:
        err = body if isinstance(body, dict) else {"body": body}
        if code in (429, 500, 502, 503) or "upstream" in str(err).lower():
            rec.update(status="ERROR", detail=f"LLM infra {code}: {err}")
            restore(golden, working)
            return rec

    want = case["expect"].get("outcome", "applied")
    known_gap = bool(case.get("known_gap"))

    # Determine actual outcome by comparing the structural index (block text +
    # id set) before and after — robust against bookmark-normalization noise
    # that a raw file-hash comparison would trip on.
    if not proposal_made or not approved:
        try:
            after = api.index(doc)
        except RuntimeError as e:
            rec.update(status="ERROR", detail=str(e))
            return rec
        after_text = {k: v.get("text") for k, v in after.items()}
        actual = "no_change" if after_text == baseline_text else "partial_change"
    else:
        actual = "applied"

    rec["outcome"] = actual

    if want in ("no_change", "declined", "rejected"):
        # Binary by nature: either the model correctly held off, or it made a
        # wrong generation it shouldn't have. No partial credit here.
        rec["status"] = "PASS" if actual == "no_change" else "FAIL"
        if rec["status"] == "FAIL":
            rec["failures"] = [f"expected no change, but outcome={actual}"]
        restore(golden, working)
        return rec

    # want == applied
    if actual != "applied":
        # The model either errored, got declined, or its batch failed
        # validation outright (a "wrong generation") -> zero credit, unless
        # this is a documented product-level gap (known_gap -> XFAIL).
        rec["status"] = "XFAIL" if known_gap else "FAIL"
        if known_gap:
            rec["known_gap"] = case["known_gap"]
        rec["failures"] = [f"expected applied, got {actual}: {rec.get('propose_error') or rec.get('approve_error')}"]
        restore(golden, working)
        return rec

    try:
        index = api.index(doc)
        api.download(doc, working)
        fmt = probe(str(working))
    except Exception as e:  # noqa: BLE001
        rec.update(status="ERROR", detail=f"grading probe failed: {e}")
        restore(golden, working)
        return rec

    passed, total, failures = grade(case, index=index, fmt=fmt, baseline_ids=baseline_ids)
    rec["checks"] = f"{passed}/{total}"
    rec["status"] = classify(passed, total, known_gap)
    if failures:
        rec["failures"] = failures
    if rec["status"] == "XFAIL":
        rec["known_gap"] = case["known_gap"]
    restore(golden, working)
    return rec


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, default=SCRIPT_DIR / "catalog.json")
    ap.add_argument("--only", default=None, help="substring filter on id/category/op_type")
    ap.add_argument("--model", default=None)
    ap.add_argument("--budget-usd", type=float, default=None,
                     help="abort the run once cumulative LLM cost would exceed this")
    ap.add_argument("--baseline", default=None, help="dump a fresh golden index for this doc and exit")
    args = ap.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    api = Api(catalog.get("api", "http://localhost:8081"))
    if not api.health():
        print("Server not reachable at", api.base, file=sys.stderr)
        return 2

    if args.baseline:
        for tid, b in api.index(args.baseline).items():
            print(f"{tid}\t{b['type']}\t{b.get('text','')!r}")
        return 0

    cases = catalog["cases"]
    if args.only:
        cases = [c for c in cases
                 if args.only in c["id"] or args.only in c["category"]
                 or args.only in c.get("op_type", "")]

    print(f"Running {len(cases)} case(s) against {api.base}"
          + (f" [model={args.model}]" if args.model else ""))
    results = []
    total_cost = 0.0
    total_tokens = 0
    for c in cases:
        if args.budget_usd is not None and total_cost >= args.budget_usd:
            print(f"\nBudget cap ${args.budget_usd:.4f} reached — stopping "
                  f"({len(results)}/{len(cases)} cases ran).")
            break
        print(f"  … {c['id']:<32} ", end="", flush=True)
        r = run_case(api, c, args.model)
        results.append(r)
        cost = r.get("cost_usd")
        if isinstance(cost, (int, float)):
            total_cost += cost
        usage = r.get("usage")
        if isinstance(usage, dict):
            total_tokens += usage.get("total_tokens", 0)
        mark = {"PASS": "PASS", "PARTIAL": "PARTIAL", "FAIL": "FAIL",
                "ERROR": "ERR ", "XFAIL": "XFAIL"}.get(r["status"], "?")
        cost_str = f" ${cost:.5f}" if isinstance(cost, (int, float)) and cost > 0 else ""
        checks_str = f" [{r['checks']}]" if "checks" in r else ""
        print(f"{mark}{checks_str}  ({r.get('elapsed_s', 0)}s{cost_str})")
        for f in r.get("failures", []):
            print(f"        - {f}")
        if r.get("known_gap"):
            print(f"        (known gap: {r['known_gap']})")
        if r.get("detail"):
            print(f"        ! {r['detail']}")

    counts = {"PASS": 0, "PARTIAL": 0, "FAIL": 0, "ERROR": 0, "XFAIL": 0}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1

    by_cat: dict = {}
    for r in results:
        d = by_cat.setdefault(r["category"], [0.0, 0])
        if r["status"] in WEIGHT:
            d[0] += WEIGHT[r["status"]]
            d[1] += 1

    # KPI: weighted success rate over LLM-driven modify/insert/delete cases
    # only — this is the number the /goal tracks toward 97%. Engine-tier
    # cases (no LLM call) and format/negative cases are reported for
    # visibility but excluded from the target.
    kpi_results = [r for r in results if r.get("mode") == "llm"
                   and r.get("op_type") in KPI_OP_TYPES and r["status"] in WEIGHT]
    kpi_rate = (sum(WEIGHT[r["status"]] for r in kpi_results) / len(kpi_results) * 100
                if kpi_results else None)

    print("\n" + "=" * 68)
    print(f"TOTAL  {counts['PASS']} pass / {counts['PARTIAL']} partial / {counts['FAIL']} fail / "
          f"{counts['ERROR']} error / {counts['XFAIL']} xfail   ({len(results)} cases)")
    for cat, (score, n) in sorted(by_cat.items()):
        print(f"  {cat:<28} {score:.1f}/{n}")
    if kpi_rate is not None:
        print(f"\nKPI (modify/insert/delete only): {kpi_rate:.1f}% "
              f"({sum(WEIGHT[r['status']] for r in kpi_results):.1f}/{len(kpi_results)} weighted) "
              f"— target 97%")
    print(f"\nCost: ${total_cost:.5f} total, {total_tokens} tokens "
          f"across {sum(1 for r in results if r.get('mode') == 'llm')} LLM call(s) "
          f"(engine-tier/manual-batch cases are free)")

    RESULTS_DIR.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = RESULTS_DIR / f"{stamp}.json"
    payload = json.dumps(
        {"timestamp": stamp, "model": args.model, "api": api.base,
         "summary": counts,
         "kpi_modify_insert_delete_pct": kpi_rate,
         "cost_usd_total": round(total_cost, 6),
         "tokens_total": total_tokens,
         "results": results}, indent=2, ensure_ascii=False)
    out.write_text(payload, encoding="utf-8")
    (RESULTS_DIR / "latest.json").write_text(payload, encoding="utf-8")
    print(f"\nWrote {out}")
    return 0 if counts["FAIL"] == 0 and counts["ERROR"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
