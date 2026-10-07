"""Audit and build SFT data from ronantakizawa/github-codereview.

    python prepare_data.py audit --out data/audit.json
    python prepare_data.py build --out-dir data --train-size 30000

`audit` looks for ways a model could score well without learning the task (for example negatives that
have no diff). Read its output before training. `build` writes chat-format jsonl files plus meta.json.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from codereview_ft.formatting import (NO_ISSUES_COMMENT, NONE_LABEL, build_system_prompt,
                                      build_target, build_user_prompt)
from codereview_ft.metrics import best_length_threshold, length_bin

DATASET = "ronantakizawa/github-codereview"


@dataclass
class BuildConfig:
    train_size: int = 30000
    val_size: int = 500
    test_size: int = 0                # 0 = use every clean test example (rare classes need the support)
    negative_share: float = 0.15      # share of negatives in the *training* sample
    match_negative_length: bool = True  # resample train negatives to mirror the positives' diff-length mix
    min_quality: float = 0.5          # applies to positive examples only
    min_comment_chars: int = 15
    max_comment_chars: int = 1000
    # Diffs at or above this length are dropped. The dataset's negatives pile up at exactly 2000 chars (a hard cap),
    # which only one class hits, so rows at the cap are removed rather than kept as a label tell.
    max_diff_chars: int = 2000
    with_before_code: bool = False
    max_before_chars: int = 2000
    type_first: bool = True
    seed: int = 0


def _quantiles(values: list[float]) -> dict[str, float]:
    if not values:
        return {}
    ordered = sorted(values)
    pick = lambda q: ordered[min(len(ordered) - 1, int(q * len(ordered)))]
    return {"min": ordered[0], "p25": pick(0.25), "p50": pick(0.5), "p75": pick(0.75),
            "p95": pick(0.95), "max": ordered[-1]}


def dedup_key(diff: str, comment: str) -> str:
    return hashlib.sha1(f"{diff.strip()}\0{comment.strip()}".encode()).hexdigest()


# --------------------------------------------------------------------------- audit

def diff_markers(text: str) -> tuple[bool, bool]:
    """(has a '@@' hunk header line, has any line starting with + or -) - cheap 'is this a diff?' signals."""
    lines = text.splitlines()
    return (any(line.startswith("@@") for line in lines),
            any(line[:1] in ("+", "-") for line in lines))


_HUNK = re.compile(r"^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@")


def last_hunk_incomplete(text: str) -> bool | None:
    """True if the final hunk shows fewer lines than its header declares (i.e. the diff was cut short).

    None when there is no hunk header. Approximate: a stripped trailing blank context line counts as cut.
    """
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if _HUNK.match(line)]
    if not starts:
        return None
    match = _HUNK.match(lines[starts[-1]])
    old_declared = int(match.group(1)) if match.group(1) is not None else 1
    new_declared = int(match.group(2)) if match.group(2) is not None else 1
    old = new = 0
    for line in lines[starts[-1] + 1:]:
        if line.startswith("\\"):  # "\ No newline at end of file"
            continue
        if line[:1] == "+":
            new += 1
        elif line[:1] == "-":
            old += 1
        else:
            old += 1
            new += 1
    return old < old_declared or new < new_declared


def sample_dump(rows: list[dict], per_class: int = 3, seed: int = 0, chars: int = 700) -> str:
    """Human-readable examples (positives and negatives) to eyeball what the model will actually see."""
    rng = random.Random(seed)
    out = []
    for negative in (False, True):
        group = [r for r in rows if bool(r.get("is_negative")) == negative]
        for r in rng.sample(group, min(per_class, len(group))):
            out.append(f"===== {'NEGATIVE' if negative else 'POSITIVE'} | type={r.get('comment_type')} | "
                       f"quality={r.get('quality_score')} | {r.get('repo_name')} {r.get('file_path')}\n"
                       f"--- diff_context ({len(r.get('diff_context') or '')} chars) ---\n"
                       f"{(r.get('diff_context') or '')[:chars]}\n"
                       f"--- reviewer_comment ---\n{(r.get('reviewer_comment') or '')[:300]}\n")
    return "\n".join(out)


def audit(splits: dict[str, list[dict]]) -> dict:
    report: dict = {"splits": {}, "warnings": []}
    repos: dict[str, set[str]] = {}
    keys: dict[str, set[str]] = {}
    for name, rows in splits.items():
        by_neg: dict[bool, list[dict]] = {True: [], False: []}
        for row in rows:
            by_neg[bool(row.get("is_negative"))].append(row)
        types = Counter(str(r.get("comment_type") or "").strip().lower() for r in rows)
        info = {
            "rows": len(rows),
            "negative_share": len(by_neg[True]) / len(rows) if rows else 0.0,
            "comment_type": dict(types.most_common()),
            "negatives_with_nonnone_type": sum(1 for r in by_neg[True]
                                               if str(r.get("comment_type") or "").lower() not in ("", NONE_LABEL)),
            "positives_with_none_type": sum(1 for r in by_neg[False]
                                            if str(r.get("comment_type") or "").lower() in ("", NONE_LABEL)),
            "quality_score_positive": _quantiles([float(r["quality_score"]) for r in by_neg[False]
                                                  if r.get("quality_score") is not None]),
            "comment_chars_positive": _quantiles([len(r.get("reviewer_comment") or "") for r in by_neg[False]]),
        }
        for neg in (False, True):
            group = by_neg[neg]
            tag = "negative" if neg else "positive"
            info[f"empty_diff_rate_{tag}"] = (sum(1 for r in group if not (r.get("diff_context") or "").strip())
                                              / len(group)) if group else None
            info[f"diff_chars_{tag}"] = _quantiles([len(r.get("diff_context") or "") for r in group])
        for neg in (False, True):
            group = by_neg[neg]
            tag = "negative" if neg else "positive"
            marks = [diff_markers(r.get("diff_context") or "") for r in group]
            info[f"hunk_header_rate_{tag}"] = sum(m[0] for m in marks) / len(marks) if marks else None
            info[f"plus_minus_line_rate_{tag}"] = sum(m[1] for m in marks) / len(marks) if marks else None
        for neg in (False, True):
            group = by_neg[neg]
            tag = "negative" if neg else "positive"
            lengths = Counter(len(r.get("diff_context") or "") for r in group)
            if lengths:
                value, count = lengths.most_common(1)[0]
                info[f"modal_diff_length_{tag}"] = {"chars": value, "share": count / len(group)}
            flags = [last_hunk_incomplete(r.get("diff_context") or "") for r in group]
            flags = [f for f in flags if f is not None]
            info[f"last_hunk_incomplete_rate_{tag}"] = sum(flags) / len(flags) if flags else None
        modal_pos, modal_neg = info.get("modal_diff_length_positive"), info.get("modal_diff_length_negative")
        if modal_pos and modal_neg and abs(modal_pos["share"] - modal_neg["share"]) > 0.1:
            report["warnings"].append(
                f"[{name}] {modal_neg['share']:.0%} of negatives vs {modal_pos['share']:.0%} of positives have exactly "
                f"{modal_neg['chars']} chars: a hard truncation cap that only one class hits.")
        inc_pos, inc_neg = info["last_hunk_incomplete_rate_positive"], info["last_hunk_incomplete_rate_negative"]
        if inc_pos is not None and inc_neg is not None and abs(inc_pos - inc_neg) > 0.2:
            report["warnings"].append(
                f"[{name}] share of diffs whose last hunk is cut short differs by class "
                f"(positive {inc_pos:.0%}, negative {inc_neg:.0%}).")
        threshold, direction, balanced = best_length_threshold(
            [len(r.get("diff_context") or "") for r in rows], [bool(r.get("is_negative")) for r in rows])
        # 0.5 = length is useless, 1.0 = length alone separates comment/no-comment perfectly.
        info["length_only_flag_balanced_accuracy"] = {"threshold_chars": threshold, "direction": direction,
                                                     "balanced_accuracy": balanced}
        report["splits"][name] = info
        repos[name] = {r.get("repo_name") or "" for r in rows}
        keys[name] = {dedup_key(r.get("diff_context") or "", r.get("reviewer_comment") or "") for r in rows}

        pos_empty, neg_empty = info["empty_diff_rate_positive"], info["empty_diff_rate_negative"]
        if pos_empty is not None and neg_empty is not None and abs(pos_empty - neg_empty) > 0.05:
            report["warnings"].append(
                f"[{name}] empty diff_context rate differs by class (positive {pos_empty:.1%}, negative {neg_empty:.1%}). "
                "A model could separate classes from input format alone; negatives with empty diffs are dropped by `build`.")
        pos_med = info["diff_chars_positive"].get("p50")
        neg_med = info["diff_chars_negative"].get("p50")
        if pos_med and neg_med and max(pos_med, neg_med) / max(1, min(pos_med, neg_med)) > 2:
            report["warnings"].append(
                f"[{name}] median diff length differs >2x between classes ({pos_med} vs {neg_med} chars): length is a shortcut.")
        for mark in ("hunk_header_rate", "plus_minus_line_rate"):
            a, b = info[f"{mark}_positive"], info[f"{mark}_negative"]
            if a is not None and b is not None and abs(a - b) > 0.2:
                report["warnings"].append(
                    f"[{name}] {mark} differs by class (positive {a:.1%}, negative {b:.1%}): "
                    "negatives may not be diffs at all, so the input format leaks the label.")
        if balanced > 0.8:
            report["warnings"].append(
                f"[{name}] a single diff-length threshold ({direction}, {threshold:.0f} chars) predicts "
                f"comment/no-comment with balanced accuracy {balanced:.2f}: report length-matched metrics.")
        if info["negatives_with_nonnone_type"] or info["positives_with_none_type"]:
            report["warnings"].append(f"[{name}] label/is_negative inconsistencies found; inconsistent rows are dropped.")

    names = list(splits)
    report["overlap"] = {}
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            report["overlap"][f"{a}~{b}"] = {"shared_repos": len(repos[a] & repos[b]),
                                             "shared_diff_comment_pairs": len(keys[a] & keys[b])}
            if report["overlap"][f"{a}~{b}"]["shared_repos"]:
                report["warnings"].append(f"Splits {a} and {b} share repositories; `build` removes them from train.")
    return report


# --------------------------------------------------------------------------- build

def clean_row(row: dict, cfg: BuildConfig) -> tuple[dict | None, str | None]:
    """Return (record, None) or (None, drop_reason)."""
    diff = (row.get("diff_context") or "").strip("\n")
    if not diff.strip():
        return None, "empty_diff"
    if len(diff) >= cfg.max_diff_chars:
        return None, "diff_at_or_over_cap"
    ctype = str(row.get("comment_type") or "").strip().lower()
    if row.get("is_negative"):
        if ctype not in ("", NONE_LABEL):
            return None, "inconsistent_negative"
        ctype, comment = NONE_LABEL, NO_ISSUES_COMMENT
    else:
        comment = (row.get("reviewer_comment") or "").strip()
        if ctype in ("", NONE_LABEL):
            return None, "positive_without_type"
        if not cfg.min_comment_chars <= len(comment) <= cfg.max_comment_chars:
            return None, "comment_length"
        quality = row.get("quality_score")
        if quality is None or float(quality) < cfg.min_quality:
            return None, "low_quality"
    before = (row.get("before_code") or "")[: cfg.max_before_chars] if cfg.with_before_code else ""
    return {
        "type": ctype, "comment": comment, "diff": diff, "before_code": before,
        "language": row.get("language") or row.get("repo_language") or "unknown",
        "file_path": row.get("file_path") or "", "pr_title": row.get("pr_title") or "",
        "repo": row.get("repo_name") or "", "pr_number": row.get("pr_number"),
        "comment_line": row.get("comment_line"), "quality": row.get("quality_score"),
        "key": dedup_key(diff, comment),
    }, None


def equal_quota(sizes: dict[str, int], total: int) -> dict[str, int]:
    """Spread `total` over classes as evenly as availability allows (small classes keep everything)."""
    quota = {k: 0 for k in sizes}
    active = [k for k, size in sizes.items() if size > 0]
    remaining = total
    while remaining > 0 and active:
        share = max(1, remaining // len(active))
        progressed = False
        for k in list(active):
            take = min(share, sizes[k] - quota[k], remaining)
            if take > 0:
                quota[k] += take
                remaining -= take
                progressed = True
            if quota[k] >= sizes[k]:
                active.remove(k)
            if remaining == 0:
                break
        if not progressed:
            break
    return quota


def length_matched_negatives(negatives: list[dict], positives: list[dict], target: int,
                             rng: random.Random) -> list[dict]:
    """Pick negatives whose diff-length mix mirrors `positives`, as far as availability allows.

    Short negatives are scarce, so this may return fewer than `target`; the caller reports the shortfall.
    """
    pos_bins = Counter(length_bin(len(r["diff"])) for r in positives)
    total = sum(pos_bins.values())
    by_bin: dict[int, list[dict]] = defaultdict(list)
    for r in negatives:
        by_bin[length_bin(len(r["diff"]))].append(r)
    chosen: list[dict] = []
    for b, count in pos_bins.items():
        want = round(target * count / total)
        pool = by_bin.get(b, [])
        chosen += rng.sample(pool, min(want, len(pool)))
    return chosen


def _clean_split(rows: Iterable[dict], cfg: BuildConfig) -> tuple[list[dict], Counter]:
    kept, dropped, seen = [], Counter(), set()
    for row in rows:
        record, reason = clean_row(row, cfg)
        if record is None:
            dropped[reason] += 1
        elif record["key"] in seen:
            dropped["duplicate"] += 1
        else:
            seen.add(record["key"])
            kept.append(record)
    return kept, dropped


def _to_example(record: dict, labels: list[str], cfg: BuildConfig) -> dict:
    user = build_user_prompt(language=record["language"], file_path=record["file_path"],
                             pr_title=record["pr_title"], diff=record["diff"],
                             before_code=record["before_code"])
    sha = hashlib.sha1(f"{record['repo']}|{record['pr_number']}|{record['file_path']}|{record['key']}".encode())
    return {
        "id": sha.hexdigest()[:12],
        "messages": [{"role": "system", "content": build_system_prompt(labels)},
                     {"role": "user", "content": user},
                     {"role": "assistant", "content": build_target(record["type"], record["comment"], cfg.type_first)}],
        "meta": {**{k: record[k] for k in ("type", "comment", "repo", "pr_number", "language", "quality")},
                 "diff_chars": len(record["diff"])},
    }


def build(splits: dict[str, list[dict]], cfg: BuildConfig) -> tuple[dict[str, list[dict]], dict]:
    rng = random.Random(cfg.seed)
    cleaned, drops = {}, {}
    for name in ("train", "validation", "test"):
        cleaned[name], drops[name] = _clean_split(splits.get(name, []), cfg)

    eval_repos = {r["repo"] for n in ("validation", "test") for r in cleaned[n]}
    eval_keys = {r["key"] for n in ("validation", "test") for r in cleaned[n]}
    before = len(cleaned["train"])
    cleaned["train"] = [r for r in cleaned["train"] if r["repo"] not in eval_repos and r["key"] not in eval_keys]
    drops["train"]["eval_repo_or_pair_overlap"] = before - len(cleaned["train"])

    by_type: dict[str, list[dict]] = defaultdict(list)
    for record in cleaned["train"]:
        by_type[record["type"]].append(record)
    positives = {t: rs for t, rs in by_type.items() if t != NONE_LABEL}
    negatives = by_type.get(NONE_LABEL, [])
    neg_target = min(len(negatives), round(cfg.train_size * cfg.negative_share))
    quota = equal_quota({t: len(rs) for t, rs in positives.items()}, cfg.train_size - neg_target)
    train = []
    for t, rs in positives.items():
        train += rng.sample(rs, quota[t])
    chosen_negatives = (length_matched_negatives(negatives, train, neg_target, rng)
                        if cfg.match_negative_length else rng.sample(negatives, neg_target))
    train += chosen_negatives
    rng.shuffle(train)

    def natural(split: str, size: int) -> list[dict]:
        rows = cleaned[split]
        return list(rows) if size <= 0 else rng.sample(rows, min(size, len(rows)))

    chosen = {"train": train, "validation": natural("validation", cfg.val_size),
              "test": natural("test", cfg.test_size)}
    labels = sorted({r["type"] for rs in chosen.values() for r in rs} | {NONE_LABEL})
    outputs = {name: [_to_example(r, labels, cfg) for r in rs] for name, rs in chosen.items()}
    meta = {
        "labels": labels, "config": asdict(cfg),
        "rows": {n: len(v) for n, v in outputs.items()},
        "class_counts": {n: dict(Counter(r["type"] for r in rs).most_common()) for n, rs in chosen.items()},
        "dropped": {n: dict(c) for n, c in drops.items()},
        "repos": {n: len({r["repo"] for r in rs}) for n, rs in chosen.items()},
        "warnings": [],
    }
    achieved = len(chosen_negatives) / max(1, len(train))
    meta["train_negative_share_achieved"] = achieved
    if cfg.match_negative_length and achieved < 0.5 * cfg.negative_share:
        meta["warnings"].append(
            f"Length matching left only {achieved:.1%} negatives in train (target {cfg.negative_share:.0%}) because short "
            "negatives are scarce. Check class_counts; the model may under-predict 'none'.")
    if len(negatives) < 0.01 * max(1, len(cleaned["train"])):
        meta["warnings"].append("Almost no negative (type=none) training examples survived cleaning. "
                                "Run `audit`: negatives probably lack diff_context. The model will never learn to say 'none'.")
    return outputs, meta


# --------------------------------------------------------------------------- CLI

def _load_splits(revision: str | None, with_before_code: bool) -> tuple[dict[str, list[dict]], str | None]:
    from datasets import load_dataset  # imported lazily so the pure functions stay testable offline
    dataset = load_dataset(DATASET, revision=revision)
    aliases = {"train": ("train",), "validation": ("validation", "val"), "test": ("test",)}
    drop = ["after_code"] + ([] if with_before_code else ["before_code"])
    splits = {}
    for name, candidates in aliases.items():
        source = next((c for c in candidates if c in dataset), None)
        if source is None:
            raise SystemExit(f"Split {name!r} not found; available: {list(dataset)}")
        part = dataset[source]
        # after_code contains the post-review fix, which is not available at review time.
        part = part.remove_columns([c for c in drop if c in part.column_names])
        splits[name] = [dict(row) for row in part]
    sha = None
    try:
        from huggingface_hub import HfApi
        sha = HfApi().dataset_info(DATASET, revision=revision).sha
    except Exception:
        pass
    return splits, sha


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    a = sub.add_parser("audit")
    a.add_argument("--out", default="data/audit.json")
    a.add_argument("--revision")
    b = sub.add_parser("build")
    b.add_argument("--out-dir", default="data")
    b.add_argument("--revision")
    defaults = BuildConfig()
    for field, value in asdict(defaults).items():
        flag = "--" + field.replace("_", "-")
        if isinstance(value, bool):
            b.add_argument(flag, action=argparse.BooleanOptionalAction, default=value)
        else:
            b.add_argument(flag, type=type(value), default=value)
    args = parser.parse_args()

    if args.command == "audit":
        splits, sha = _load_splits(args.revision, with_before_code=True)
        report = audit(splits)
        report["dataset_revision"] = sha
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(report, indent=2, ensure_ascii=False))
        samples = Path(args.out).with_name("audit_samples.txt")
        samples.write_text("\n\n######## TRAIN ########\n" + sample_dump(splits["train"]) +
                           "\n\n######## TEST ########\n" + sample_dump(splits["test"]))
        print(json.dumps(report, indent=2, ensure_ascii=False))
        print(f"\nExamples to eyeball: {samples}")
        print("\nWARNINGS:" if report["warnings"] else "\nNo warnings.")
        for warning in report["warnings"]:
            print(" -", warning)
        return

    cfg = BuildConfig(**{k: getattr(args, k) for k in asdict(defaults)})
    splits, sha = _load_splits(args.revision, cfg.with_before_code)
    outputs, meta = build(splits, cfg)
    meta["dataset_revision"] = sha
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for name, examples in outputs.items():
        with (out / f"{name}.jsonl").open("w") as handle:
            for example in examples:
                handle.write(json.dumps(example, ensure_ascii=False) + "\n")
    (out / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False))
    print(json.dumps(meta, indent=2, ensure_ascii=False))
    for warning in meta["warnings"]:
        print("WARNING:", warning)


if __name__ == "__main__":
    main()
