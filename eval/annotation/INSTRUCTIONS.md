# Relevance labelling task

You are labelling a retrieval evaluation set. Work only from the two files in this folder. Do not browse
the web, open GitHub, or read any other folder.

## Files

- `corpus.jsonl`: 123 sources from GitHub pull requests and issues. Each line has a `sourceId`
  (for example `mr:19878:description`, `issue:19745:note:5736573241`) and its full `content`.
- `queries.jsonl`: 55 questions, each with an `id` (`q01` to `q55`) and a `query`.

## What to decide

For every query, list **every** source whose text answers the query, fully or in substantial part.

- **Relevant**: the source's own text states the answer, or a substantial part of it.
- **Not relevant**: the source only mentions the topic, asks for the work, asks a question, or says
  "I'd like to work on this" without giving the answer.
- A source can be relevant to several queries. A query can have several relevant sources.
- Some queries have **no** answer in the corpus. Use an empty list for those. Do not stretch a loosely
  related source to fill the list.
- Read the full `content`, not just the beginning: long tracking issues hide answers deep in numbered items.
- Check all 123 sources for each query; do not stop at the first match.

## Output

Write `codex_labels.jsonl` in this folder, one line per query, in `id` order:

```json
{"id": "q01", "relevant": ["mr:19878:description", "issue:19887:description"], "reason": "one short sentence"}
{"id": "q02", "relevant": [], "reason": "no source discusses this"}
```

Use `sourceId` values exactly as they appear in `corpus.jsonl`. Output all 55 lines.
