# Answer grading task

You grade answers written by a question-answering agent about GitHub pull requests and issues of one project.
Work only from `answers.jsonl` in this folder. Do not browse the web, open GitHub, or read any other folder.

Each line has an `id`, the `question`, the `keyFacts` a correct answer must contain (empty when the sources
cannot answer the question), an `expectation`, the `sources` (texts the agent could rely on),
`githubMetadata` (state, author, dates and diff size of every pull request or issue the answer mentions,
read from GitHub; the agent can read the same metadata through its tools), and the agent's `answer`.
Judge only against the key facts, the source texts and the GitHub metadata, never against your own knowledge.

## What to decide for each answer

1. For each key fact, a verdict:
   - `covered`: the answer states it, in any wording.
   - `partial`: the answer states part of it, or states it too vaguely to check.
   - `missing`: the answer does not state it.
   - `contradicted`: the answer states something incompatible with it.
2. `unsupportedClaims`: the answer's factual claims that neither the source texts nor the GitHub metadata
   support: invented PR or issue numbers, outcomes, decisions, values or causes, and general knowledge about
   the software (typical settings, defaults, recommendations, how a feature works) that no source states.
   A claim that matches the metadata (a PR's state, merge date, author or diff size) is supported.
   Ignore hedges, offers to help and restatements of the question. Metric figures the agent reports from its
   own tools (merge counts, commit counts and similar) are not unsupported merely because the sources lack
   them; list them only if they contradict a source. Quote or paraphrase each claim briefly.
3. `declined`: true if the answer says the evidence is insufficient or it cannot answer, instead of giving a
   substantive answer; false otherwise. An answer that says the evidence is missing and then answers anyway
   from general knowledge is not declined.

## Output

Write `codex_grades.jsonl` in this folder, one line per answer, in `id` order:

```json
{"id": "g001", "facts": [{"index": 1, "verdict": "covered", "reason": "short reason"}], "unsupportedClaims": [], "declined": false}
```

Use `"facts": []` when `keyFacts` is empty. Output all {count} lines.
