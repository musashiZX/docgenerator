# Functional test prompts — `XYZ-Training-and-Instruction-Program-version0.docx`

**Index source:** structural index snapshot (`xyz-index.json`) — **372 blocks** (132 body paragraphs, 240 table cells).

**How to run each test (manual UI)**

1. Open http://localhost:8081 and select `XYZ-Training-and-Instruction-Program-version0.docx`.
2. Paste the **Prompt** into **AI edit** → **Propose with AI** (or stage manually → **Propose batch**).
3. Review the proposal diff; **Approve & save** only if changed blocks match expectations.
4. **Re-index** (↻) and confirm **Expected result** in Blocks or Preview.

**Automated runner (recommended — browser UI)**

1. Start the Java app: `cd docx4j-agent-server && mvn spring-boot:run`
2. Open http://localhost:8081 — in the right panel, **Functional tests** → **Start tests**
3. For each test: review **Preview**, **Proposals** diffs, click **Approve & save** (normal UI), then **Pass** or **Fail** in the test panel. Golden file restores automatically before the next test.
4. Hard-refresh the page (`Ctrl+F5`) after pulling UI updates — the browser may cache old `app.js`.

**Automated runner (terminal alternative)**

```powershell
python docx4j-agent-server/scripts/functional_test_runner.py
```

Test catalog: `docs/xyz-functional-tests.json`. Options: `--from 3`, `--include-negative`, `--model gpt-4.1-mini`.

**Pass criteria (all tests):** proposal validates; approve succeeds; hash guard passes; only listed blocks change text (formatting may differ slightly in preview); rejected/invalid proposals leave the file unchanged.

---

## Document map (quick reference)

| Section | Example `target_id`s | Notes |
|---------|----------------------|-------|
| Title / cover | `dg_p0`–`dg_p8` | Metadata lines; `dg_p8` has corrupted prefix `923925378479Facility:` |
| Purpose and Scope | `dg_p10`–`dg_p15` | Heading + body + three “applies to” bullets |
| Training modules table | `dg_tbl0_r0_c0` … `dg_tbl0_r12_c3` | 13 rows × 4 cols; e.g. `TRN-01` at `dg_tbl0_r1_c0` |
| Training frequency table | `dg_tbl1_*` | After `dg_p22` |
| Empty paragraphs | `dg_p9`, `dg_p16`, `dg_p17`, `dg_p21`, `dg_p23` | Safe delete/insert anchors |

---

## Test cases

| # | Prompt | Expected changed block(s) | Expected result |
|---|--------|---------------------------|-----------------|
| **1** | Change the effective date from January 15, 2025 to March 1, 2025. | `dg_p4` | Text becomes `Effective Date: March 1, 2025`. No other block text changes. |
| **2** | Update the document number from XYZ-TRN-2025-001 to XYZ-TRN-2026-002. | `dg_p2` | Text becomes `Document Number: XYZ-TRN-2026-002`. Only `dg_p2` changes. |
| **3** | In the training modules table, change module code TRN-01 to TRN-01A. | `dg_tbl0_r1_c0` | Cell text changes from `TRN-01` to `TRN-01A`. Other table cells unchanged. |
| **4** | In row TRN-11, change the module title to "Emergency Response and Product Recall Procedures". | `dg_tbl0_r11_c1` | Cell text updates to the new title; `dg_tbl0_r11_c0` still `TRN-11`. |
| **5** | Fix the Facility line: remove the stray number at the start so it reads "Facility: XYZ Frozen Foods Plant, Lelystad, Netherlands". | `dg_p8` | Text becomes `Facility: XYZ Frozen Foods Plant, Lelystad, Netherlands` (no leading `923925378479`). |
| **6** | Bump the version from 1.0 to 1.1. | `dg_p3` | Text becomes `Version: 1.1`. |
| **7** | Insert a new paragraph immediately after "This program applies to:" with the text "Contractors and visitors with plant access must complete induction before entry." | **1 new id** (insert after `dg_p12`); anchor unchanged | New paragraph appears between `dg_p12` and `dg_p13`. Existing bullets `dg_p13`–`dg_p15` text unchanged. Proposal shows one **insert** diff. |
| **8** | Delete the empty paragraph between the Company line and the Purpose and Scope heading (the blank line after `dg_p8`). | `dg_p9` | Empty paragraph removed; `dg_p10` follows `dg_p8` directly in document order. Only `dg_p9` disappears from index. |
| **9** | Replace the three "This program applies to" bullet paragraphs with these three numbered lines: (1) All production and warehouse staff, (2) Quality and maintenance personnel, (3) Temporary workers and approved contractors. Delete the old bullets and add the three new lines after `dg_p12`. | **Delete:** `dg_p13`, `dg_p14`, `dg_p15` · **Insert:** 3 new paragraph ids (chained after `dg_p12`) | Old bullet text gone. Three new paragraphs after `dg_p12` with the numbered content. `dg_p12` text unchanged. Proposal shows 3 deletes + 3 inserts (6 mutations). |
| **10** | Make the Purpose and Scope section more formal: change the heading to "1. Purpose and Scope" and add a period at the end of the long purpose paragraph (the one starting "This document defines the training…"). | `dg_p10`, `dg_p11` | `dg_p10` → `1. Purpose and Scope` (or `1. Purpose and Scope.`). `dg_p11` ends with a period if missing. No insert/delete. |

---

## Suggested manual / negative checks (optional)

| Prompt | Expected outcome |
|--------|------------------|
| Delete table cell `dg_tbl0_r1_c0` | **Rejected** at validation — table cells cannot be deleted in v1. |
| Modify `dg_p4` with wrong `old_text` "January 1, 2025" | **Rejected** — stale / OLD_TEXT_NOT_FOUND; document unchanged. |
| "Add a new row to the training modules table" | **LLM declined** or empty batch — table row insert not supported in v1. |

---

## Notes for testers

- **Block ids are stable** across preview and index (`dg_*` bookmarks). After inserts, new ids look like `dg_p133` (next free ordinal), not renumbered siblings.
- **Focus blocks:** Ctrl+click paragraphs in Preview or Blocks to focus multiple blocks for AI context (`selected_blocks` in the API). Plain click in Blocks replaces focus with one block. Especially useful for tests 7 and 9.
- **PowerShell / curl:** when calling `POST /api/proposals`, write JSON to a file and use `curl --data-binary @file.json` to avoid quoting errors.
- If the document was edited in earlier sessions, re-fetch the index (`GET /api/documents/XYZ-Training-and-Instruction-Program-version0.docx/index`) and adjust `old_text` expectations if cover fields differ.

**Index generated from baseline texts in `xyz-index.json`.** Re-run index after any approved batch before regression-testing the same prompts.
