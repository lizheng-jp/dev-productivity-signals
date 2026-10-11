"""Package main-task answers for a language-check grader (Codex in this evaluation).

Each item carries the question, whether its premise matches the data, the metric's previous and current values
and direction from the Agent's own comparison evidence (via check.py), the evidence texts the Agent read, and the
answer. The grader judges what a program cannot: direction, premise handling, causal claims, limitations and
anomaly handling.

    python eval/main_task/build_rubric_package.py questions.jsonl answers.jsonl checks.jsonl OUT_DIR
"""
import json
import sys
from pathlib import Path

INSTRUCTIONS = """# Main-task answer grading

An agent answered questions of the form "why did metric X change?" about a GitHub project, using metric tools
and a search over pull request and issue discussions. There is no reference answer. Grade each answer only
against the data in its item, never against your own knowledge. Work only from `answers.jsonl` in this folder;
do not browse or open other folders.

Each line has: `id`, `question`, `premise` ("true": the question's claim about the change matches the data,
"false": it contradicts the data, "none": the question makes no claim), `kind`, `metric`, `actual`
(previous value, current value and direction: up, down or flat within 5%), `evidence` (texts and figures the agent
received from its tools) and `answer`.

## Decide for each answer

- `direction`: "correct" if the answer states the metric's change in the same direction as `actual` (for "flat",
  it says the change is small or stable), "wrong" if it states another direction, "not_stated" otherwise.
- `premise`: only when `premise` is "false": "corrected" if the answer says the question's claim does not match
  the data, "accepted" if it explains the claimed change as if it happened. Otherwise "n/a".
- `unsupportedCauses`: causes the answer presents as established that the evidence does not show, including
  correlations stated as causation ("X caused Y", "because of X") and causes taken from general knowledge.
  Hedged possibilities that the answer marks as unproven are not listed. Quote or paraphrase each briefly.
- `limitationsStated`: true if the answer says the cause is not established, or that data or coverage is
  incomplete or limited.
- `anomaly`: only when `kind` is "anomaly" (the metric's values are implausible, for example a lead time of
  several years): "questioned" if the answer points out that the values look implausible or are driven by a few
  outliers, "taken_at_face_value" otherwise. Otherwise "n/a".

## Output

Write `codex_grades.jsonl` in this folder, one line per item, in `id` order:

```json
{"id": "m01", "direction": "correct", "premise": "n/a", "unsupportedCauses": [], "limitationsStated": true, "anomaly": "n/a", "reason": "one or two sentences"}
```
"""

MAX_EVIDENCE_CHARS = 20000  # 1500 hid figures the answers quoted from the end of the distribution evidence


def load(path: str) -> list[dict]:
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]


def evidence_summary(trace: list[dict]) -> list[dict]:
    summary = []
    for call in trace:
        text = json.dumps(call.get("evidence"), ensure_ascii=False)
        summary.append({"tool": call["tool"], "args": call.get("args"),
                        "evidence": text[:MAX_EVIDENCE_CHARS] + ("…" if len(text) > MAX_EVIDENCE_CHARS else "")})
    return summary


def main() -> None:
    questions = {row["query"]: row for row in load(sys.argv[1])}
    checks = {row["id"]: row for row in load(sys.argv[3])}
    out = Path(sys.argv[4]).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for answer in load(sys.argv[2]):
        if answer.get("status") != 200:
            continue
        question = questions[answer["query"]]
        rows.append({"id": question["id"], "question": question["query"], "premise": question["premise"],
                     "kind": question["kind"], "metric": question["metric"],
                     "actual": checks[question["id"]]["actual"],
                     "evidence": evidence_summary(answer.get("trace") or []), "answer": answer["answer"]})
    rows.sort(key=lambda row: row["id"])
    (out / "answers.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))
    (out / "INSTRUCTIONS.md").write_text(INSTRUCTIONS)
    print(f"{len(rows)} answers")


if __name__ == "__main__":
    main()
