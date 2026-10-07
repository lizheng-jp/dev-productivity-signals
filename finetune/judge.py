"""Pairwise LLM-judge comparison of two runs' review comments (ROUGE cannot tell good wording from bad).

    GEMINI_API_KEY=... python judge.py --a results/base.predictions.jsonl --b results/tuned.predictions.jsonl \
        --data-dir data --n 150

The judge sees the diff, the human reviewer's comment as a reference, and the two candidates in a random
order. Only hunks where the reviewer commented are used. Treat the result as one signal: the judge is an LLM
with its own biases, so read a sample of its reasons and keep the position-swap randomisation.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import urllib.request
from pathlib import Path

PROMPT = """You are judging two candidate code review comments for the same diff hunk.
A human reviewer wrote the reference comment. A good candidate is specific to this diff, technically correct,
and actionable. It does not need to match the reference wording; a candidate that raises a different but valid
and useful point can be as good. Penalise generic, vague, wrong or irrelevant comments.

Diff:
{diff}

Reference comment:
{reference}

Candidate A:
{a}

Candidate B:
{b}

Reply with only JSON: {{"winner": "A" | "B" | "tie", "reason": "<one sentence>"}}"""


def build_prompt(diff: str, reference: str, a: str, b: str) -> str:
    return PROMPT.format(diff=diff[:6000], reference=reference, a=a or "(empty)", b=b or "(empty)")


def parse_verdict(text: str) -> str | None:
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        return None
    try:
        winner = json.loads(text[start:end + 1]).get("winner")
    except json.JSONDecodeError:
        return None
    return winner if winner in ("A", "B", "tie") else None


def aggregate(outcomes: list[str]) -> dict:
    """outcomes: 'first' (run --a wins), 'second' (run --b wins) or 'tie'."""
    n = len(outcomes)
    a_wins, b_wins = outcomes.count("first"), outcomes.count("second")
    return {"n": n, "a_wins": a_wins, "b_wins": b_wins, "ties": outcomes.count("tie"),
            "b_win_rate_excluding_ties": b_wins / (a_wins + b_wins) if a_wins + b_wins else None}


def call_gemini(prompt: str, model: str, key: str) -> str:
    request = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps({"contents": [{"role": "user", "parts": [{"text": prompt}]}],
                         "generationConfig": {"temperature": 0}}).encode(),
        headers={"x-goog-api-key": key, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        body = json.load(response)
    return body["candidates"][0]["content"]["parts"][0]["text"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--a", required=True, help="predictions.jsonl of the first run (e.g. base)")
    parser.add_argument("--b", required=True, help="predictions.jsonl of the second run (e.g. tuned)")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--split", default="test")
    parser.add_argument("--n", type=int, default=150)
    parser.add_argument("--model", default="gemini-3.5-flash")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--out", default="results/judge.json")
    args = parser.parse_args()
    key = os.environ.get("GEMINI_API_KEY", "")
    if not key:
        raise SystemExit("GEMINI_API_KEY is required")

    load = lambda p: [json.loads(line) for line in Path(p).read_text().splitlines() if line.strip()]
    examples = {r["id"]: r for r in load(Path(args.data_dir) / f"{args.split}.jsonl")}
    runs_a = {r["id"]: r for r in load(args.a)}
    runs_b = {r["id"]: r for r in load(args.b)}
    ids = sorted(i for i in runs_a if i in runs_b and examples[i]["meta"]["type"] != "none")
    rng = random.Random(args.seed)
    rng.shuffle(ids)

    outcomes, details = [], []
    for item_id in ids[: args.n]:
        example = examples[item_id]
        diff = example["messages"][1]["content"]
        text_a, text_b = runs_a[item_id]["pred_comment"], runs_b[item_id]["pred_comment"]
        swapped = rng.random() < 0.5
        shown = (text_b, text_a) if swapped else (text_a, text_b)
        raw = call_gemini(build_prompt(diff, example["meta"]["comment"], *shown), args.model, key)
        verdict = parse_verdict(raw)
        if verdict is None:
            continue
        if verdict == "tie":
            outcome = "tie"
        else:
            first_shown_wins = verdict == "A"
            outcome = "second" if first_shown_wins == swapped else "first"
        outcomes.append(outcome)
        details.append({"id": item_id, "swapped": swapped, "verdict": verdict, "outcome": outcome, "raw": raw})
    summary = aggregate(outcomes)
    summary.update({"judge_model": args.model, "a": args.a, "b": args.b, "seed": args.seed})
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps({"summary": summary, "details": details}, indent=2, ensure_ascii=False))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
