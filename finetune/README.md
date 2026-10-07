# Code-review model fine-tuning

LoRA fine-tune of a small open model on [`ronantakizawa/github-codereview`](https://huggingface.co/datasets/ronantakizawa/github-codereview).

**Task.** Input: one diff hunk (plus file path, language and PR title). Output: a JSON object `{"type": ..., "comment": ...}` where `type` is the review-comment category (`bug`, `style`, `nitpick`, ..., or `none`) and `comment` is the review comment (`"No issues found."` for `none`).

**Status.** The pipeline and its unit tests are written. No model has been trained yet, and this README contains no results. Numbers come from `results/*.json` after you run the steps below; do not quote anything that is not in those files.

## What the evaluation is designed to show

| Question | How it is answered |
| --- | --- |
| Did training help at all? | Same test set and prompt for `majority` (always predict the most common training type), `length` (uses only diff length), `base` (zero-shot) and `tuned` |
| Is the difference noise? | Bootstrap 95% CI on macro-F1 |
| Does it generalise to unseen repositories? | Splits are by repository; `build` additionally removes any training row whose repo or (diff, comment) pair appears in validation/test |
| Can the model score well from input artifacts alone? | `audit` measures empty-diff rate, diff markers and how well one diff-length threshold separates comment/no-comment; the `length` baseline and the length-matched flag metric show how much of the score is length |
| Is the output usable? | Valid-JSON rate, plus accuracy where an unparseable answer counts as wrong |
| Does it know when *not* to comment? | Flag precision/recall and balanced accuracy (comment vs `none`), also on a length-matched subsample |
| Does it get the category right when a reviewer did comment? | Type macro-F1 over commented hunks only (`type_macro_f1_positives`) |
| Is the comment text good? | ROUGE-L against the reviewer's comment (weak proxy) and an optional pairwise LLM judge (`judge.py`) |

Design decisions:

- `after_code` (the code after the reviewer's fix) is dropped because it is not available when a review is written. `repo_name` is not put in the prompt, to limit memorisation.
- Loss is computed on the assistant reply only.
- Diffs of 2000 characters or more are dropped (`--max-diff-chars`). The dataset's negatives pile up at exactly 2000, a cap only one class reaches, and longer positives would be a region where the label is trivially "comment". This removes roughly the longest tenth of positives and an unmeasured share of negatives; `meta.json` records the counts, and raising the flag brings them back at the cost of a label tell.
- `--match-negative-length` (on by default) resamples training negatives to mirror the positives' diff-length mix as far as availability allows, and `build` reports the negative share it actually achieved.
- Validation and test use every clean example (`--test-size 0`), because rare classes (`security`, `performance`, `nitpick`) have only about 100-150 test rows before filtering.
- Training is class-balanced (equal quota per type, fixed negative share); validation and test keep the natural distribution, so macro-F1 and the majority baseline are meaningful.
- Quality filtering (`--min-quality`) applies to positive examples in all splits so the benchmark is the same clean distribution everywhere.
- `type` comes first in the JSON by default. `--no-type-first` on `build` puts the comment first, which lets the type be conditioned on the generated comment; this is a cheap ablation.

## What the data audit found

From `prepare_data.py audit` on dataset revision `c3e3c6e7e9f61e3e7a5b52894bcd440d586ae6ca` (run on 2026-10-07):

- The dataset card says splits are by repository, but train shares 40 repositories with validation and 54 with test, plus 2,991 and 4,475 identical (diff, comment) pairs. `build` removes those training rows, so the held-out claim is true for this pipeline, not for the raw splits.
- Negative ("no issue") examples are longer: diff length p50 is about 1,650-1,870 characters against about 450-550 for commented hunks, and the negatives' p75, p95 and max are all exactly 2,000, which looks like a hard cap. A single length threshold (about 890 characters, longer means no comment) predicts comment/no-comment with balanced accuracy of 0.70 (train), 0.73 (validation) and 0.75 (test), where 0.5 is chance. That is a moderate shortcut, not a decisive one; the `length` baseline and the length-matched metric keep it visible.
- Negatives and positives are both real diffs: 99.5-100% of rows have a `@@` hunk header, and 98-100% have `+`/`-` lines. So the input format does not give the label away. (An earlier version of this README worried negatives might be plain code; the audit shows they are not.)
- Negatives are 22% of train but 31% of validation and 37% of test, so class priors differ between train and test.
- Negatives almost never have an empty diff (under 0.1%).
- The audit now also reports the share of rows at exactly the modal length and whether the last hunk is cut short, per class. Check those numbers before treating the cap as a truncation artifact: the samples file only prints the first 700 characters, so it cannot show where a 2,000-character diff ends.

In `data/audit_samples.txt`, one of three commented hunks (`nit: use EXPECT_STATUS_OK ...`) is labelled `suggestion` while another "Nit: ..." comment is labelled `nitpick`. Three examples prove nothing, but it is a reason to hand-check the labels before trusting type macro-F1.

## Limits to state honestly

- "No issue" here means no inline comment was left on that chunk, not that the code is correct. A reviewer may simply not have commented. Treat the comment/no-comment decision as "would a reviewer have commented here", not as a correctness judgement.
- Labels (`comment_type`, `quality_score`) come from the dataset. Its card does not say how they were assigned; check a sample by hand before trusting them, and say so if you cite macro-F1.
- Predicting what a human reviewer will say about a hunk is ambiguous: several comments can be valid. Expect modest absolute numbers; the baselines and CIs are what make them interpretable.
- Base models may have seen these public repositories during pre-training. Base-vs-tuned is a fair comparison; the absolute score is not proof of generalisation.
- This is GitHub inline-comment data. Signals analyses GitLab merge requests, so the model is not validated on that domain.

## Run it on RunPod

Use a GPU with at least 24 GB (A40, L4, RTX 4090 or A5000 are enough for a 1.5B model with LoRA), a PyTorch template, and a persistent volume mounted at `/workspace` so downloads and checkpoints survive a restart. Template names change; any recent CUDA PyTorch image works.

```sh
# 1. Get the code onto the pod (push this folder to Git and clone it, or use `runpodctl send finetune`)
cd /workspace && git clone <your-repo-url> && cd dev-productivity-signals/finetune

# 2. Environment
export HF_HOME=/workspace/hf
pip install -r requirements.txt
python -m unittest discover -s tests          # pure-Python tests, should pass before you spend GPU time

# 3. Audit the data, and READ the warnings and data/audit_samples.txt before continuing
#    (CPU only; fine to run on your Mac first: pip install datasets huggingface_hub)
python prepare_data.py audit --out data/audit.json

# 4. Build the SFT files (writes data/{train,validation,test}.jsonl and data/meta.json)
python prepare_data.py build --out-dir data --train-size 30000

# 5. Pipeline check (about a minute), then the real run. Use one id per experiment: exp01, exp02, ...
python train.py --data-dir data --output-dir runs/smoke --smoke
python train.py --data-dir data --output-dir runs/exp01

# 6. Evaluate on the validation split while you iterate; use --split test only for the runs you will quote
python evaluate.py --results-dir results/exp01 --split validation --name majority --baseline majority
python evaluate.py --results-dir results/exp01 --split validation --name length   --baseline length
python evaluate.py --results-dir results/exp01 --split validation --name base  --model Qwen/Qwen2.5-Coder-1.5B-Instruct
python evaluate.py --results-dir results/exp01 --split validation --name tuned --model Qwen/Qwen2.5-Coder-1.5B-Instruct --adapter runs/exp01/adapter
python report.py results/exp01                  # markdown comparison table

# 7. Optional: pairwise LLM judge on comment quality (needs a Gemini key; 150 judge calls)
GEMINI_API_KEY=... python judge.py --a results/exp01/base.predictions.jsonl --b results/exp01/tuned.predictions.jsonl --split validation

# 8. Record the experiment (see "Recording experiments"), download experiments/, runs/exp01/adapter and data/{meta,audit}.json,
#    then STOP the pod to stop billing
python experiments.py record --id exp01-baseline --train-dir runs/exp01 --results-dir results/exp01 \
    --hypothesis "..." --change "baseline: Qwen2.5-Coder-1.5B, LoRA r=16, 2 epochs"
```

Time and cost depend on the GPU and token lengths. `train.py` prints the token-length distribution and the smoke run gives you a step time; extrapolate from that before launching the full run. If memory is tight, lower `--batch-size` and raise `--grad-accum`. For a faster first pass use `--train-size 8000` and `--epochs 1`.

### If `audit` reports shortcuts

- **Diff length** (found): keep the defaults (2000-char cap, length-matched negatives), and judge the "does it know when not to comment" ability only by the length-matched balanced accuracy and by how far `tuned` beats the `length` baseline.
- **Diff markers differ by class** (check after re-running audit): if negatives are plain code while positives are `@@`/`+`/`-` diffs, the input format leaks the label. Then report type metrics on commented hunks only and do not claim a flag/none result.
- **Negatives without diffs**: `build` drops them and warns if almost none remain. Do not silently continue.

## Recording experiments

Several experiments are run, and each record answers four questions: *why it was run, what exactly changed, what happened, what was decided*.

Rules that make the record trustworthy:

1. **Write the hypothesis before the run**, in one sentence ("comment-first ordering raises type F1 because the type is chosen after the comment is written"). A result without a prior hypothesis is a story you made up afterwards.
2. **Change one thing per experiment** and name its parent (`--parent exp01-baseline`). Otherwise you cannot say which change helped.
3. **Choose with validation, report with test.** Evaluate on `--split validation` while iterating and run `--split test` only for the few runs you will quote. Selecting among many variants on the test set quietly overfits to it. Rebuild with `--val-size 2000` first: the 500-example default has 2 `nitpick` rows, too few to decide anything.
4. **Same eval data for any comparison.** `INDEX.md` shows a data fingerprint and prints a warning if a run and its parent were evaluated on different files (for example after changing `--no-type-first`, which changes the prompts).
5. **Never quote a number that is not in a recorded file**, and quote it with its confidence interval. `INDEX.md` says "CIs overlap" when a difference is within noise. Overlapping intervals are a conservative reading, not a significance test; a paired bootstrap on the predictions would be sharper.
6. **Keep the failures.** A change that did not help is a result; record it and say why you think so.

`python experiments.py record` copies the training metadata (hyper-parameters, GPU, time, data fingerprints, git commit and whether the tree was dirty), the evaluation results and the data metadata into `experiments/<id>/`, creates a `NOTES.md` template with the hypothesis and setup filled in, and regenerates `experiments/INDEX.md`. Fill in `NOTES.md` while the run is fresh, then commit `experiments/` (it is small; add `--with-predictions` if you want the raw outputs too).

A suggested ladder, each answering one question:

| id | change | question it answers |
| --- | --- | --- |
| exp01-baseline | Qwen2.5-Coder-1.5B, LoRA r=16, 30k examples, type first | Does fine-tuning beat zero-shot, the majority class and the length-only baseline? |
| exp02-comment-first | `build --no-type-first` | Does writing the comment before choosing the type improve type F1? |
| exp03-no-length-match | `build --no-match-negative-length` | How much of the flag decision was diff length? |
| exp04-8k | `--train-size 8000` | How much does more data help (cost vs gain)? |
| exp05-0.5b | `--base-model Qwen/Qwen2.5-Coder-0.5B-Instruct` | How much does model size matter for this task? |

Run them in order of cost and keep each one only if it answers its question; a few well-recorded experiments are worth more than many unexplained ones.

## Reading the results

- Compare `tuned` to `base` and to `majority`, with the CI. If the CIs overlap, you cannot claim an improvement.
- Look at per-class F1 and the confusion matrix in `results/tuned.json`: rare classes (`security`, `performance`) usually fail first.
- Open `results/tuned.predictions.jsonl` and read about 30 wrong answers. Categorise the failures (ambiguous label, plausible alternative comment, wrong analysis, format error) and record the counts in the experiment's `NOTES.md`.

## Next step (not done)

Serve the merged model behind an OpenAI-compatible endpoint (for example vLLM) and add it to the Signals Agent as a tool that categorises PR review discussions, then evaluate it on a hand-labelled sample of real GitLab/GitHub discussions from this project. Until that is done, do not describe the model as part of the product.
