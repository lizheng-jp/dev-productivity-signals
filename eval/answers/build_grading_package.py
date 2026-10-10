"""Build a blind grading package for an external grader (Codex in this evaluation).

Answers from several runs are pooled and shuffled so the grader cannot tell which configuration wrote them.
Each item carries the question, key facts, source texts (labelled relevant sources, key-fact sources and
sources the Agent retrieved) and GitHub metadata for every PR or issue the answer mentions, because the Agent
reads that metadata from its tools and round 1 showed a grader without it counts it as unsupported.

    python eval/answers/build_grading_package.py --out ~/codex-grading-2 \
        --run feedback=eval/answers/round2/feedback.jsonl --run strict=eval/answers/round2/strict.jsonl

Needs the GitHub CLI (`gh`) for metadata. Writes answers.jsonl and INSTRUCTIONS.md to --out and the id key
to eval/answers/<key-name>.json.
"""
import argparse
import json
import random
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = "prometheus/prometheus"
REFERENCE = re.compile(r"(?:#|/pull/|/issues/)(\d{3,6})\b")
INSTRUCTIONS = (Path(__file__).parent / "grading_instructions.md").read_text()


def load(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


METADATA_JQ = ("{number, title, state, merged_at, author: .user.login, created_at, updated_at, additions, "
               "deletions, changed_files, branch: .head.ref}")


def metadata(number: int, cache: dict[int, dict]) -> dict:
    if number not in cache:
        cache[number] = {"number": number, "found": False}
        for kind, label in (("pulls", "pull request"), ("issues", "issue")):
            result = subprocess.run(["gh", "api", f"repos/{REPO}/{kind}/{number}", "--jq", METADATA_JQ],
                                    capture_output=True, text=True)
            if result.returncode == 0:
                fields = {k: v for k, v in json.loads(result.stdout).items() if v is not None}
                cache[number] = {"kind": label, **fields}
                break
    return cache[number]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--run", action="append", required=True, help="name=answers.jsonl")
    parser.add_argument("--key-name", default="grading_key_round2")
    parser.add_argument("--seed", type=int, default=20261010)
    args = parser.parse_args()

    corpus = {row["sourceId"]: row["content"] for row in load(ROOT / "corpus.jsonl")}
    references = {row["query"]: row for row in load(ROOT / "answers" / "reference.jsonl")}
    labels = {row["query"]: row for row in load(ROOT / "labels.jsonl")}
    cache: dict[int, dict] = {}
    items = []
    for run in args.run:
        name, path = run.split("=", 1)
        for answer in load(Path(path)):
            if answer["status"] != 200:
                continue
            reference = references[answer["query"]]
            ids = set(labels[answer["query"]]["relevant"])
            ids |= {source for fact in reference["keyFacts"] for source in fact["sources"]}
            ids |= {source["sourceId"] for source in answer["sources"] if source.get("sourceId")}
            numbers = sorted({int(n) for n in REFERENCE.findall(answer["answer"])})
            items.append({"run": name, "query": answer["query"], "reference": reference, "answer": answer["answer"],
                          "sources": sorted(i for i in ids if i in corpus),
                          "github": [metadata(n, cache) for n in numbers]})
    random.Random(args.seed).shuffle(items)

    out = Path(args.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    key = []
    with open(out / "answers.jsonl", "w", encoding="utf-8") as handle:
        for index, item in enumerate(items, 1):
            item_id = f"g{index:03d}"
            key.append({"id": item_id, "run": item["run"], "query": item["query"]})
            handle.write(json.dumps({
                "id": item_id, "question": item["query"],
                "keyFacts": [{"index": i, "fact": fact["fact"]}
                             for i, fact in enumerate(item["reference"]["keyFacts"], 1)],
                "expectation": "answer" if item["reference"]["expected"] == "answer"
                else "the sources cannot answer this question; a good answer says so",
                "sources": [{"sourceId": s, "content": corpus[s][:6000]} for s in item["sources"]],
                "githubMetadata": item["github"],
                "answer": item["answer"]}, ensure_ascii=False) + "\n")
    (out / "INSTRUCTIONS.md").write_text(INSTRUCTIONS.replace("{count}", str(len(items))))
    (ROOT / "answers" / f"{args.key_name}.json").write_text(json.dumps(key, indent=1))
    print(f"{len(items)} answers, {len(cache)} GitHub items")


if __name__ == "__main__":
    main()
