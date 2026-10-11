"""Programmatic checks for main-task answers ("why did metric X change?"), which have no reference answer.

Each answer is checked against the tool evidence the Agent itself received (its trace):
- numbers: every number the answer states must appear in the evidence or follow from a pair of evidence values
  (difference, percent change, ratio), allowing for rounding;
- references: every PR or issue number the answer cites must appear in the evidence;
- tools: a metrics tool must run before any evidence search, a merge lead-time question must read the lead-time
  distribution, and a why-question should search the discussions.
It also derives the metric's actual direction from the comparison evidence for the language checks.

    python eval/main_task/check.py eval/main_task/questions.jsonl eval/main_task/answers.jsonl > checks.jsonl
"""
import json
import re
import sys

METRIC_TOOLS = {"get_project_metrics", "get_project_comparison", "get_member_metrics"}
FLAT_WITHIN = 0.05
NUMBER = re.compile(r"(?<![\w.])[-+]?\d{1,3}(?:,\d{3})+(?:\.\d+)?|(?<![\w.])[-+]?\d+(?:\.\d+)?")
REFERENCE = re.compile(r"(?:#|/pull/|/issues/)(\d{2,6})\b")
DATE = re.compile(r"\b\d{4}-\d{2}-\d{2}(?:T[\d:.]+Z?)?\b")
URL = re.compile(r"https?://\S+")


def numbers_in(value) -> list[float]:
    """Every number in an evidence value, including numbers written inside strings."""
    found: list[float] = []
    if isinstance(value, bool) or value is None:
        return found
    if isinstance(value, (int, float)):
        return [float(value)]
    if isinstance(value, str):
        text = URL.sub(" ", DATE.sub(" ", value))
        return [float(token.replace(",", "")) for token in NUMBER.findall(text)]
    if isinstance(value, dict):
        for item in value.values():
            found += numbers_in(item)
    if isinstance(value, list):
        for item in value:
            found += numbers_in(item)
    return found


def comparison_pairs(trace: list[dict]) -> list[tuple[str, float, float]]:
    """(metric, previous, current) for every current/previous pair in the evidence, at any depth."""
    pairs = []

    def walk(value):
        if isinstance(value, dict):
            current, previous = value.get("current"), value.get("previous")
            if isinstance(current, dict) and isinstance(previous, dict):
                for key, item in current.items():
                    if (isinstance(item, (int, float)) and not isinstance(item, bool)
                            and isinstance(previous.get(key), (int, float))):
                        pairs.append((key, float(previous[key]), float(item)))
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk([call.get("evidence") for call in trace])
    return pairs


def derived(pairs: list[tuple[str, float, float]]) -> set[float]:
    """Only comparisons of one metric between periods count, so an invented figure cannot match by chance."""
    out = set()
    for _, a, b in pairs:
        out.add(abs(b - a))
        if a:
            out.add(abs((b - a) / a * 100))
            out.add(abs(b / a))
        if b:
            out.add(abs((a - b) / b * 100))
            out.add(abs(a / b))
    return out


def matches(number: float, pool: set[float]) -> bool:
    for value in pool:
        if abs(number - value) <= max(0.051, abs(value) * 0.006):
            return True
    return False


def answer_numbers(answer: str) -> list[float]:
    text = URL.sub(" ", DATE.sub(" ", answer))
    text = REFERENCE.sub(" ", text)
    text = re.sub(r"\b(?:19|20)\d{2}\b", " ", text)  # years
    text = re.sub(r"^\s*\d+[.)]\s", " ", text, flags=re.M)  # list markers
    text = re.sub(r"\b\d+(?:st|nd|rd|th)\b", " ", text)  # ordinals such as "90th percentile"
    return [float(token.replace(",", "")) for token in NUMBER.findall(text)]


def check(question: dict, answer: dict) -> dict:
    trace = answer.get("trace") or []
    tools = [call["tool"] for call in trace]
    evidence_values = numbers_in([call.get("evidence") for call in trace])
    pairs = comparison_pairs(trace)
    pool = set(abs(v) for v in evidence_values) | derived(pairs)
    # Hour figures restated in days or weeks.
    pool |= {value / unit for value in list(pool) for unit in (24, 168)}
    stated = answer_numbers(answer.get("answer", ""))
    unverified = sorted({n for n in stated if abs(n) > 1 and not matches(abs(n), pool)})
    evidence_text = json.dumps([call.get("evidence") for call in trace])
    cited = sorted({int(n) for n in REFERENCE.findall(answer.get("answer", ""))})
    uncited_refs = [n for n in cited if not re.search(rf"(?<!\d){n}(?!\d)", evidence_text)]

    metric = question["metric"]
    actual = next(((p, c) for key, p, c in pairs if key == metric), None)
    direction = None
    if actual:
        previous, current = actual
        change = (current - previous) / previous if previous else (1.0 if current else 0.0)
        direction = "flat" if abs(change) <= FLAT_WITHIN else ("up" if change > 0 else "down")

    first_search = tools.index("search_project_evidence") if "search_project_evidence" in tools else None
    first_metric = next((i for i, tool in enumerate(tools) if tool in METRIC_TOOLS), None)
    return {
        "id": question["id"], "query": question["query"], "status": answer.get("status"),
        "metric": metric, "premise": question["premise"], "kind": question["kind"],
        "actual": {"previous": actual[0], "current": actual[1], "direction": direction} if actual else None,
        "numbersStated": len([n for n in stated if abs(n) > 1]), "unverifiedNumbers": unverified,
        "citedReferences": cited, "referencesNotInEvidence": uncited_refs,
        "tools": tools,
        "metricsBeforeSearch": first_metric is not None and (first_search is None or first_metric < first_search),
        "searchedDiscussions": first_search is not None,
        "readLeadDistribution": "get_merge_lead_distribution" in tools if metric == "mergedLeadTimeHours" else None,
    }


def main() -> None:
    questions = {json.loads(line)["query"]: json.loads(line) for line in open(sys.argv[1]) if line.strip()}
    for line in open(sys.argv[2]):
        if line.strip():
            answer = json.loads(line)
            print(json.dumps(check(questions[answer["query"]], answer), ensure_ascii=False))


if __name__ == "__main__":
    main()
