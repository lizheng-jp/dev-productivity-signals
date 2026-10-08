"""Offline retrieval evaluation for the Qdrant evidence index.

Step 1 lists what is indexed so you can label it:

    python -m app.retrieval_eval candidates --project-id github~owner~repo \
        --since 2026-07-01 --until 2026-09-26 --ref-name main > candidates.jsonl

Step 2 scores labelled queries. Each line of the labels file is
{"query": "...", "since": "YYYY-MM-DD", "until": "YYYY-MM-DD", "relevant": ["mr:17:description", ...]}
with optional "sourceTypes". An empty "relevant" list marks a query the index cannot answer; for those
the search should report insufficientEvidence.

    python -m app.retrieval_eval run --project-id github~owner~repo --ref-name main \
        --labels eval/labels.jsonl

Recall and MRR are computed on what the agent would receive: after the score threshold, bot filter and
per-entity diversification. Labels made only from retrieved results inflate recall; label from the
candidate list instead.
"""
import argparse
import asyncio
import json
import os
from datetime import date
from statistics import mean
from typing import Any

import httpx

from app.evidence import configured_embedding
from app.qdrant_evidence import COLLECTION, _day, configured_index

TOP_K = 8
CUTOFFS = (1, 3, 5, 8)


def recall_at_k(retrieved: list[str], relevant: set[str], k: int) -> float:
    return len(set(retrieved[:k]) & relevant) / len(relevant)


def reciprocal_rank(retrieved: list[str], relevant: set[str]) -> float:
    return next((1 / rank for rank, item in enumerate(retrieved, 1) if item in relevant), 0.0)


def summarize(results: list[dict[str, Any]]) -> dict[str, Any]:
    answerable = [item for item in results if item["relevant"]]
    unanswerable = [item for item in results if not item["relevant"]]
    relevant_scores = [hit["score"] for item in results for hit in item["hits"] if hit["relevant"]]
    other_scores = [hit["score"] for item in results for hit in item["hits"] if not hit["relevant"]]
    summary: dict[str, Any] = {"queries": len(results), "answerable": len(answerable),
                               "unanswerable": len(unanswerable)}
    if answerable:
        for k in CUTOFFS:
            summary[f"recall@{k}"] = round(mean(item[f"recall@{k}"] for item in answerable), 4)
        summary["mrr"] = round(mean(item["rr"] for item in answerable), 4)
        summary["flaggedInsufficientWhenAnswerable"] = sum(
            item["insufficientEvidence"] for item in answerable)
    if unanswerable:
        summary["confidentWhenUnanswerable"] = sum(
            not item["insufficientEvidence"] for item in unanswerable)
    summary["meanScoreRelevant"] = round(mean(relevant_scores), 4) if relevant_scores else None
    summary["meanScoreOther"] = round(mean(other_scores), 4) if other_scores else None
    return summary


def score_query(label: dict[str, Any], search: dict[str, Any]) -> dict[str, Any]:
    relevant = set(label["relevant"])
    retrieved = [item["sourceId"] for item in search["items"]]
    result: dict[str, Any] = {
        "query": label["query"], "relevant": sorted(relevant),
        "insufficientEvidence": search["insufficientEvidence"],
        "coverageIncomplete": search["coverageIncomplete"],
        "hits": [{"sourceId": item["sourceId"], "score": item["score"],
                  "relevant": item["sourceId"] in relevant} for item in search["items"]],
    }
    if relevant:
        for k in CUTOFFS:
            result[f"recall@{k}"] = recall_at_k(retrieved, relevant, k)
        result["rr"] = reciprocal_rank(retrieved, relevant)
    return result


async def candidates(args: argparse.Namespace) -> None:
    async with httpx.AsyncClient() as client:
        index = configured_index(client)
        scope = index._scope_filter(args.project_id, args.ref_name)
        scope["must"].append({"key": "event_day",
                              "range": {"gte": _day(args.since), "lte": _day(args.until)}})
        seen: set[str] = set()
        async for point in index._scroll(COLLECTION, scope):
            payload = point["payload"]
            if payload["source_id"] in seen:
                continue
            seen.add(payload["source_id"])
            print(json.dumps({"sourceId": payload["source_id"], "sourceType": payload["source_type"],
                              "eventDate": payload["event_date"], "url": payload["url"],
                              "preview": " ".join(payload["content"].split())[:200]},
                             ensure_ascii=False))


async def run(args: argparse.Namespace) -> None:
    with open(args.labels, encoding="utf-8") as handle:
        labels = [json.loads(line) for line in handle if line.strip()]
    results = []
    async with httpx.AsyncClient() as client:
        index = configured_index(client)
        embedding = configured_embedding(client)
        for label in labels:
            search = await index.search(args.project_id, label["query"],
                                        date.fromisoformat(label["since"]),
                                        date.fromisoformat(label["until"]),
                                        label.get("sourceTypes"), TOP_K, embedding, args.ref_name)
            results.append(score_query(label, search))
    output = {"summary": summarize(results), "queries": results}
    print(json.dumps(output, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description="Offline retrieval evaluation for the evidence index")
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("candidates", help="List indexed sources to label")
    listing.add_argument("--since", type=date.fromisoformat, required=True)
    listing.add_argument("--until", type=date.fromisoformat, required=True)
    scoring = commands.add_parser("run", help="Score labelled queries")
    scoring.add_argument("--labels", required=True)
    for command in (listing, scoring):
        command.add_argument("--project-id", required=True)
        command.add_argument("--ref-name")
    args = parser.parse_args()
    if not args.project_id.startswith("github~") or len(args.project_id.split("~")) != 3:
        parser.error("--project-id must be a github~owner~repo project")
    if (args.command == "run" and os.getenv("EMBEDDING_PROVIDER", "gemini").strip().lower() == "gemini"
            and not os.getenv("GEMINI_API_KEY")):
        parser.error("GEMINI_API_KEY is required to embed queries unless EMBEDDING_PROVIDER=local")
    asyncio.run(candidates(args) if args.command == "candidates" else run(args))


if __name__ == "__main__":
    main()
