import argparse
import asyncio
import json
import os
from datetime import date
from urllib.parse import quote

import httpx


QUESTIONS = (
    "Why did review wait time increase in this project?",
    "Why did merge lead time change in the selected period?",
    "Which recent pull request discussions mention review bottlenecks?",
    "Which issues may be related to the recent productivity change?",
    "Are maintainer approval delays recurring in recent pull requests?",
)


async def evaluate(project_id: str, since: date, until: date, ref_name: str | None,
                   index_first: bool, index_only: bool, full_index: bool = False,
                   sample_scale: int = 1) -> None:
    key = os.environ.get("AGENT_INTERNAL_KEY", "")
    if not key:
        raise SystemExit("AGENT_INTERNAL_KEY is required")
    base = "http://127.0.0.1:8000"
    headers = {"X-Agent-Internal-Key": key}
    async with httpx.AsyncClient(timeout=300) as client:
        if index_first or index_only:
            response = await client.post(
                f"{base}/index/projects/{quote(project_id, safe='')}", headers=headers,
                json={"since": since.isoformat(), "until": until.isoformat(),
                      "refName": ref_name, "fullRefresh": full_index,
                      "sampleScale": sample_scale})
            response.raise_for_status()
            print(json.dumps({"index": response.json()}, ensure_ascii=False))
        if index_only:
            return
        owner, repo = project_id.split("~")[1:]
        project_url = f"https://github.com/{owner}/{repo}"
        for question in QUESTIONS:
            response = await client.post(f"{base}/ask", headers=headers, json={
                "question": question, "projectId": project_id,
                "since": since.isoformat(), "until": until.isoformat(),
                "refName": ref_name,
            })
            payload = response.json()
            sources = payload.get("sources", [])
            def belongs_to_project(source):
                url = source.get("projectUrl", "").lower()
                expected = project_url.lower()
                return url == expected or url.startswith(expected + "/")

            print(json.dumps({
                "question": question, "status": response.status_code,
                "answer": payload.get("answer", ""),
                "executionId": payload.get("executionId"),
                "tools": list(dict.fromkeys(source.get("tool") for source in sources)),
                "projectCorrect": all(belongs_to_project(source) for source in sources),
                "dateCorrect": all(since.isoformat() <= source["eventDate"] <= until.isoformat()
                                   for source in sources if source.get("eventDate")),
                "sources": sources,
            }, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect five real-project Agent/RAG questions")
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--since", type=date.fromisoformat, required=True)
    parser.add_argument("--until", type=date.fromisoformat, required=True)
    parser.add_argument("--ref-name")
    parser.add_argument("--index", action="store_true", help="Refresh evidence before evaluation")
    parser.add_argument("--index-only", action="store_true", help="Refresh evidence without asking the model")
    parser.add_argument("--full-index", action="store_true", help="Ignore the last refresh timestamp")
    parser.add_argument("--sample-scale", type=int, default=1, choices=range(1, 6),
                        help="Index a wider sample for retrieval evaluation (multiplies PR, issue and chunk limits)")
    args = parser.parse_args()
    if not args.project_id.startswith("github~") or len(args.project_id.split("~")) != 3:
        parser.error("--project-id must be a github~owner~repo project")
    if args.full_index and not (args.index or args.index_only):
        parser.error("--full-index requires --index or --index-only")
    asyncio.run(evaluate(args.project_id, args.since, args.until, args.ref_name,
                         args.index, args.index_only, args.full_index, args.sample_scale))


if __name__ == "__main__":
    main()
