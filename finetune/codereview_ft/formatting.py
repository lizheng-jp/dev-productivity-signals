"""Prompt/target formatting and output parsing shared by data prep, training and evaluation.

Everything here is pure Python so it can be unit-tested without torch or datasets.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass

NONE_LABEL = "none"
NO_ISSUES_COMMENT = "No issues found."
INVALID = "__invalid__"  # prediction bucket for outputs that cannot be parsed or have an unknown type


def build_system_prompt(labels: list[str]) -> str:
    options = ", ".join(f'"{label}"' for label in labels)
    return (
        "You are a senior engineer reviewing a pull request. You are given one diff hunk from one file. "
        "Decide whether a human reviewer would leave an inline comment on it, and if so write that comment. "
        f"Reply with a single JSON object with the keys \"type\" and \"comment\". \"type\" must be one of: {options}. "
        f"If nothing needs flagging, use type \"{NONE_LABEL}\" and comment \"{NO_ISSUES_COMMENT}\"."
    )


def build_user_prompt(*, language: str, file_path: str, pr_title: str, diff: str,
                      before_code: str = "") -> str:
    parts = [f"Language: {language}", f"File: {file_path}", f"PR title: {pr_title}"]
    if before_code.strip():
        parts += ["", "Code before the change:", before_code.rstrip()]
    parts += ["", "Diff:", diff.rstrip()]
    return "\n".join(parts)


def build_target(comment_type: str, comment: str, type_first: bool = True) -> str:
    pairs = [("type", comment_type), ("comment", comment)]
    if not type_first:
        pairs.reverse()
    return json.dumps(dict(pairs), ensure_ascii=False)


@dataclass(frozen=True)
class Parsed:
    valid: bool          # JSON object, known type, string comment
    type: str | None     # normalised type, or None if absent/unknown
    comment: str         # empty when the output could not be parsed


_FENCE = re.compile(r"^```[a-zA-Z0-9_-]*\s*|\s*```$")


def _first_json_object(text: str) -> dict | None:
    cleaned = _FENCE.sub("", text.strip())
    decoder = json.JSONDecoder()
    for match in re.finditer(r"\{", cleaned):
        try:
            value, _ = decoder.raw_decode(cleaned, match.start())
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            return value
    return None


def parse_prediction(text: str, labels: list[str]) -> Parsed:
    obj = _first_json_object(text)
    if obj is None:
        return Parsed(False, None, "")
    raw_type = obj.get("type")
    comment = obj.get("comment")
    normalised = raw_type.strip().lower() if isinstance(raw_type, str) else None
    type_ok = normalised in labels
    comment_ok = isinstance(comment, str)
    return Parsed(type_ok and comment_ok, normalised if type_ok else None,
                  comment.strip() if comment_ok else "")
