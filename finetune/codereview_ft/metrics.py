"""Evaluation metrics, implemented in the standard library so they are easy to audit and test."""
from __future__ import annotations

import random
import re
from collections import Counter, defaultdict
from typing import Callable, Sequence

from .formatting import INVALID, NONE_LABEL


def confusion_matrix(gold: Sequence[str], pred: Sequence[str], labels: list[str]) -> dict[str, dict[str, int]]:
    columns = labels + [INVALID]
    matrix = {g: {p: 0 for p in columns} for g in labels}
    for g, p in zip(gold, pred):
        matrix[g][p if p in labels else INVALID] += 1
    return matrix


def per_class(gold: Sequence[str], pred: Sequence[str], labels: list[str]) -> dict[str, dict[str, float]]:
    result = {}
    for label in labels:
        tp = sum(1 for g, p in zip(gold, pred) if g == label and p == label)
        fp = sum(1 for g, p in zip(gold, pred) if g != label and p == label)
        fn = sum(1 for g, p in zip(gold, pred) if g == label and p != label)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        result[label] = {"precision": precision, "recall": recall, "f1": f1, "support": tp + fn}
    return result


def accuracy(gold: Sequence[str], pred: Sequence[str]) -> float:
    return sum(g == p for g, p in zip(gold, pred)) / len(gold) if gold else 0.0


def macro_f1(gold: Sequence[str], pred: Sequence[str], labels: list[str]) -> float:
    """Macro-F1 over labels that occur in the gold data (classes with no support are ignored).

    Unparseable predictions never equal a gold label, so they lower recall but not precision.
    """
    stats = per_class(gold, pred, labels)
    present = [stats[label]["f1"] for label in labels if stats[label]["support"] > 0]
    return sum(present) / len(present) if present else 0.0


def flag_metrics(gold: Sequence[str], pred: Sequence[str]) -> dict[str, float]:
    """Binary 'does this hunk deserve a comment' decision: positive = any type other than none."""
    tp = sum(1 for g, p in zip(gold, pred) if g != NONE_LABEL and p not in (NONE_LABEL, INVALID))
    fp = sum(1 for g, p in zip(gold, pred) if g == NONE_LABEL and p not in (NONE_LABEL, INVALID))
    fn = sum(1 for g, p in zip(gold, pred) if g != NONE_LABEL and p in (NONE_LABEL, INVALID))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"precision": precision, "recall": recall, "f1": f1}


_TOKEN = re.compile(r"[A-Za-z0-9_]+")


def rouge_l_f1(reference: str, hypothesis: str, max_tokens: int = 200) -> float:
    ref = _TOKEN.findall(reference.lower())[:max_tokens]
    hyp = _TOKEN.findall(hypothesis.lower())[:max_tokens]
    if not ref or not hyp:
        return 0.0
    previous = [0] * (len(hyp) + 1)
    for r in ref:
        current = [0]
        for j, h in enumerate(hyp, 1):
            current.append(previous[j - 1] + 1 if r == h else max(previous[j], current[j - 1]))
        previous = current
    lcs = previous[-1]
    if not lcs:
        return 0.0
    precision, recall = lcs / len(hyp), lcs / len(ref)
    return 2 * precision * recall / (precision + recall)


def bootstrap_ci(metric: Callable[[list[int]], float], n_items: int, rounds: int = 1000,
                 seed: int = 0, alpha: float = 0.05) -> tuple[float, float]:
    """Percentile bootstrap over example indices; `metric` receives a resampled index list."""
    rng = random.Random(seed)
    values = sorted(metric([rng.randrange(n_items) for _ in range(n_items)]) for _ in range(rounds))
    return values[int(rounds * alpha / 2)], values[min(rounds - 1, int(rounds * (1 - alpha / 2)))]


def majority_predictions(train_labels: Sequence[str], n: int) -> list[str]:
    label = Counter(train_labels).most_common(1)[0][0]
    return [label] * n


# ---- length-shortcut tools --------------------------------------------------------------
# In github-codereview, "no issue" examples are long, truncated code chunks while commented hunks are short,
# so diff length alone separates the classes. These helpers measure and control for that.
LENGTH_EDGES = (0, 300, 600, 900, 1200, 1600, 2001, 3000, 4500)


def length_bin(chars: int) -> int:
    for i in range(len(LENGTH_EDGES) - 1, -1, -1):
        if chars >= LENGTH_EDGES[i]:
            return i
    return 0


def length_matched_indices(diff_chars: Sequence[int], is_negative: Sequence[bool], seed: int = 0) -> list[int]:
    """Subsample so every length bin holds equally many positives and negatives (length carries no signal)."""
    rng = random.Random(seed)
    groups: dict[int, tuple[list[int], list[int]]] = defaultdict(lambda: ([], []))
    for i, (chars, negative) in enumerate(zip(diff_chars, is_negative)):
        groups[length_bin(chars)][1 if negative else 0].append(i)
    chosen: list[int] = []
    for key in sorted(groups):
        positives, negatives = groups[key]
        k = min(len(positives), len(negatives))
        chosen += rng.sample(positives, k) + rng.sample(negatives, k)
    return sorted(chosen)


def balanced_accuracy(truth: Sequence[bool], pred: Sequence[bool]) -> float:
    """Mean of true-positive and true-negative rate; 0.0 if either class is absent."""
    pos = [p for t, p in zip(truth, pred) if t]
    neg = [p for t, p in zip(truth, pred) if not t]
    if not pos or not neg:
        return 0.0
    return (sum(pos) / len(pos) + sum(not p for p in neg) / len(neg)) / 2


def best_length_threshold(chars: Sequence[int], is_negative: Sequence[bool]) -> tuple[float, str, float]:
    """Best single diff-length cut for 'negative vs positive': (threshold, direction, balanced accuracy).

    direction 'longer_is_negative' predicts negative when chars > threshold.
    """
    n_neg = sum(is_negative)
    n_pos = len(is_negative) - n_neg
    best = (0.0, "longer_is_negative", 0.0)
    if not n_neg or not n_pos:
        return best
    pairs = sorted(zip(chars, is_negative))
    neg_seen = pos_seen = 0
    i = 0
    while i < len(pairs):
        value, j = pairs[i][0], i
        while j < len(pairs) and pairs[j][0] == value:
            neg_seen += pairs[j][1]
            pos_seen += not pairs[j][1]
            j += 1
        ba = ((n_neg - neg_seen) / n_neg + pos_seen / n_pos) / 2
        if ba > best[2]:
            best = (float(value), "longer_is_negative", ba)
        if 1 - ba > best[2]:
            best = (float(value), "shorter_is_negative", 1 - ba)
        i = j
    return best


def length_baseline_predictions(chars: Sequence[int], threshold: float, direction: str,
                                positive_type: str) -> list[str]:
    longer = direction == "longer_is_negative"
    return [NONE_LABEL if ((c > threshold) == longer) else positive_type for c in chars]


def summarize(gold_types: list[str], gold_comments: list[str], pred_types: list[str],
              pred_comments: list[str], valid: list[bool], labels: list[str],
              bootstrap_rounds: int = 1000, diff_chars: list[int] | None = None) -> dict:
    n = len(gold_types)
    pred_for_scoring = [p if v else INVALID for p, v in zip(pred_types, valid)]
    positives = [i for i, g in enumerate(gold_types) if g != NONE_LABEL]

    def comment_score(i: int) -> float:
        flagged = valid[i] and pred_types[i] != NONE_LABEL
        return rouge_l_f1(gold_comments[i], pred_comments[i]) if flagged else 0.0

    def resampled_macro(idx: list[int]) -> float:
        return macro_f1([gold_types[i] for i in idx], [pred_for_scoring[i] for i in idx], labels)

    low, high = bootstrap_ci(resampled_macro, n, bootstrap_rounds) if n else (0.0, 0.0)
    type_labels = [label for label in labels if label != NONE_LABEL]
    flagged = [p not in (NONE_LABEL, INVALID) for p in pred_for_scoring]
    is_negative = [g == NONE_LABEL for g in gold_types]
    matched: dict = {}
    if diff_chars is not None:
        idx = length_matched_indices(diff_chars, is_negative)
        matched = {"n": len(idx),
                   "balanced_accuracy": balanced_accuracy([not is_negative[i] for i in idx],
                                                          [flagged[i] for i in idx]) if idx else 0.0}
    return {
        "n": n,
        "valid_rate": sum(valid) / n if n else 0.0,
        "accuracy": accuracy(gold_types, pred_for_scoring),
        "macro_f1": macro_f1(gold_types, pred_for_scoring, labels),
        "macro_f1_ci95": [low, high],
        "per_class": per_class(gold_types, pred_for_scoring, labels),
        "confusion": confusion_matrix(gold_types, pred_for_scoring, labels),
        "flag": flag_metrics(gold_types, pred_for_scoring),
        # Type macro-F1 over hunks the reviewer commented on (a 'none' or invalid answer counts as wrong).
        "type_macro_f1_positives": macro_f1([gold_types[i] for i in positives],
                                            [pred_for_scoring[i] for i in positives], type_labels),
        "flag_balanced_accuracy": balanced_accuracy([not x for x in is_negative], flagged) if n else 0.0,
        # Same decision on a length-matched subsample, where diff length cannot help.
        "flag_length_matched": matched,
        # ROUGE-L against the reviewer's comment, over hunks where the reviewer commented.
        # A hunk the model leaves unflagged scores 0. This is a weak proxy: many valid comments differ in wording.
        "rouge_l_on_positives": (sum(comment_score(i) for i in positives) / len(positives)
                                 if positives else 0.0),
    }
