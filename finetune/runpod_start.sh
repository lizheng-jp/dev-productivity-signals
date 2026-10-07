#!/usr/bin/env bash
# Unattended pipeline for a RunPod GPU pod: build data, train, evaluate, package, print metrics to the logs.
#
#   REPO_URL=https://github.com/lizheng-jp/dev-productivity-signals.git EXP=exp01 bash runpod_start.sh
#
# Everything important is printed to stdout so it shows up in the pod logs. Artifacts end up in
# /workspace/$EXP-artifacts.tar.gz. The script then offers that file through `runpodctl send` and keeps the pod
# alive; STOP THE POD yourself once you have the file, because it bills while it runs.
set -euo pipefail
# On failure keep the pod alive so the logs can be read, then stop it yourself.
trap 'echo "FAILED at line $LINENO. Pod left running for log inspection; STOP IT to stop billing."; sleep infinity' ERR

REPO_URL="${REPO_URL:-https://github.com/lizheng-jp/dev-productivity-signals.git}"
BRANCH="${BRANCH:-main}"
EXP="${EXP:-exp01}"
BASE_MODEL="${BASE_MODEL:-Qwen/Qwen2.5-Coder-1.5B-Instruct}"
TRAIN_SIZE="${TRAIN_SIZE:-30000}"
VAL_SIZE="${VAL_SIZE:-2000}"
SPLIT="${SPLIT:-validation}"           # evaluate on validation while iterating; use test only for quoted runs
EXPECT_TRAIN_SHA="${EXPECT_TRAIN_SHA:-}"   # optional: sha prefix of the local data/train.jsonl, to prove identical data

export HF_HOME=/workspace/hf
export PYTHONUNBUFFERED=1
export TOKENIZERS_PARALLELISM=false

step() { echo; echo "===== $(date -u +%H:%M:%S) $* ====="; }

cd /workspace
if [ ! -d repo ]; then
  step "clone $REPO_URL ($BRANCH)"
  git clone --depth 1 --branch "$BRANCH" "$REPO_URL" repo
fi
cd repo/finetune
echo "commit: $(git rev-parse --short HEAD)"
nvidia-smi --query-gpu=name,memory.total --format=csv,noheader || true

step "install requirements"
pip install -q --break-system-packages -r requirements.txt 2>&1 | tail -n 3 || pip install -q -r requirements.txt

step "unit tests"
python -m unittest discover -s tests 2>&1 | tail -n 5

step "build data (train=$TRAIN_SIZE val=$VAL_SIZE)"
if [ ! -f data/meta.json ]; then
  python prepare_data.py build --out-dir data --train-size "$TRAIN_SIZE" --val-size "$VAL_SIZE"
fi
SHA=$(python - <<'EOF'
from pathlib import Path
from codereview_ft.runinfo import file_sha
print(file_sha(Path("data/train.jsonl")))
EOF
)
echo "train.jsonl sha: $SHA"
if [ -n "$EXPECT_TRAIN_SHA" ] && [[ "$SHA" != "$EXPECT_TRAIN_SHA"* ]]; then
  echo "WARNING: train.jsonl differs from the local copy ($EXPECT_TRAIN_SHA); results are not comparable with local data"
fi
wc -l data/*.jsonl

step "smoke test"
python train.py --data-dir data --output-dir runs/smoke --base-model "$BASE_MODEL" --smoke 2>&1 | tail -n 15

step "train $EXP"
python train.py --data-dir data --output-dir "runs/$EXP" --base-model "$BASE_MODEL" 2>&1 | grep -v "it/s\]\|s/it\]" | tail -n 200

RES="results/$EXP"
step "baselines on $SPLIT"
python evaluate.py --results-dir "$RES" --split "$SPLIT" --name majority --baseline majority | tail -n 3
python evaluate.py --results-dir "$RES" --split "$SPLIT" --name length --baseline length | tail -n 3

step "base model zero-shot on $SPLIT"
python evaluate.py --results-dir "$RES" --split "$SPLIT" --name base --model "$BASE_MODEL" | tail -n 40

step "tuned model on $SPLIT"
python evaluate.py --results-dir "$RES" --split "$SPLIT" --name tuned --model "$BASE_MODEL" \
  --adapter "runs/$EXP/adapter" | tail -n 40

step "report"
python report.py "$RES"

step "package"
tar -czf "/workspace/$EXP-artifacts.tar.gz" "runs/$EXP/adapter" "runs/$EXP/train_meta.json" "$RES" data/meta.json
ls -la "/workspace/$EXP-artifacts.tar.gz"

step "DONE. Offering the artifacts; on your Mac run the 'runpodctl receive' command printed below. STOP THE POD afterwards."
if command -v runpodctl >/dev/null 2>&1; then
  runpodctl send "/workspace/$EXP-artifacts.tar.gz" || true
fi
sleep infinity
