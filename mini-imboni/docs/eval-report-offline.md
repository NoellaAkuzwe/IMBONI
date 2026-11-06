# mini-imboni evaluation report

Generated 2026-11-06T14:59. Embeddings: `hashing-v1`. Generator: `offline`.

> **Read this first.** These numbers come from a small dummy corpus (8 documents, 2 teachers, 40 requests) and an offline *extractive* generator that copies source sentences. Retrieval, gate, extraction and reliability numbers are real measurements of this code. Groundedness of the offline generator is high by construction, so the meaningful generation-side number here is the checker's detection rate on injected errors (section 5). Run with `IMBONI_LLM=openai` for real-model groundedness, latency and cost.

## 1. Extraction quality checks

Hand-written fixtures of clean and OCR-damaged text. The checker should flag damage without flagging clean STEM text (chemical formulas, fractions and percentages are the usual false alarms).

- Damaged fixtures caught: **9 / 10** (90%)
- Clean fixtures wrongly flagged: **0 / 7**
- Equations intact after OCR in the scanned worksheet: **2 / 4**

| fixture | expected | flagged |  |
|---|---|---|---|
| clean-prose |  |  | ✓ |
| clean-chem |  |  | ✓ |
| clean-photo-eq |  |  | ✓ |
| clean-fraction |  |  | ✓ |
| clean-table |  |  | ✓ |
| clean-paren-eq |  |  | ✓ |
| clean-percent |  |  | ✓ |
| ocr-l-for-1 | suspicious_equation | suspicious_equation | ✓ |
| ocr-l0 | suspicious_equation | suspicious_equation | ✓ |
| ocr-dangling | suspicious_equation | suspicious_equation | ✓ |
| ocr-unbalanced | suspicious_equation | suspicious_equation | ✓ |
| ocr-replacement | garbled_chars | garbled_chars | ✓ |
| ocr-symbol-noise | garbled_chars | garbled_chars | ✓ |
| ocr-low-density | low_text_density | low_text_density | ✓ |
| broken-table | broken_table | broken_table | ✓ |
| table-no-heading | table_without_heading | table_without_heading | ✓ |
| ocr-exponent-lost | suspicious_equation |  | ✗ |

| scanned-worksheet ground truth | after extraction |
|---|---|
| v = u + at | preserved |
| s = ut + ½at² | damaged |
| a = (v − u) / t | preserved |
| acceleration unit m/s² | damaged |

## 2. Retrieval

28 answerable requests with labelled evidence. **Recall@5**: the right passage is in the top 5. **MRR**: how high it ranks (1.0 = always first). **Complete evidence**: for the 7 requests where an explanation and its equation/table must arrive together, a single top-5 passage contains both.

| chunking | search | passages | recall@5 | MRR | complete evidence |
|---|---|---|---|---|---|
| fixed-80 | VECTOR | 27 | 96% | 0.86 | 86% |
| fixed-80 | KEYWORD | 27 | 96% | 0.86 | 86% |
| fixed-80 | HYBRID | 27 | 96% | 0.86 | 86% |
| structure-180 | VECTOR | 32 | 100% | 0.90 | 100% |
| structure-180 | KEYWORD | 32 | 100% | 0.89 | 100% |
| structure-180 | HYBRID | 32 | 100% | 0.90 | 100% |

Production config (structure-aware + HYBRID) recall@5 by request type: direct 11/11, equation 7/7, paraphrase 4/4, scan 3/3, table 3/3

**Permission leaks** (passages from another teacher's documents returned, across all 6 runs × 40 requests): **0**

## 3. Evidence gate (refuse when the documents don't support a lesson)

Configured thresholds: min similarity 0.12, min IDF-weighted term coverage 0.50. Best on the dev split by sweep: similarity 0.20, coverage 0.60.

| split | answerable answered | false refusals | unanswerable refused | false answers |
|---|---|---|---|---|
| test (configured) | 14/14 | 0 | 5/6 | 1 |
| all (configured) | 28/28 | 0 | 11/12 | 1 |
| test (dev-best 0.20 / 0.60) | 12/14 | 2 | 6/6 | 0 |

Misclassified requests (all splits):

| id | teacher | request | error | coverage | max sim |
|---|---|---|---|---|---|
| q32 | alice | multiplying and dividing fractions | false answer | 0.59 | 0.24 |

<details><summary>Dev-split threshold sweep</summary>

| min sim | min coverage | false refusals | false answers | cost (2×FA + FR) |
|---|---|---|---|---|
| 0.05 | 0.3 | 0 | 0 | 0 |
| 0.05 | 0.4 | 0 | 0 | 0 |
| 0.05 | 0.5 | 0 | 0 | 0 |
| 0.05 | 0.6 | 0 | 0 | 0 |
| 0.05 | 0.7 | 2 | 0 | 2 |
| 0.12 | 0.3 | 0 | 0 | 0 |
| 0.12 | 0.4 | 0 | 0 | 0 |
| 0.12 | 0.5 | 0 | 0 | 0 |
| 0.12 | 0.6 | 0 | 0 | 0 |
| 0.12 | 0.7 | 2 | 0 | 2 |
| 0.2 | 0.3 | 0 | 0 | 0 |
| 0.2 | 0.4 | 0 | 0 | 0 |
| 0.2 | 0.5 | 0 | 0 | 0 |
| 0.2 | 0.6 | 0 | 0 | 0 |
| 0.2 | 0.7 | 2 | 0 | 2 |
| 0.3 | 0.3 | 2 | 0 | 2 |
| 0.3 | 0.4 | 2 | 0 | 2 |
| 0.3 | 0.5 | 2 | 0 | 2 |
| 0.3 | 0.6 | 2 | 0 | 2 |
| 0.3 | 0.7 | 3 | 0 | 3 |

</details>

## 4. End to end (offline generator)

- Outcomes for 40 requests: {AUTO_APPROVED=15, NEEDS_REVIEW=14, REFUSED=11}
- Fact statements supported by their cited passages: **100%** (122/122; 0 weak, 0 unsupported)
- Output failed the JSON/citation contract after one repair attempt: 0; generation errors: 0
- Sent to human review: **14 of 29** generated lessons. Reasons: {cites_flagged_source=7, weak_evidence=7}
- Cross-teacher requests that produced a lesson: 0
- bob requesting alice's document id → rejected as not found (no existence leak)
- repeat of "inputs and outputs of photosynthesis": cache hit=true, 0 ms vs 6 ms, 0 model calls
- Latency (end to end, non-cached): p50 2 ms, p95 6 ms; generation p50 0 ms, p95 2 ms
- Tokens per generated lesson: 742 in / 359 out, 1.00 model calls on average
- Cost per generated lesson at list prices for these token counts: claude-haiku $0.00253, gpt-4o-mini $0.00033 _(projection: token counts are estimated from the offline prompt/output)_

Corpus passages carrying extraction warnings (any lesson citing one goes to review):

| document | passage | page | warnings |
|---|---|---|---|
| kinematics_worksheet_scan | #0 Kinematics Worksheet - Equations of Motion | 1 | suspicious_equation, scanned_source |
| kinematics_worksheet_scan | #1 Example | 1 | scanned_source |
| kinematics_worksheet_scan | #2 Example | 2 | table_without_heading, broken_table, scanned_source |
| kinematics_worksheet_scan | #3 Practice questions | 2 | garbled_chars, scanned_source |

## 5. Grounding checker vs planted errors

Each generated lesson gets known errors planted in it: a fabricated fact with a real citation, a changed number, a flipped negation, and a term swap (one key term replaced by another term from the same material). A planted error counts as caught if the checker marks it weak or unsupported. Note: the offline generator copies sentences verbatim, so the false-flag rate below is a floor; a real model paraphrases, which will raise it. Measure that with `IMBONI_LLM=openai`.

| planted error | caught | rate |
|---|---|---|
| FABRICATED | 28/28 | 100% |
| NUMBER | 11/11 | 100% |
| NEGATION | 27/27 | 100% |
| TERM_SWAP | 2/24 | 8% |

- Correct fact statements wrongly flagged: **0 / 56** (0%)
- Lessons containing planted errors that were routed to human review: **28 / 28**

Missed (these are the checker's known blind spots):

| id | type | statement | checker said |
|---|---|---|---|
| q03 | TERM_SWAP | The rate of Increasing depends on light intensity, carbon dioxide concentration and temperature. | term support 100% |
| q04 | TERM_SWAP | Pondweed placed in water releases bubbles of oxygen when it different. | term support 100% |
| q05 | TERM_SWAP | During Chloroplasts, a plant takes in carbon dioxide from the air through tiny pores called stomata, and absorbs water from the soil through its roots. | term support 93% |
| q06 | TERM_SWAP | The first equation links final velocity to initial velocity, conservation and time: | term support 89% |
| q07 | TERM_SWAP | A larger force produces a larger resultant, and a larger mass produces a smaller acceleration for the same force. | term support 100% |
| q08 | TERM_SWAP | A trolley of mass 2 kg is pushed with a acceleration force of 10 N. | term support 100% |
| q09 | TERM_SWAP | When you swim, you push the water surface and the water pushes you forwards. | term support 86% |
| q14 | TERM_SWAP | Subtraction works the same way: make the fraction the same, then subtract the numerators. | term support 100% |
| q15 | TERM_SWAP | where F is the resultant force in newtons (N), m is the mass in kilograms (kg) and a is the conservation in metres per second squared (m/s²). | term support 91% |
| q16 | TERM_SWAP | A chemical equation must balanced have the same number of atoms of each element on both sides. | term support 86% |

## 6. Ingestion reliability

| scenario | result | passages stored / expected |
|---|---|---|
| transient embedding error on batch 2 | READY after 1 attempt(s) | 8 / 8 |
| embedding down for batch 2 (all 3 retries) | FAILED, 0 partial passages stored | — |
| → reprocess after recovery | READY after 2 attempts | 8 / 8 (0 duplicates) |
| same file uploaded again | returned existing document | 8 / 8 |

