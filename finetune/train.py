"""LoRA supervised fine-tuning on the jsonl files produced by prepare_data.py.

    python train.py --data-dir data --output-dir runs/qwen15b --smoke      # 1-minute sanity check
    python train.py --data-dir data --output-dir runs/qwen15b

Loss is computed on the assistant reply only. Uses plain transformers.Trainer + peft rather than trl,
whose SFT arguments change between releases.
"""
from __future__ import annotations

import argparse
import json
import platform
import random
from pathlib import Path

from codereview_ft.runinfo import file_sha, git_info


def load_jsonl(path: Path) -> list[dict]:
    with path.open() as handle:
        return [json.loads(line) for line in handle if line.strip()]


def tokenize_example(tokenizer, messages: list[dict], max_len: int) -> dict | None:
    """Mask everything except the assistant reply. Returns None if the example does not fit max_len."""
    prompt = tokenizer.apply_chat_template(messages[:-1], tokenize=False, add_generation_prompt=True)
    prompt_ids = tokenizer(prompt, add_special_tokens=False)["input_ids"]
    reply_ids = tokenizer(messages[-1]["content"] + tokenizer.eos_token, add_special_tokens=False)["input_ids"]
    if len(prompt_ids) + len(reply_ids) > max_len:
        return None
    return {"input_ids": prompt_ids + reply_ids,
            "labels": [-100] * len(prompt_ids) + reply_ids}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--base-model", default="Qwen/Qwen2.5-Coder-1.5B-Instruct")
    parser.add_argument("--epochs", type=float, default=2.0)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--lora-r", type=int, default=16)
    parser.add_argument("--max-len", type=int, default=2560)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--grad-accum", type=int, default=8)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--report-to", default="none", help="e.g. wandb")
    parser.add_argument("--val-limit", type=int, default=500,
                        help="validation examples used for eval loss during training (keeps it fast)")
    parser.add_argument("--smoke", action="store_true", help="64 examples, 1 epoch, to check the pipeline")
    args = parser.parse_args()

    import torch
    import transformers
    from peft import LoraConfig, get_peft_model
    from transformers import (AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments,
                              set_seed)

    set_seed(args.seed)
    data_dir = Path(args.data_dir)
    train_rows = load_jsonl(data_dir / "train.jsonl")
    val_rows = load_jsonl(data_dir / "validation.jsonl")[: args.val_limit]
    if args.smoke:
        train_rows, val_rows, args.epochs = train_rows[:64], val_rows[:16], 1.0
    random.Random(args.seed).shuffle(train_rows)

    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    def encode(rows):
        encoded = [tokenize_example(tokenizer, r["messages"], args.max_len) for r in rows]
        kept = [e for e in encoded if e is not None]
        return kept, len(rows) - len(kept)

    train_set, train_dropped = encode(train_rows)
    val_set, val_dropped = encode(val_rows)
    print(f"train {len(train_set)} (dropped {train_dropped} over {args.max_len} tokens), "
          f"val {len(val_set)} (dropped {val_dropped})")
    lengths = sorted(len(e["input_ids"]) for e in train_set)
    print(f"token length p50={lengths[len(lengths) // 2]} p95={lengths[int(len(lengths) * 0.95)]} max={lengths[-1]}")

    def collate(batch):
        width = max(len(item["input_ids"]) for item in batch)
        pad = tokenizer.pad_token_id
        return {
            "input_ids": torch.tensor([i["input_ids"] + [pad] * (width - len(i["input_ids"])) for i in batch]),
            "attention_mask": torch.tensor([[1] * len(i["input_ids"]) + [0] * (width - len(i["input_ids"])) for i in batch]),
            "labels": torch.tensor([i["labels"] + [-100] * (width - len(i["labels"])) for i in batch]),
        }

    try:
        model = AutoModelForCausalLM.from_pretrained(args.base_model, dtype=torch.bfloat16)
    except TypeError:  # older transformers
        model = AutoModelForCausalLM.from_pretrained(args.base_model, torch_dtype=torch.bfloat16)
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(r=args.lora_r, lora_alpha=2 * args.lora_r, lora_dropout=0.05,
                                             target_modules="all-linear", task_type="CAUSAL_LM"))
    model.print_trainable_parameters()

    steps_per_epoch = max(1, len(train_set) // (args.batch_size * args.grad_accum))
    eval_every = max(10, steps_per_epoch // 4)
    training_args = TrainingArguments(
        output_dir=args.output_dir, num_train_epochs=args.epochs, learning_rate=args.lr,
        lr_scheduler_type="cosine", warmup_ratio=0.03, weight_decay=0.0,
        per_device_train_batch_size=args.batch_size, per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum, bf16=True, logging_steps=10,
        eval_strategy="steps", eval_steps=eval_every, save_strategy="steps", save_steps=eval_every,
        save_total_limit=2, report_to=args.report_to, seed=args.seed, remove_unused_columns=False,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )
    trainer = Trainer(model=model, args=training_args, train_dataset=train_set, eval_dataset=val_set,
                      data_collator=collate)
    result = trainer.train()
    final_eval = trainer.evaluate()

    out = Path(args.output_dir)
    model.save_pretrained(out / "adapter")
    tokenizer.save_pretrained(out / "adapter")
    meta_in = json.loads((data_dir / "meta.json").read_text())
    (out / "train_meta.json").write_text(json.dumps({
        "args": vars(args), "train_examples": len(train_set), "val_examples": len(val_set),
        "train_loss": result.training_loss, "final_eval": final_eval,
        "train_metrics": result.metrics,  # includes train_runtime in seconds
        "git": git_info(), "data_sha": {"train": file_sha(data_dir / "train.jsonl"),
                                        "validation": file_sha(data_dir / "validation.jsonl")},
        "data_meta": meta_in, "versions": {"torch": torch.__version__, "transformers": transformers.__version__,
                                          "python": platform.python_version()},
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
    }, indent=2))
    print("saved adapter to", out / "adapter")


if __name__ == "__main__":
    main()
