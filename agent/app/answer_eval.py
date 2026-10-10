"""Answer-level evaluation: does the Agent's answer contain the reference facts, stay within its evidence,
and decline when the evidence cannot answer?

Step 1 asks the running Agent every reference query and appends one JSON line per answer. It resumes,
skipping queries already in the output, because free-tier Gemini quotas stop long runs:

    python -m app.answer_eval generate --project-id github~owner~repo --ref-name main \
        --reference eval/answers/reference.jsonl --out /tmp/answers.jsonl

Step 2 grades each answer with a Gemini judge against the key facts and the source texts (the labelled
relevant sources plus whatever the Agent retrieved), also resuming:

    python -m app.answer_eval judge --project-id github~owner~repo --ref-name main \
        --reference eval/answers/reference.jsonl --answers /tmp/answers.jsonl --out /tmp/grades.jsonl

Step 3 summarises the grades:

    python -m app.answer_eval summarize --reference eval/answers/reference.jsonl --grades /tmp/grades.jsonl
"""
import argparse
import asyncio
import json
import os
import re
import time
from pathlib import Path
from statistics import mean
from typing import Any
from urllib.parse import quote

import httpx

from app.qdrant_evidence import COLLECTION, configured_index

AGENT_URL = os.getenv("AGENT_URL", "http://127.0.0.1:8000")
FACT_CREDIT = {"covered": 1.0, "partial": 0.5, "missing": 0.0, "contradicted": 0.0}
RATE_LIMIT_WAIT_SECONDS = 65
# Quota, auth and availability errors repeat and say nothing about the Agent: stop on the first few.
# An Agent failure (502/504) is a result to record, but a long run of them points at a broken setup.
EXTERNAL_FAILURES = frozenset({401, 403, 429, 503})
MAX_CONSECUTIVE_EXTERNAL_FAILURES = 3
DEFAULT_MAX_CONSECUTIVE_FAILURES = 10
MAX_SOURCE_CHARS = 6000

JUDGE_INSTRUCTION = """You grade answers from a question-answering agent about GitHub pull requests and issues.
You are given the question, the key facts a correct answer must contain, the source texts, and the answer.
Judge only against the key facts and the source texts, never against your own knowledge.

For each key fact, decide:
- covered: the answer states it, in any wording.
- partial: the answer states part of it, or states it too vaguely to check.
- missing: the answer does not state it.
- contradicted: the answer states something incompatible with it.

Then list the answer's factual claims about the repository that no source text supports
(invented PR numbers, outcomes, decisions, values or causes). Ignore hedges, offers to help and restatements
of the question. Claims based on metric figures the agent reports from its own tools are not unsupported
merely because the source texts lack them; list them only if they contradict a source text.

Finally decide whether the answer declines: it says the evidence is insufficient or it cannot answer,
instead of giving a substantive answer."""

JUDGE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "facts": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "index": {"type": "INTEGER"},
            "verdict": {"type": "STRING", "enum": list(FACT_CREDIT)},
            "reason": {"type": "STRING"}}, "required": ["index", "verdict", "reason"]}},
        "unsupportedClaims": {"type": "ARRAY", "items": {"type": "STRING"}},
        "declined": {"type": "BOOLEAN"},
    },
    "required": ["facts", "unsupportedClaims", "declined"],
}


def load_jsonl(path: str) -> list[dict[str, Any]]:
    if not Path(path).exists():
        return []
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def append_jsonl(path: str, row: dict[str, Any]) -> None:
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def gold_entities(reference: dict[str, Any]) -> set[str]:
    return {":".join(source.split(":")[:2]) for fact in reference["keyFacts"] for source in fact["sources"]}


def judge_prompt(reference: dict[str, Any], answer: str, sources: dict[str, str]) -> str:
    facts = "\n".join(f"{index}. {fact['fact']}" for index, fact in enumerate(reference["keyFacts"], 1))
    texts = "\n\n".join(f"[{source_id}]\n{content[:MAX_SOURCE_CHARS]}" for source_id, content in sources.items())
    return (f"Question:\n{reference['query']}\n\n"
            f"Key facts:\n{facts or '(none: the sources cannot answer this question)'}\n\n"
            f"Source texts:\n{texts or '(none)'}\n\nAnswer:\n{answer}")


def grade_row(reference: dict[str, Any], answer: dict[str, Any], verdict: dict[str, Any]) -> dict[str, Any]:
    by_index = {item["index"]: item for item in verdict.get("facts", [])}
    facts = [{"fact": fact["fact"], "verdict": by_index.get(index, {}).get("verdict", "missing"),
              "reason": by_index.get(index, {}).get("reason", "")}
             for index, fact in enumerate(reference["keyFacts"], 1)]
    cited = {source["sourceId"] for source in answer.get("sources", []) if source.get("cited")}
    cited_entities = {":".join(source_id.split(":")[:2]) for source_id in cited if source_id}
    row = {"query": reference["query"], "expected": reference["expected"], "facts": facts,
           "unsupportedClaims": verdict.get("unsupportedClaims", []),
           "declined": bool(verdict.get("declined")), "citedEntities": sorted(cited_entities)}
    if reference["expected"] == "answer":
        row["factRecall"] = mean(FACT_CREDIT[fact["verdict"]] for fact in facts)
        if cited_entities:
            row["citationPrecision"] = len(cited_entities & gold_entities(reference)) / len(cited_entities)
    return row


def summarize(references: list[dict[str, Any]], grades: list[dict[str, Any]]) -> dict[str, Any]:
    by_query = {grade["query"]: grade for grade in grades}
    graded = [by_query[reference["query"]] for reference in references if reference["query"] in by_query]
    answerable = [grade for grade in graded if grade["expected"] == "answer"]
    unanswerable = [grade for grade in graded if grade["expected"] != "answer"]
    verdicts = [fact["verdict"] for grade in answerable for fact in grade["facts"]]
    summary: dict[str, Any] = {"graded": len(graded), "answerable": len(answerable),
                               "unanswerable": len(unanswerable)}
    if answerable:
        summary["factRecall"] = round(mean(grade["factRecall"] for grade in answerable), 4)
        summary["factVerdicts"] = {name: verdicts.count(name) for name in FACT_CREDIT}
        summary["answersWithUnsupportedClaims"] = sum(bool(grade["unsupportedClaims"]) for grade in answerable)
        summary["declinedWhenAnswerable"] = sum(grade["declined"] for grade in answerable)
        precision = [grade["citationPrecision"] for grade in answerable if "citationPrecision" in grade]
        summary["citationPrecision"] = round(mean(precision), 4) if precision else None
        summary["answersCitingNothing"] = sum("citationPrecision" not in grade for grade in answerable)
    if unanswerable:
        summary["declinedWhenUnanswerable"] = sum(grade["declined"] for grade in unanswerable)
        summary["unanswerableWithUnsupportedClaims"] = sum(
            bool(grade["unsupportedClaims"]) for grade in unanswerable)
    return summary


async def generate(args: argparse.Namespace) -> None:
    key = os.environ.get("AGENT_INTERNAL_KEY", "")
    if not key:
        raise SystemExit("AGENT_INTERNAL_KEY is required")
    # Agent failures are results; only quota, auth and availability errors are asked again.
    done = {row["query"] for row in load_jsonl(args.out) if row.get("status") not in EXTERNAL_FAILURES}
    failures = external = 0
    async with httpx.AsyncClient(timeout=300) as client:
        for reference in load_jsonl(args.reference):
            if reference["query"] in done:
                continue
            for attempt in range(3):
                start = time.monotonic()
                response = await client.post(f"{AGENT_URL}/ask", headers={"X-Agent-Internal-Key": key}, json={
                    "question": reference["query"], "projectId": args.project_id,
                    "since": reference["since"], "until": reference["until"], "refName": args.ref_name,
                    **({"model": args.model} if args.model else {})})
                if response.status_code != 429 or attempt == 2:
                    break
                print(json.dumps({"event": "rate_limited", "query": reference["query"][:60]}), flush=True)
                await asyncio.sleep(RATE_LIMIT_WAIT_SECONDS)
            payload = response.json()
            row = {"query": reference["query"], "status": response.status_code,
                   "seconds": round(time.monotonic() - start, 1), "answer": payload.get("answer", ""),
                   "detail": payload.get("detail"), "iterations": payload.get("iterations"),
                   "sources": payload.get("sources", [])}
            append_jsonl(args.out, row)
            print(json.dumps({"query": reference["query"][:60], "status": response.status_code,
                              "seconds": row["seconds"]}), flush=True)
            failures = 0 if response.status_code == 200 else failures + 1
            external = external + 1 if response.status_code in EXTERNAL_FAILURES else 0
            if external >= MAX_CONSECUTIVE_EXTERNAL_FAILURES or 0 < args.max_failures <= failures:
                raise SystemExit(f"Stopped after {failures} failed answers in a row; last: "
                                 f"{response.status_code} {payload.get('detail')}")
            await asyncio.sleep(args.pause)


async def source_texts(client: httpx.AsyncClient, project_id: str, ref_name: str | None,
                       source_ids: set[str]) -> dict[str, str]:
    if not source_ids:
        return {}
    index = configured_index(client)
    scope = index._scope_filter(project_id, ref_name)
    scope["must"].append({"key": "source_id", "match": {"any": sorted(source_ids)}})
    chunks: dict[str, dict[str, str]] = {}
    async for point in index._scroll(COLLECTION, scope):
        payload = point["payload"]
        chunks.setdefault(payload["source_id"], {})[payload["chunk_id"]] = payload["content"]
    return {source_id: "\n".join(parts[chunk_id] for chunk_id in sorted(parts, key=_chunk_order))
            for source_id, parts in sorted(chunks.items())}


def _chunk_order(chunk_id: str) -> int:
    match = re.search(r":(\d+)$", chunk_id)
    return int(match.group(1)) if match else 0


async def judge_answer(client: httpx.AsyncClient, model: str, key: str, prompt: str) -> dict[str, Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{quote(model, safe='')}:generateContent"
    body = {"systemInstruction": {"parts": [{"text": JUDGE_INSTRUCTION}]},
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": {"temperature": 0, "responseMimeType": "application/json",
                                 "responseSchema": JUDGE_SCHEMA}}
    for attempt in range(4):
        response = await client.post(url, headers={"x-goog-api-key": key}, json=body, timeout=120)
        if response.status_code in (429, 500, 502, 503, 504) and attempt < 3:
            await asyncio.sleep(RATE_LIMIT_WAIT_SECONDS if response.status_code == 429 else 2 ** attempt)
            continue
        response.raise_for_status()
        parts = response.json()["candidates"][0]["content"]["parts"]
        return json.loads("".join(part.get("text", "") for part in parts))
    raise AssertionError("unreachable")


async def judge(args: argparse.Namespace) -> None:
    key = os.environ.get("GEMINI_API_KEY", "")
    if not key:
        raise SystemExit("GEMINI_API_KEY is required for the judge")
    labels = {row["query"]: row for row in load_jsonl(args.labels)} if args.labels else {}
    answers = {row["query"]: row for row in load_jsonl(args.answers) if row.get("status") == 200}
    done = {row["query"] for row in load_jsonl(args.out)}
    async with httpx.AsyncClient() as client:
        for reference in load_jsonl(args.reference):
            answer = answers.get(reference["query"])
            if answer is None or reference["query"] in done:
                continue
            wanted = {source for fact in reference["keyFacts"] for source in fact["sources"]}
            wanted |= set(labels.get(reference["query"], {}).get("relevant", []))
            wanted |= {source["sourceId"] for source in answer["sources"] if source.get("sourceId")}
            texts = await source_texts(client, args.project_id, args.ref_name, wanted)
            verdict = await judge_answer(client, args.judge_model, key,
                                         judge_prompt(reference, answer["answer"], texts))
            row = {**grade_row(reference, answer, verdict), "judgeModel": args.judge_model}
            append_jsonl(args.out, row)
            print(json.dumps({"query": reference["query"][:60], "factRecall": row.get("factRecall"),
                              "declined": row["declined"]}), flush=True)
            await asyncio.sleep(args.pause)


def main() -> None:
    parser = argparse.ArgumentParser(description="Answer-level evaluation of the Signals Agent")
    commands = parser.add_subparsers(dest="command", required=True)
    asking = commands.add_parser("generate", help="Ask the Agent every reference query")
    asking.add_argument("--out", required=True)
    asking.add_argument("--model", help="Agent model; defaults to the Agent's GEMINI_MODEL")
    asking.add_argument("--max-failures", type=int, default=DEFAULT_MAX_CONSECUTIVE_FAILURES,
                        help="Stop after this many Agent failures in a row; 0 never stops on them")
    grading = commands.add_parser("judge", help="Grade answers against the reference facts")
    grading.add_argument("--answers", required=True)
    grading.add_argument("--out", required=True)
    grading.add_argument("--labels", help="Retrieval labels, to show the judge every relevant source")
    grading.add_argument("--judge-model", default="gemini-3.5-flash")
    for command in (asking, grading):
        command.add_argument("--project-id", required=True)
        command.add_argument("--ref-name")
        command.add_argument("--reference", required=True)
        command.add_argument("--pause", type=float, default=4.0, help="Seconds between calls, for rate limits")
    report = commands.add_parser("summarize", help="Summarise graded answers")
    report.add_argument("--reference", required=True)
    report.add_argument("--grades", required=True)
    args = parser.parse_args()
    if args.command == "summarize":
        print(json.dumps(summarize(load_jsonl(args.reference), load_jsonl(args.grades)), indent=2))
        return
    asyncio.run(generate(args) if args.command == "generate" else judge(args))


if __name__ == "__main__":
    main()
