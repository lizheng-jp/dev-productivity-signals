# Answer grading task

You grade answers written by a question-answering agent about GitHub pull requests and issues of one project.
Work only from `answers.jsonl` in this folder. Do not browse the web, open GitHub, or read any other folder.

Each line has an `id`, the `question`, the `keyFacts` a correct answer must contain (empty when the sources
cannot answer the question), an `expectation`, the `sources` (full texts the agent could rely on), and the
agent's `answer`. Judge only against the key facts and the source texts, never against your own knowledge.

## What to decide for each answer

1. For each key fact, a verdict:
   - `covered`: the answer states it, in any wording.
   - `partial`: the answer states part of it, or states it too vaguely to check.
   - `missing`: the answer does not state it.
   - `contradicted`: the answer states something incompatible with it.
2. `unsupportedClaims`: the answer's factual claims about the repository that no source text supports
   (invented PR numbers, outcomes, decisions, values or causes). Quote or paraphrase each briefly.
   Ignore hedges, offers to help and restatements of the question. Metric figures the agent reports from its
   own tools (merge counts, commit counts and similar) are not unsupported merely because the sources lack
   them; list them only if they contradict a source.
3. `declined`: true if the answer says the evidence is insufficient or it cannot answer, instead of giving a
   substantive answer; false otherwise.

## Output

Write `codex_grades.jsonl` in this folder, one line per answer, in `id` order:

```json
{"id": "g01", "facts": [{"index": 1, "verdict": "covered", "reason": "short reason"}], "unsupportedClaims": [], "declined": false}
```

Use `"facts": []` when `keyFacts` is empty. Output every line.
