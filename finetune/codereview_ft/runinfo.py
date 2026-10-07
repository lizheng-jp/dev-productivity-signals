"""Reproducibility fingerprints recorded with every training/evaluation run."""
from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path


def git_info(cwd: str | Path = ".") -> dict:
    """Commit and whether files under `cwd` have uncommitted changes. Values are None outside a git checkout."""
    def run(*args: str) -> str | None:
        try:
            out = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, timeout=10, check=True)
            return out.stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return None

    commit = run("rev-parse", "--short=12", "HEAD")
    status = run("status", "--porcelain", "--", ".")
    return {"commit": commit, "dirty": None if status is None else bool(status)}


def file_sha(path: str | Path, length: int = 12) -> str | None:
    """Short SHA-256 of a file; identifies exactly which data a run used."""
    path = Path(path)
    if not path.exists():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()[:length]


def format_progress(step: int, max_steps: int, elapsed_s: float, logs: dict) -> str:
    """One log line per Trainer log event: step, percent, elapsed, ETA and the main numbers."""
    pct = 100 * step / max_steps if max_steps else 0.0
    eta_min = elapsed_s / step * (max_steps - step) / 60 if step else 0.0
    shown = []
    for key in ("loss", "eval_loss", "learning_rate", "grad_norm"):
        if key in logs:
            try:
                shown.append(f"{key}={float(logs[key]):.4g}")
            except (TypeError, ValueError):
                shown.append(f"{key}={logs[key]}")
    return (f"[progress] step {step}/{max_steps} ({pct:.0f}%) elapsed {elapsed_s / 60:.1f} min, "
            f"ETA {eta_min:.0f} min | " + ", ".join(shown))
