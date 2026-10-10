import hashlib
import json
import logging
import os
import re
import time
from datetime import date, datetime, timedelta, timezone
from typing import Any, AsyncIterator
from uuid import NAMESPACE_URL, uuid5

import httpx

from app.evidence import (EMBEDDING_BATCH_SIZE, EMBEDDING_DIMENSIONS, MAX_CHUNKS,
                          SOURCE_TYPES, EmbeddingProvider, EvidenceDocument,
                          EvidenceSearchResult, _chunks, _unit_vector,
                          insufficient_evidence_below, is_bot_author, select_evidence_hits)


logger = logging.getLogger("signals.agent.evidence")
COLLECTION = "signals_evidence"
RUN_COLLECTION = "signals_evidence_runs"
POINT_BATCH_SIZE = 50


class QdrantRequestError(Exception):
    def __init__(self, status_code: int):
        self.status_code = status_code
        super().__init__(f"Qdrant request failed with status {status_code}")


def _point_id(kind: str, key: str) -> str:
    return str(uuid5(NAMESPACE_URL, f"signals:{kind}:{key}"))


def _day(value: date | str) -> int:
    return int((value.isoformat() if isinstance(value, date) else value).replace("-", ""))


def _match(key: str, value: str) -> dict[str, Any]:
    return {"key": key, "match": {"value": value}}


class QdrantEvidenceIndex:
    def __init__(self, client: httpx.AsyncClient, url: str, api_key: str = ""):
        if not re.match(r"^https?://[^/]+", url):
            raise ValueError("QDRANT_URL must be an HTTP or HTTPS URL")
        self.client = client
        self.url = url.rstrip("/")
        self.headers = {"api-key": api_key} if api_key else {}
        self._ready: set[str] = set()

    async def _request(self, method: str, path: str, *, body: dict | None = None,
                       missing_ok: bool = False) -> dict | None:
        response = await self.client.request(method, self.url + path, headers=self.headers,
                                             json=body, timeout=60)
        if missing_ok and response.status_code == 404:
            return None
        if response.is_error:
            logger.warning("Qdrant request failed: method=%s path=%s status=%s",
                           method, path.split("?")[0], response.status_code)
            raise QdrantRequestError(response.status_code)
        return response.json()

    async def _ensure_collection(self, name: str, size: int, distance: str,
                                 indexes: dict[str, str]) -> None:
        if name in self._ready:
            return
        path = f"/collections/{name}"
        current = await self._request("GET", path, missing_ok=True)
        if current is None:
            await self._request("PUT", path, body={"vectors": {"size": size, "distance": distance},
                                                   "on_disk_payload": True})
        else:
            vectors = current["result"]["config"]["params"]["vectors"]
            if vectors.get("size") != size or vectors.get("distance") != distance:
                raise ValueError(f"Qdrant collection {name} has incompatible vector settings")
        existing_indexes = current["result"].get("payload_schema", {}) if current else {}
        for field, schema in indexes.items():
            if field not in existing_indexes:
                await self._request("PUT", f"{path}/index?wait=true",
                                    body={"field_name": field, "field_schema": schema})
        self._ready.add(name)

    async def _ensure_evidence(self) -> None:
        await self._ensure_collection(COLLECTION, EMBEDDING_DIMENSIONS, "Cosine", {
            "project_id": "keyword", "ref_name": "keyword", "event_day": "integer",
            "source_type": "keyword", "embedding_model": "keyword", "source_id": "keyword",
        })

    async def _ensure_runs(self) -> None:
        await self._ensure_collection(RUN_COLLECTION, 1, "Dot", {
            "project_id": "keyword", "ref_name": "keyword", "since_day": "integer",
            "until_day": "integer",
        })

    async def ensure_collections(self) -> None:
        await self._ensure_evidence()
        await self._ensure_runs()

    async def _get_points(self, collection: str, ids: list[str], *, vectors: bool = False) -> dict[str, dict]:
        found: dict[str, dict] = {}
        for offset in range(0, len(ids), POINT_BATCH_SIZE):
            result = await self._request("POST", f"/collections/{collection}/points",
                                         body={"ids": ids[offset:offset + POINT_BATCH_SIZE],
                                               "with_payload": True, "with_vector": vectors})
            found.update({point["id"]: point for point in result["result"]})
        return found

    async def _upsert(self, collection: str, points: list[dict]) -> None:
        for offset in range(0, len(points), POINT_BATCH_SIZE):
            await self._request("PUT", f"/collections/{collection}/points?wait=true",
                                body={"points": points[offset:offset + POINT_BATCH_SIZE]})

    async def _scroll(self, collection: str, filter_body: dict,
                      payload: bool | list[str] = True) -> AsyncIterator[dict]:
        offset: str | int | None = None
        while True:
            body: dict[str, Any] = {"filter": filter_body, "limit": 256,
                                    "with_payload": payload, "with_vector": False}
            if offset is not None:
                body["offset"] = offset
            result = (await self._request("POST", f"/collections/{collection}/points/scroll",
                                          body=body))["result"]
            for point in result["points"]:
                yield point
            next_offset = result.get("next_page_offset")
            if next_offset is None or next_offset == offset:
                break
            offset = next_offset

    def _scope_filter(self, project_id: str, ref_name: str | None) -> dict:
        return {"must": [_match("project_id", project_id), _match("ref_name", ref_name or "")]}

    def _run_id(self, project_id: str, since: date, until: date, ref_name: str | None) -> str:
        return _point_id("run", f"{project_id}:{ref_name or ''}:{since}:{until}")

    async def updated_after(self, project_id: str, since: date, until: date,
                            ref_name: str | None) -> str | None:
        await self._ensure_runs()
        points = await self._get_points(RUN_COLLECTION,
                                        [self._run_id(project_id, since, until, ref_name)])
        point = points.get(self._run_id(project_id, since, until, ref_name))
        if point is None or point["payload"]["truncated"]:
            return None
        previous = datetime.fromisoformat(point["payload"]["refreshed_at"]) - timedelta(minutes=5)
        return previous.isoformat()

    async def record_refresh(self, project_id: str, since: date, until: date,
                             ref_name: str | None, truncated: bool) -> None:
        await self._ensure_runs()
        await self._upsert(RUN_COLLECTION, [{
            "id": self._run_id(project_id, since, until, ref_name), "vector": [1.0],
            "payload": {"project_id": project_id, "ref_name": ref_name or "",
                        "since": since.isoformat(), "until": until.isoformat(),
                        "since_day": _day(since), "until_day": _day(until),
                        "refreshed_at": datetime.now(timezone.utc).isoformat(),
                        "truncated": bool(truncated)},
        }])

    async def coverage_incomplete(self, project_id: str, since: date, until: date,
                                  ref_name: str | None) -> bool:
        await self._ensure_runs()
        run_filter = self._scope_filter(project_id, ref_name)
        run_filter["must"].extend([
            {"key": "since_day", "range": {"lte": _day(since)}},
            {"key": "until_day", "range": {"gte": _day(until)}},
        ])
        newest: dict | None = None
        async for point in self._scroll(RUN_COLLECTION, run_filter):
            payload = point["payload"]
            if newest is None or payload["refreshed_at"] > newest["refreshed_at"]:
                newest = payload
        return newest is None or bool(newest["truncated"])

    async def sync(self, project_id: str, documents: list[EvidenceDocument],
                   embedding: EmbeddingProvider, ref_name: str | None = None,
                   max_chunks: int = MAX_CHUNKS) -> dict:
        start = time.monotonic()
        await self._ensure_evidence()
        scope = ref_name or ""
        chunks: list[tuple[EvidenceDocument, str, str, str]] = []
        ids_by_source: dict[str, set[str]] = {}
        truncated = False
        owner, repo = project_id.split("~")[1:]
        project_url = f"https://github.com/{owner}/{repo}".lower()
        for document in documents:
            if document.projectId != project_id or document.sourceType not in SOURCE_TYPES:
                raise ValueError("Evidence outside selected project or source types")
            if not (document.url.lower() == project_url or
                    document.url.lower().startswith(project_url + "/")):
                raise ValueError("Evidence URL is outside selected GitHub project")
            datetime.fromisoformat(document.eventAt.replace("Z", "+00:00"))
            if is_bot_author(document.author):
                continue
            contents = _chunks(document.content)
            # Skip whole documents so a partly indexed source never loses its existing chunks.
            if len(chunks) + len(contents) > max_chunks:
                truncated = True
                continue
            ids_by_source.setdefault(document.sourceId, set())
            for index, content in enumerate(contents):
                chunk_id = f"{project_id}:{scope}:{document.sourceId}:{index}"
                ids_by_source[document.sourceId].add(chunk_id)
                chunks.append((document, chunk_id, hashlib.sha256(content.encode()).hexdigest(), content))

        existing = await self._get_points(COLLECTION,
                                          [_point_id("chunk", item[1]) for item in chunks], vectors=True)
        pending = [item for item in chunks if not (point := existing.get(_point_id("chunk", item[1])))
                   or point["payload"].get("content_hash") != item[2]
                   or point["payload"].get("embedding_model") != embedding.model
                   or not isinstance(point.get("vector"), list)]
        vectors: dict[str, list[float]] = {}
        embedded_ms = 0
        for offset in range(0, len(pending), EMBEDDING_BATCH_SIZE):
            batch = pending[offset:offset + EMBEDDING_BATCH_SIZE]
            embed_start = time.monotonic()
            result = await embedding.embed_documents([item[3] for item in batch])
            embedded_ms += round((time.monotonic() - embed_start) * 1000)
            if len(result) != len(batch):
                raise ValueError("Embedding response count mismatch")
            vectors.update({item[1]: _unit_vector(vector) for item, vector in zip(batch, result)})

        points = []
        for document, chunk_id, content_hash, content in chunks:
            previous = existing.get(_point_id("chunk", chunk_id))
            vector = vectors.get(chunk_id) or _unit_vector(previous["vector"])
            points.append({"id": _point_id("chunk", chunk_id), "vector": vector,
                           "payload": {"chunk_id": chunk_id, "project_id": project_id,
                                       "ref_name": scope, "source_type": document.sourceType,
                                       "source_id": document.sourceId, "entity_id": document.entityId,
                                       "author": document.author, "created_at": document.createdAt,
                                       "updated_at": document.updatedAt,
                                       "event_date": document.eventAt[:10],
                                       "event_day": _day(document.eventAt[:10]),
                                       "url": document.url, "labels": document.labels,
                                       "content": content, "content_hash": content_hash,
                                       "embedding_model": embedding.model}})
        await self._upsert(COLLECTION, points)

        stale: set[str] = set()
        if ids_by_source:
            source_filter = self._scope_filter(project_id, scope)
            source_filter["must"].append({"key": "source_id",
                                          "match": {"any": list(ids_by_source)}})
            async for point in self._scroll(COLLECTION, source_filter, ["chunk_id", "source_id"]):
                payload = point["payload"]
                if payload["chunk_id"] not in ids_by_source[payload["source_id"]]:
                    stale.add(point["id"])
        async for point in self._scroll(COLLECTION, self._scope_filter(project_id, scope), ["author"]):
            payload = point["payload"]
            if is_bot_author(payload.get("author", "")):
                stale.add(point["id"])
        stale_ids = list(stale)
        for offset in range(0, len(stale_ids), POINT_BATCH_SIZE):
            await self._request("POST", f"/collections/{COLLECTION}/points/delete?wait=true",
                                body={"points": stale_ids[offset:offset + POINT_BATCH_SIZE]})
        result = {"sources": len(ids_by_source), "embeddedChunks": len(pending),
                  "unchangedChunks": len(chunks) - len(pending), "truncated": truncated}
        logger.info(json.dumps({"event": "evidence_index", "project_id": project_id,
                                "ref_name": scope, **result, "embedding_ms": embedded_ms,
                                "duration_ms": round((time.monotonic() - start) * 1000)}))
        return result

    async def search(self, project_id: str, query: str, since: date, until: date,
                     source_types: list[str] | None, top_k: int,
                     embedding: EmbeddingProvider, ref_name: str | None = None) -> dict:
        start = time.monotonic()
        if not 1 <= top_k <= 8 or since > until:
            raise ValueError("Invalid evidence search range or size")
        if source_types and not set(source_types) <= SOURCE_TYPES:
            raise ValueError("Invalid evidence source type")
        await self._ensure_evidence()
        scope_filter = self._scope_filter(project_id, ref_name)
        scope_filter["must"].extend([
            {"key": "event_day", "range": {"gte": _day(since), "lte": _day(until)}},
            _match("embedding_model", embedding.model),
        ])
        if source_types:
            scope_filter["must"].append({"key": "source_type", "match": {"any": source_types}})
        count = (await self._request("POST", f"/collections/{COLLECTION}/points/count",
                                     body={"filter": scope_filter, "exact": True}))["result"]["count"]
        items = []
        query_limit = min(count, 256)
        if count:
            vector = await embedding.embed_query(query)
            result = await self._request("POST", f"/collections/{COLLECTION}/points/query",
                                         body={"query": vector, "filter": scope_filter,
                                               "limit": query_limit, "score_threshold": 0.30,
                                               "with_payload": True, "with_vector": False})
            candidates = []
            for point in result["result"]["points"]:
                payload = point["payload"]
                candidates.append({"chunkId": payload["chunk_id"], "projectId": project_id,
                              "refName": ref_name or "", "sourceType": payload["source_type"],
                              "sourceId": payload["source_id"], "entityId": payload["entity_id"],
                              "author": payload["author"], "createdAt": payload["created_at"],
                              "updatedAt": payload["updated_at"],
                              "eventDate": payload["event_date"], "url": payload["url"],
                              "labels": payload["labels"], "content": payload["content"],
                              "score": round(point["score"], 4)})
            items = select_evidence_hits(candidates, top_k)
        result = {"items": items, "candidateCount": count,
                  "insufficientEvidence": not items or items[0]["score"] < insufficient_evidence_below(
                      embedding.model),
                  "candidateLimitReached": count > query_limit,
                  "coverageIncomplete": await self.coverage_incomplete(project_id, since, until,
                                                                        ref_name)}
        logger.info(json.dumps({"event": "evidence_search", "project_id": project_id,
                                "ref_name": ref_name or "", "since": since.isoformat(),
                                "until": until.isoformat(), "candidate_count": count,
                                "coverage_incomplete": result["coverageIncomplete"],
                                "source_ids": [item["sourceId"] for item in items],
                                "scores": [item["score"] for item in items],
                                "duration_ms": round((time.monotonic() - start) * 1000)}))
        return EvidenceSearchResult.model_validate(result).model_dump()


def configured_index(client: httpx.AsyncClient) -> QdrantEvidenceIndex:
    return QdrantEvidenceIndex(client, os.getenv("QDRANT_URL", ""), os.getenv("QDRANT_API_KEY", ""))
