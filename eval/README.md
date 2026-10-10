# Retrieval evaluation: prometheus/prometheus

Offline evaluation of the Signals Agent evidence search (`agent/app/retrieval_eval.py`) on one real repository.

## Setup

- Corpus: PR descriptions, PR comments and reviews, issues and issue comments from `prometheus/prometheus`,
  2026-07-11 to 2026-10-08, indexed with `--sample-scale 5`: 123 sources (40 PRs, 10 issues), 187 chunks.
  Bot authors are excluded. The repository was chosen after sampling ten projects for human versus bot
  discussion; `openai/openai-java` was dropped because most of its discussion was automated.
- Labels (`labels.jsonl`): 55 queries labelled against the full text of the indexed sources, not previews.
  - 20 paraphrase queries that avoid the source wording, 15 lexical queries that reuse it.
  - 20 unanswerable queries (`relevant: []`): 8 near-miss (topic is indexed, answer is not) and 12 unrelated.
- Models: `BAAI/bge-base-en-v1.5` and `Qwen/Qwen3-Embedding-0.6B` (truncated to 768 dimensions), both on CPU.
  Qwen3 vectors were produced with `retrieval_eval reembed` from the same chunks, so both models see an
  identical corpus.
- Search returns the top 8 sources, at most 2 per PR or issue.

## Results

| Metric | Random | bge-base | Qwen3-0.6B |
|---|---|---|---|
| recall@1 | 0.008 | 0.50 | **0.56** |
| recall@3 | 0.024 | 0.76 | **0.78** |
| MRR | 0.04 | 0.87 | **0.91** |
| MRR, lexical / paraphrase | | 0.90 / 0.85 | **0.97** / 0.86 |
| entityRecall@3 | | 0.97 | 0.97 |
| recall ceiling (2 per entity) | | 0.94 | 0.94 |
| Unanswerable flagged as insufficient | | **14 / 20** | 12 / 20 |
| Answerable wrongly flagged | | 4 / 35 | **2 / 35** |
| Mean score, relevant / other | | 0.78 / 0.66 | 0.70 / 0.48 |
| Embedding 187 chunks | | ~1 min | ~44 min |

Random figures are the expected values of picking 8 of the 123 sources.

## Findings

1. **Finding the right PR or issue is solved; picking the right comment in it is not.** entityRecall@3 is
   0.97 for both models, while recall@3 is 0.76 to 0.78 against a ceiling of 0.94. Short comments such as
   "could you elaborate?" or "I'd like to take this issue" often outrank the substantive one.
2. **The insufficient-evidence threshold did not transfer between models.** The original 0.40 cut-off,
   set for Gemini embeddings, flagged 0 of 20 unanswerable queries with bge, whose unrelated text already
   scores about 0.6. Thresholds are now per model (`INSUFFICIENT_EVIDENCE_BELOW` in `agent/app/evidence.py`,
   overridable with `EVIDENCE_MIN_TOP_SCORE`).
3. **Qwen3 separates relevant from irrelevant text better** (score gap 0.22 against 0.12) and ranks lexical
   queries better, but paraphrase queries are no better, and it is about 40 times slower on CPU.
4. **Labelling from previews was wrong once.** One query's answer sat in item 6 of a long tracking issue
   that the 200-character preview cut off; labels were rechecked against full text.
5. **Source-level recall understated retrieval** because the search caps sources per entity;
   `recallCeiling` and `entityRecall@3` were added to separate that design limit from ranking errors.

## Caveats

- The thresholds (bge 0.73, Qwen3 0.59) were picked on these same 55 queries, so the flagging numbers are
  optimistic. They need confirming on held-out queries or another repository.
- 123 sources and 55 queries are small; differences of a few hundredths between models are within noise.
- The backend scans only the first page (100 items) of recently updated PRs and issues, so the corpus is a
  sample of the window, with only 10 issues.

## Reproduce

```sh
docker compose --profile agent exec agent python -m app.evaluate --project-id github~prometheus~prometheus \
    --since 2026-07-11 --until 2026-10-08 --ref-name main --index-only --full-index --sample-scale 5
docker compose --profile agent cp eval/labels.jsonl agent:/tmp/labels.jsonl
docker compose --profile agent exec agent python -m app.retrieval_eval run \
    --project-id github~prometheus~prometheus --ref-name main --labels /tmp/labels.jsonl
```

Re-indexing later fetches a different GitHub sample, so label source IDs may no longer exist.
`candidates.jsonl` lists the sources these labels were written against.
