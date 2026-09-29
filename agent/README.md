# Signals Agent

Signals Agent answers questions about a selected GitHub project through the Spring API. It uses bounded metric tools and a project-scoped Qdrant evidence index for GitHub pull request and issue discussions. It does not query PostgreSQL directly or index source code, saved AI analyses, or productivity scores.

## Start locally

Copy `.env.example` to `.env`. Set a unique database password, then set `AGENT_ENABLED=true` and provide `GITHUB_API_TOKEN`, `GEMINI_API_KEY`, and a random `AGENT_INTERNAL_KEY` of at least 32 characters. Keep `.env` private.

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

To run the Agent tests:

```sh
PYTHONPATH=. python -m unittest discover -s tests
```

The existing five real-project questions are a repeatable manual review set, not an established accuracy benchmark. The Agent uses up to five tool calls and six model turns per request, with a 240-second limit.
