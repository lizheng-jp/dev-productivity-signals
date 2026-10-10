# Reference answer task

You are writing reference answers for an evaluation of a question-answering agent. Work only from
`queries.jsonl` in this folder. Do not browse the web, open GitHub, or read any other folder.

## Input

`queries.jsonl` has 35 lines. Each has an `id` (`a01` to `a35`), a `query`, and the `sources` that answer it:
GitHub pull request and issue texts, each with a `sourceId` and full `content`.

## What to write

For each query, list the **key facts** a correct answer must contain: usually 2 to 4, never more than 4.

- Each fact is one short, checkable statement, so a grader can say whether an answer contains it.
- Use only what the sources say. Do not add background knowledge.
- Include the outcome or current status. If the discussion has no decision, make "it is not decided" a fact,
  so the grader can catch answers that invent a decision.
- Include specific identifiers that matter (PR numbers, function names, values), but not every detail.
- Give each fact the `sourceId`s that support it, exactly as written in the input.
- Read each source in full; answers can sit deep in long texts.

## Output

Write `codex_reference.jsonl` in this folder, one line per query, in `id` order:

```json
{"id": "a01", "keyFacts": [{"fact": "one checkable statement", "sources": ["mr:19878:description"]}]}
```

Output all 35 lines.
