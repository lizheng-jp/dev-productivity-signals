# Signals Agent

Signals Agent answers questions about a selected GitHub project through the Spring API. It uses bounded metric tools and a project-scoped Qdrant evidence index for GitHub pull request and issue discussions. It does not query PostgreSQL directly or index source code, saved AI analyses, or productivity scores.

## Start locally

Copy `.env.example` to `.env`. Set a unique database password, then set `AGENT_ENABLED=true` and provide `GITHUB_API_TOKEN`, `GEMINI_API_KEY`, and a random `AGENT_INTERNAL_KEY` of at least 32 characters. Keep `.env` private.

Evidence embeddings default to Gemini. To index and run retrieval evaluation without a Gemini key, set `EMBEDDING_PROVIDER=local` before building: the image then installs CPU-only `sentence-transformers` and bakes in `EMBEDDING_MODEL` (default `BAAI/bge-base-en-v1.5`; `Qwen/Qwen3-Embedding-0.6B` is truncated to the index's 768 dimensions). Changing the model needs a rebuild and re-index, because hits are filtered by embedding model. Answering questions still needs `GEMINI_API_KEY`. `AGENT_TOOL_POLICY=open` (default) lets the model choose any tool from its first turn, returns tool limits to the model as feedback instead of failing the request, and with `AGENT_STRICT_EVIDENCE=true` (default) tells it not to fill missing evidence with general knowledge; `metrics_first` restores the earlier policy, where the first turn may only read metrics and a why-question is then forced to search evidence. Indexing samples the whole window by creation month by default (`sampling: stratified`, or `INDEX_SAMPLING` when a request does not set it); `recent` reads only the first page of recently updated PRs and issues, which skews a three-month window towards its last weeks. The search score threshold (0.30) was set for Gemini; check `meanScoreOther` in the retrieval evaluation before relying on `insufficientEvidence` with a local model.

```sh
cp .env.example .env
docker compose --profile agent up -d --build
```

Open `http://localhost:3001`, select a GitHub project, and use the Signals Agent button. The Agent and Qdrant services run only when the `agent` profile is enabled. Qdrant is bound to the loopback interface.

## Index project evidence

The index is refreshed manually. For a selected GitHub repository, branch, and date range, run:

```sh
docker compose --profile agent exec agent python -m app.evaluate \
  --project-id github~owner~repo --since 2026-07-01 --until 2026-09-26 \
  --ref-name main --index-only --full-index
```

Indexing is bounded by GitHub pagination and per-item limits. Check `truncated` and source counts in the result; a successful run does not prove full coverage. Normal questions search the stored index and do not refresh GitHub evidence. Answers should include source links and express uncertainty when evidence is weak.

## Evaluate retrieval

`app.retrieval_eval` measures recall@k and MRR of the evidence search against hand-labelled queries. List the indexed sources, write a labels file (one JSON object per line: `query`, `since`, `until`, `relevant` source IDs; an empty `relevant` list marks a question the index cannot answer), then score it:

```sh
docker compose --profile agent exec agent python -m app.retrieval_eval candidates \
  --project-id github~owner~repo --since 2026-07-01 --until 2026-09-26 --ref-name main
docker compose --profile agent exec agent python -m app.retrieval_eval run \
  --project-id github~owner~repo --ref-name main --labels /path/to/labels.jsonl
```

The summary also reports how often unanswerable queries were not flagged `insufficientEvidence`, and the mean score of relevant and other hits, which is the input for tuning the 0.30/0.40 thresholds.

## Logs and metrics

`POST /ask/stream` takes the same request as `/ask` and returns server-sent events: `step` (a tool call running, done, failed or skipped), `thought` (Gemini thought-summary text), `answer` (answer text as it is generated), `answer_reset` (discard streamed text that turned out to precede a tool call), then one `done` event carrying the same response as `/ask`, or an `error` event with the status `/ask` would return. Thought summaries are requested only on this endpoint; Gemini bills thinking tokens either way. The public demo reaches it through Spring at `/api/agent/ask/stream`, which applies the same limits.

Each answer logs a `citation_check` event: how many retrieved hits the answer cited, and PR or issue numbers it named that no tool returned (`unsupported`). Retrieved hits in the response carry `cited`. Model calls log Gemini token counts, and `/metrics` exports them as `signals_agent_model_tokens_total`.

To run the Agent tests:

```sh
PYTHONPATH=. python -m unittest discover -s tests
```

The existing five real-project questions are a repeatable manual review set, not an established accuracy benchmark. The Agent uses up to five tool calls and six model turns per request, with a 240-second limit.
