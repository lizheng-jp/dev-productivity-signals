"""Checks which pull requests and issues an answer refers to against the evidence the tools returned.

GitHub numbers pull requests and issues from one sequence per repository, so a number identifies one
entity. The check is at entity level: mentioning PR 17 counts as citing every retrieved chunk of PR 17.
"""
import re
from typing import Any

REFERENCE_KEYS = frozenset({"iid", "number", "mrIid", "entityId"})
GITHUB_URL = re.compile(r"https?://(?:www\.)?github\.com/[^\s)\]>\"'`]+", re.IGNORECASE)
NUMBER_MENTION = re.compile(
    r"(?<![\w&/#])#(\d{1,7})\b|\b(?:PR|MR|pull request|issue)\s*#?(\d{1,7})\b", re.IGNORECASE)


def _project_number(url: str, project_url: str) -> int | None:
    match = re.match(re.escape(project_url) + r"/(?:pull|issues)/(\d{1,7})\b", url, re.IGNORECASE)
    return int(match.group(1)) if match else None


def answer_references(answer: str, project_url: str) -> tuple[set[int], int]:
    """Returns the PR/issue numbers the answer names and its count of links outside the project."""
    numbers: set[int] = set()
    external = 0
    for url in GITHUB_URL.findall(answer):
        number = _project_number(url, project_url)
        if number is not None:
            numbers.add(number)
        elif not (url.lower() == project_url.lower()
                  or url.lower().startswith(project_url.lower() + "/")):
            external += 1
    without_urls = GITHUB_URL.sub(" ", answer)
    numbers.update(int(first or second) for first, second in NUMBER_MENTION.findall(without_urls))
    return numbers, external


def evidence_references(evidence: Any, project_url: str) -> set[int]:
    """Returns every PR/issue number present in tool evidence, by known key or project URL."""
    found: set[int] = set()
    if isinstance(evidence, dict):
        for key, value in evidence.items():
            if key in REFERENCE_KEYS and type(value) is int:
                found.add(value)
            else:
                found |= evidence_references(value, project_url)
    elif isinstance(evidence, list):
        for item in evidence:
            found |= evidence_references(item, project_url)
    elif isinstance(evidence, str):
        # Retrieved text may name other PRs; an answer repeating that mention is supported.
        found |= answer_references(evidence, project_url)[0]
    return found
