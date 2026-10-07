"""Evaluate a model (base, base+LoRA adapter) or the majority-class baseline on the held-out test split.

    python evaluate.py --name majority --baseline majority
    python evaluate.py --name length   --baseline length
    python evaluate.py --name base  --model Qwen/Qwen2.5-Coder-1.5B-Instruct
    python evaluate.py --name tuned --model Qwen/Qwen2.5-Coder-1.5B-Instruct --adapter runs/qwen15b/adapter

Writes results/<name>.json (metrics) and results/<name>.predictions.jsonl (raw outputs for error analysis).
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from codereview_ft.formatting import INVALID, parse_prediction
from collections import Counter

from codereview_ft.formatting import NONE_LABEL
from codereview_ft.runinfo import file_sha, git_info
from codereview_ft.metrics import (best_length_threshold, length_baseline_predictions,
                                   majority_predictions, summarize)


def load_jsonl(path: Path) -> list[dict]:
    with path.open() as handle:
        return [json.loads(line) for line in handle if line.strip()]


def generate(model, tokenizer, prompts: list[str], batch_size: int, max_new_tokens: int) -> list[str]:
    import torch
    tokenizer.padding_side = "left"
    order = sorted(range(len(prompts)), key=lambda i: len(prompts[i]))  # similar lengths per batch
    outputs: list[str] = [""] * len(prompts)
    for start in range(0, len(order), batch_size):
        idx = order[start:start + batch_size]
        batch = tokenizer([prompts[i] for i in idx], return_tensors="pt", padding=True,
                          add_special_tokens=False).to(model.device)
        with torch.inference_mode():
            generated = model.generate(**batch, max_new_tokens=max_new_tokens, do_sample=False,
                                       pad_token_id=tokenizer.pad_token_id)
        texts = tokenizer.batch_decode(generated[:, batch["input_ids"].shape[1]:], skip_special_tokens=True)
        for i, text in zip(idx, texts):
            outputs[i] = text
        print(f"  {min(start + batch_size, len(order))}/{len(order)}", flush=True)
    return outputs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", required=True)
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--split", default="test")
    parser.add_argument("--baseline", choices=["majority", "length"])
    parser.add_argument("--model", default="Qwen/Qwen2.5-Coder-1.5B-Instruct")
    parser.add_argument("--adapter")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--results-dir", default="results")
    args = parser.parse_args()

    data_dir = Path(args.data_dir)
    meta = json.loads((data_dir / "meta.json").read_text())
    labels = meta["labels"]
    rows = load_jsonl(data_dir / f"{args.split}.jsonl")[: args.limit]
    gold_types = [r["meta"]["type"] for r in rows]
    gold_comments = [r["meta"]["comment"] for r in rows]
    diff_chars = [r["meta"].get("diff_chars") for r in rows]
    diff_chars = diff_chars if all(c is not None for c in diff_chars) else None

    started = time.monotonic()
    if args.baseline in ("majority", "length"):
        train_rows = load_jsonl(data_dir / "train.jsonl")
        train_types = [r["meta"]["type"] for r in train_rows]
        if args.baseline == "majority":
            pred_types = majority_predictions(train_types, len(rows))
        else:
            # Predicts only from diff length: 'none' on one side of a threshold, else the commonest comment type.
            # If this scores well on the flag decision, diff length (not code understanding) is doing the work.
            threshold, direction, _ = best_length_threshold(
                [r["meta"]["diff_chars"] for r in train_rows], [t == NONE_LABEL for t in train_types])
            commonest = Counter(t for t in train_types if t != NONE_LABEL).most_common(1)[0][0]
            pred_types = length_baseline_predictions(diff_chars, threshold, direction, commonest)
        pred_comments = [""] * len(rows)
        valid = [True] * len(rows)
        raw = [""] * len(rows)
    else:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        tokenizer = AutoTokenizer.from_pretrained(args.adapter or args.model)
        if tokenizer.pad_token is None:
            tokenizer.pad_token = tokenizer.eos_token
        try:
            model = AutoModelForCausalLM.from_pretrained(args.model, dtype=torch.bfloat16, device_map="auto")
        except TypeError:
            model = AutoModelForCausalLM.from_pretrained(args.model, torch_dtype=torch.bfloat16, device_map="auto")
        if args.adapter:
            from peft import PeftModel
            model = PeftModel.from_pretrained(model, args.adapter)
        model.eval()
        prompts = [tokenizer.apply_chat_template(r["messages"][:-1], tokenize=False, add_generation_prompt=True)
                   for r in rows]
        raw = generate(model, tokenizer, prompts, args.batch_size, args.max_new_tokens)
        parsed = [parse_prediction(text, labels) for text in raw]
        pred_types = [p.type or INVALID for p in parsed]
        pred_comments = [p.comment for p in parsed]
        valid = [p.valid for p in parsed]
    elapsed = time.monotonic() - started

    metrics = summarize(gold_types, gold_comments, pred_types, pred_comments, valid, labels,
                        diff_chars=diff_chars)
    metrics.update({"name": args.name, "model": None if args.baseline else args.model,
                    "adapter": args.adapter, "baseline": args.baseline, "split": args.split,
                    "labels": labels, "seconds": round(elapsed, 1),
                    "data_revision": meta.get("dataset_revision"),
                    "split_sha": file_sha(data_dir / f"{args.split}.jsonl"), "limit": args.limit,
                    "batch_size": args.batch_size, "max_new_tokens": args.max_new_tokens,
                    "git": git_info()})
    out = Path(args.results_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{args.name}.json").write_text(json.dumps(metrics, indent=2, ensure_ascii=False))
    with (out / f"{args.name}.predictions.jsonl").open("w") as handle:
        for r, text, t, c, v in zip(rows, raw, pred_types, pred_comments, valid):
            handle.write(json.dumps({"id": r["id"], "raw": text, "pred_type": t, "pred_comment": c,
                                     "valid": v, "gold_type": r["meta"]["type"]}, ensure_ascii=False) + "\n")
    print(json.dumps({k: metrics[k] for k in ("name", "n", "valid_rate", "accuracy", "macro_f1",
                                              "macro_f1_ci95", "type_macro_f1_positives", "flag",
                                              "flag_balanced_accuracy", "flag_length_matched",
                                              "rouge_l_on_positives")}, indent=2))


if __name__ == "__main__":
    main()
