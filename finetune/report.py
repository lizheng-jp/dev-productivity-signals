"""Print a markdown comparison table from results/*.json.   python report.py [results-dir]"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def render(results: list[dict]) -> str:
    lines = ["| run | n | valid JSON | macro-F1 (95% CI) | type F1, commented hunks only | flag P / R | "
             "flag bal. acc. (all / length-matched) | ROUGE-L |",
             "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in results:
        low, high = r["macro_f1_ci95"]
        matched = r.get("flag_length_matched") or {}
        matched_ba = f"{matched['balanced_accuracy']:.2f}" if "balanced_accuracy" in matched else "n/a"
        lines.append(
            f"| {r['name']} | {r['n']} | {r['valid_rate']:.1%} | {r['macro_f1']:.3f} ({low:.3f}-{high:.3f}) | "
            f"{r.get('type_macro_f1_positives', float('nan')):.3f} | "
            f"{r['flag']['precision']:.2f} / {r['flag']['recall']:.2f} | "
            f"{r.get('flag_balanced_accuracy', float('nan')):.2f} / {matched_ba} | "
            f"{r['rouge_l_on_positives']:.3f} |")
    return "\n".join(lines)


if __name__ == "__main__":
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else "results")
    runs = [json.loads(p.read_text()) for p in sorted(directory.glob("*.json"))
            if p.name != "judge.json" and "macro_f1_ci95" in json.loads(p.read_text())]
    print(render(runs))
