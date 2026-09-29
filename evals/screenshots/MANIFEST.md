# Screenshot manifest — before/after for every eval case

Captured via claude-in-chrome browser automation against the live app UI
(http://localhost:8081), driving real AI edits through the real
`/api/proposals` → `/approve` pipeline (Gemini 3.5 flash-lite), then
restoring each case's golden document afterward.

| # | case id | op_type | doc | status |
|---|---------|---------|-----|--------|
| 1 | text-effective-date | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 2 | text-document-number | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 3 | text-version-bump | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 4 | text-table-cell-code | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 5 | text-table-cell-title | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 6 | insert-paragraph-after | insert | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 7 | text-find-replace-multi | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 8 | delete-empty-paragraph | delete (known-gap, correctly declined) | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 9 | replace-bullets-with-numbered | delete+insert | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 10 | format-bold-title | format | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 11 | format-center-heading | format | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 12 | format-cell-center | format | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 13 | table-add-two-rows-additives | insert | guangdeli_eval.docx | OK |
| 14 | neg-delete-table-cell | negative (correctly declined) | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 15 | neg-nonexistent-section | negative (correctly declined) | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 16 | xyz-modify-prepared-by | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 17 | xyz-modify-company-name | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 18 | xyz-modify-training-frequency | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 19 | xyz-insert-training-type-row | insert | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 20 | xyz-delete-approved-by | delete | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 21 | gdl-modify-net-contents | modify | guangdeli_eval2.docx | OK |
| 22 | gdl-modify-hs-code | modify | guangdeli_eval2.docx | OK |
| 23 | gdl-modify-country-of-origin | modify | guangdeli_eval2.docx | OK |
| 24 | gdl-modify-water-percentage | modify | guangdeli_eval2.docx | OK |
| 25 | gdl-modify-halal-institution | modify | guangdeli_eval2.docx | OK |
| 26 | gdl-insert-component-row | insert | guangdeli_eval2.docx | OK |
| 27 | gdl-insert-additives-row-single | insert | guangdeli_eval2.docx | OK |
| 28 | gdl-insert-gmo-paragraph | insert | guangdeli_eval2.docx | OK |
| 29 | gdl-delete-raw-material-note | delete | guangdeli_eval2.docx | OK |
| 30 | gdl-delete-ingredient-quality-note | delete | guangdeli_eval2.docx | OK |
| 31 | cv-modify-name | modify | mydoc_eval.docx | OK |
| 32 | cv-modify-work-company | modify | mydoc_eval.docx | OK |
| 33 | cv-modify-education-years | modify | mydoc_eval.docx | OK |
| 34 | cv-insert-bullet-after-degree | insert | mydoc_eval.docx | OK |
| 35 | cv-insert-work-bullet | insert | mydoc_eval.docx | OK |
| 36 | cv-delete-tech-skills-placeholder | delete | mydoc_eval.docx | OK |
| 37 | cv-delete-certificates-placeholder | delete | mydoc_eval.docx | OK |
| 38 | xyz-modify-disambiguated-frequency | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 39 | xyz-insert-before-heading | insert | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 40 | xyz-multi-modify-two-fields | modify | XYZ-Training-and-Instruction-Program-version0.docx | OK |
| 41 | gdl-delete-indirect-reference | delete | guangdeli_eval3.docx | OK |
| 42 | gdl-delete-gmo-paragraph-vague | delete | guangdeli_eval3.docx | OK |
| 43 | gdl-insert-additive-before-first-row | insert | guangdeli_eval3.docx | OK |
| 44 | gdl-modify-afg-article-number | modify | guangdeli_eval3.docx | OK |
| 45 | cv-multi-modify-two-fields | modify | mydoc_eval2.docx | OK |
| 46 | cv-delete-basic-info-placeholder | delete | mydoc_eval2.docx | OK |
| 47 | cv-insert-profile-summary | insert | mydoc_eval2.docx | OK |
| 48 | cvl-modify-title | modify | cv_latest_eval.docx | OK |
| 49 | cvl-modify-education-dates | modify | cv_latest_eval.docx | OK |
| 50 | cvl-insert-skill-line | insert | cv_latest_eval.docx | OK |
| 51 | cvl-delete-side-project-intro | delete | cv_latest_eval.docx | OK |
| 52 | cv13-modify-education-dates | modify | cv_v13_eval.docx | OK |
| 53 | cv13-delete-internship-bullet | delete | cv_v13_eval.docx | OK |
| 54 | po-modify-quantity | modify | it_po_eval.docx | OK |
| 55 | po-modify-approval-status | modify | it_po_eval.docx | OK |
| 56 | po-insert-line-item-row | insert | it_po_eval.docx | OK |
| 57 | po-delete-delivery-note | delete | it_po_eval.docx | OK |
| 58 | lab-modify-yield-strength | modify | lab_report_eval.docx | OK |
| 59 | lab-insert-specimen-row | insert | lab_report_eval.docx | OK |
| 60 | lab-delete-reviewer-line | delete | lab_report_eval.docx | OK |
---

All 60 cases captured — 120 screenshots (60 `_before.png` + 60 `_after.png`),
verified present on disk against the 60 case ids in `evals/catalog.json`
with no gaps or duplicates. Plus 3 pre-existing demo screenshots
(`additives_before_after.png`, `full_before.png`, `full_after.png`) from
the original manual GUANGDELI walkthrough — 123 PNGs total in this directory.
