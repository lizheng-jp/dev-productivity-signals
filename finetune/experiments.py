"""Record experiments in a git-tracked folder and keep a comparison table up to date.

    # after training into runs/exp01 and evaluating into results/exp01
    python experiments.py record --id exp01-baseline --train-dir runs/exp01 --results-dir results/exp01 \
        --hypothesis "LoRA on 30k examples beats zero-shot on commented-hunk type F1" \
        --change "baseline: Qwen2.5-Coder-1.5B, LoRA r=16, 2 epochs"
    python experiments.py record --id exp02-comment-first ... --parent exp01-baseline
    python experiments.py index          # regenerate experiments/INDEX.md

Each experiment gets experiments/<id>/ with experiment.json (machine record), NOTES.md (you fill in: what you
saw, why, what next), train_meta.json, data_meta.json and the evaluation result files.
"""
from __future__ import annotations

import argparse
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

from codereview_ft.runinfo import git_info

NOTES_TEMPLATE = """# {id}

**Hypothesis.** {hypothesis}

**Change vs {parent}.** {change}

## Setup (from train_meta.json)
{setup}

## Results
See `results/` and the table in `../INDEX.md`. Write the numbers you will quote here, with their confidence intervals.

## What I saw
- (Per-class failures, confusion matrix, 20-30 wrong predictions read by hand and grouped by cause.)

## Decision and next experiment
- (Keep / drop the change, and why. One sentence on what this run does NOT show.)
"""


def _load(path: Path) -> dict | None:
    return json.loads(path.read_text()) if path.exists() else None


def _setup_lines(train_meta: dict | None) -> str:
    if not train_meta:
        return "- (no training run recorded: evaluation-only experiment)"
    args = train_meta.get("args", {})
    runtime = (train_meta.get("train_metrics") or {}).get("train_runtime")
    lines = [f"- base model: {args.get('base_model')}", f"- LoRA r: {args.get('lora_r')}, lr: {args.get('lr')}, "
             f"epochs: {args.get('epochs')}, seed: {args.get('seed')}",
             f"- train examples: {train_meta.get('train_examples')}, GPU: {train_meta.get('gpu')}",
             f"- final eval loss: {(train_meta.get('final_eval') or {}).get('eval_loss')}"]
    if runtime:
        lines.append(f"- training time: {runtime / 60:.0f} min")
    return "\n".join(lines)


def record(experiments_dir: Path, exp_id: str, *, results_dir: Path, train_dir: Path | None, data_dir: Path,
           hypothesis: str, change: str, parent: str | None, with_predictions: bool = False,
           force: bool = False, headline: str | None = None) -> Path:
    dest = experiments_dir / exp_id
    if dest.exists() and not force:
        raise FileExistsError(f"{dest} exists; use --force to overwrite")
    (dest / "results").mkdir(parents=True, exist_ok=True)

    names = []
    for path in sorted(results_dir.glob("*.json")):
        shutil.copy(path, dest / "results" / path.name)
        names.append(path.stem)
        predictions = path.with_name(f"{path.stem}.predictions.jsonl")
        if with_predictions and predictions.exists():
            shutil.copy(predictions, dest / "results" / predictions.name)
    if not names:
        raise SystemExit(f"No result files in {results_dir}")

    train_meta = _load(train_dir / "train_meta.json") if train_dir else None
    if train_meta:
        (dest / "train_meta.json").write_text(json.dumps(train_meta, indent=2))
    data_meta = _load(data_dir / "meta.json")
    if data_meta:
        (dest / "data_meta.json").write_text(json.dumps(data_meta, indent=2))

    if headline is None:  # the first result produced by a tuned adapter, else the first file
        headline = next((n for n in names if (_load(dest / "results" / f"{n}.json") or {}).get("adapter")), names[0])
    record_ = {"id": exp_id, "date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
               "hypothesis": hypothesis, "change": change, "parent": parent, "headline": headline,
               "results": names, "git": git_info(), "has_training": train_meta is not None}
    (dest / "experiment.json").write_text(json.dumps(record_, indent=2, ensure_ascii=False))

    notes = dest / "NOTES.md"
    if not notes.exists():
        notes.write_text(NOTES_TEMPLATE.format(id=exp_id, hypothesis=hypothesis or "(write it before you run)",
                                               parent=parent or "nothing (first run)", change=change,
                                               setup=_setup_lines(train_meta)))
    build_index(experiments_dir)
    return dest


def _intervals_overlap(a: list[float], b: list[float]) -> bool:
    return a[0] <= b[1] and b[0] <= a[1]


def build_index(experiments_dir: Path) -> Path:
    records = []
    for path in sorted(experiments_dir.glob("*/experiment.json")):
        rec = json.loads(path.read_text())
        rec["_result"] = _load(path.parent / "results" / f"{rec['headline']}.json")
        records.append(rec)
    records.sort(key=lambda r: r["date"])
    by_id = {r["id"]: r for r in records}

    lines = ["# Experiments", "",
             "Generated by `python experiments.py index`; do not edit by hand. Notes live in each `NOTES.md`.", "",
             "| experiment | change | n | macro-F1 (95% CI) | vs parent | type F1 (commented hunks) | "
             "flag bal. acc. (length-matched) | valid JSON | data | code |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for rec in records:
        res = rec["_result"]
        if not res:
            lines.append(f"| {rec['id']} | {rec['change']} | - | missing headline result | | | | | | |")
            continue
        low, high = res["macro_f1_ci95"]
        parent = by_id.get(rec.get("parent") or "")
        versus = "-"
        if parent and parent["_result"]:
            pres = parent["_result"]
            delta = res["macro_f1"] - pres["macro_f1"]
            verdict = "CIs overlap" if _intervals_overlap(res["macro_f1_ci95"], pres["macro_f1_ci95"]) else "CIs separate"
            versus = f"{delta:+.3f} ({verdict})"
            if res.get("split_sha") != pres.get("split_sha"):
                versus += " ⚠ different eval data"
        matched = (res.get("flag_length_matched") or {}).get("balanced_accuracy")
        git = rec.get("git") or {}
        code = (git.get("commit") or "?") + ("*" if git.get("dirty") else "")
        limit = f" (limit {res['limit']})" if res.get("limit") else ""
        lines.append(
            f"| {rec['id']} | {rec['change']} | {res['n']}{limit} | {res['macro_f1']:.3f} ({low:.3f}-{high:.3f}) | "
            f"{versus} | {res.get('type_macro_f1_positives', float('nan')):.3f} | "
            f"{'n/a' if matched is None else f'{matched:.2f}'} | {res['valid_rate']:.1%} | "
            f"{res.get('split_sha') or '?'} | {code} |")
    lines += ["", "`*` after a commit means the working tree had uncommitted changes when the run was recorded. "
              "'CIs overlap' means the difference is not distinguishable from noise on this test set."]
    out = experiments_dir / "INDEX.md"
    out.write_text("\n".join(lines) + "\n")
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("record")
    r.add_argument("--id", required=True)
    r.add_argument("--results-dir", required=True)
    r.add_argument("--train-dir")
    r.add_argument("--data-dir", default="data")
    r.add_argument("--hypothesis", required=True)
    r.add_argument("--change", required=True)
    r.add_argument("--parent")
    r.add_argument("--headline", help="result name to show in INDEX (default: first tuned run)")
    r.add_argument("--with-predictions", action="store_true", help="also copy predictions.jsonl (larger files)")
    r.add_argument("--force", action="store_true")
    for p in (r, sub.add_parser("index")):
        p.add_argument("--experiments-dir", default="experiments")
    args = parser.parse_args()
    root = Path(args.experiments_dir)
    if args.command == "index":
        print(build_index(root))
        return
    dest = record(root, args.id, results_dir=Path(args.results_dir),
                  train_dir=Path(args.train_dir) if args.train_dir else None, data_dir=Path(args.data_dir),
                  hypothesis=args.hypothesis, change=args.change, parent=args.parent,
                  with_predictions=args.with_predictions, force=args.force, headline=args.headline)
    print(f"recorded {dest}; edit {dest / 'NOTES.md'}, then commit experiments/")


if __name__ == "__main__":
    main()
