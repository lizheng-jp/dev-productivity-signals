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

## Labelling and agreement

Labels were made twice, independently: a first pass by Claude, then a blind pass by OpenAI Codex given only
the shuffled queries, the corpus and the written rules (`annotation/`: instructions, shuffled queries, Codex labels, id key and `agreement.json`).

| Agreement | Value |
|---|---|
| Cohen's kappa over 55 x 123 query-source pairs | 0.87 |
| Answerable vs unanswerable | 55 / 55 (kappa 1.0) |
| Identical relevant sets | 40 / 55 |

All 15 disagreements were resolved for Codex under the written rule. In 10, the first pass had kept comments
that only ask, ping or acknowledge ("Can you also fix AppenderV2?", "Looks like known breaking changes");
in 5, Codex found answers deep inside long sources that the first pass had read only partly.

## Results

| Metric | Random | bge-base | Qwen3-0.6B |
|---|---|---|---|
| recall@1 | 0.008 | 0.59 | **0.65** |
| recall@3 | 0.024 | **0.84** | 0.83 |
| recall@8 | 0.065 | 0.85 | **0.87** |
| MRR | 0.04 | 0.89 | **0.91** |
| MRR, lexical / paraphrase | | 0.93 / 0.85 | **0.97 / 0.86** |
| entityRecall@3 | | 0.97 | 0.97 |
| recall ceiling (2 per entity) | | 0.95 | 0.95 |
| Unanswerable flagged as insufficient | | **14 / 20** | 12 / 20 |
| Answerable wrongly flagged | | 4 / 35 | **2 / 35** |
| Mean score, relevant / other | | 0.78 / 0.66 | 0.70 / 0.49 |
| Embedding 187 chunks | | ~1 min | ~44 min |

Random figures are the expected values of picking 8 of the 123 sources.

## Findings

1. **Label noise changed the conclusion.** With the first-pass labels, recall@3 was 0.76 and the gap to the
   0.94 ceiling looked like a ranking problem: short comments appeared to crowd out substantive ones. After
   adjudication recall@3 is 0.84; much of that gap was the retriever correctly ignoring comments the labels
   should not have counted. A second, independent annotator was worth more than any model change here.
2. **Finding the right PR or issue is solved.** entityRecall@3 is 0.97 for both models; the remaining source
   recall gap is small and mostly paraphrase queries.
3. **The insufficient-evidence threshold did not transfer between models.** The original 0.40 cut-off, set
   for Gemini embeddings, flagged 0 of 20 unanswerable queries with bge, whose unrelated text already scores
   about 0.6. Thresholds are now per model (`INSUFFICIENT_EVIDENCE_BELOW` in `agent/app/evidence.py`,
   overridable with `EVIDENCE_MIN_TOP_SCORE`).
4. **Qwen3 separates relevant from irrelevant text better** (score gap 0.22 against 0.12) and ranks the first
   hit better, but recall@3 is no better and it is about 40 times slower on CPU. bge-base stays the default.
5. **Long sources need full reading.** Both annotation errors that missed answers came from long tracking
   issues and proposals, where the answer sat in a numbered item far from the start.
6. **Source-level recall understated retrieval** because the search caps sources per entity;
   `recallCeiling` and `entityRecall@3` separate that design limit from ranking errors.

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
