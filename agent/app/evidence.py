import asyncio
import functools
import hashlib
from contextlib import closing
import json
import logging
import math
import os
import random
import sqlite3
import struct
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Protocol

import httpx
from pydantic import BaseModel, Field


logger = logging.getLogger("signals.agent.evidence")
SOURCE_TYPES = frozenset({"mr_description", "mr_discussion", "issue", "issue_comment"})
EMBEDDING_DIMENSIONS = 768
MAX_CHUNKS = 250
EMBEDDING_BATCH_SIZE = 50
EMBEDDING_ATTEMPTS = 3
# Questions keep the model gateway's policy of not waiting on 429.
QUERY_RETRY_STATUSES = frozenset({500, 502, 503, 504})
DOCUMENT_RETRY_STATUSES = QUERY_RETRY_STATUSES | {429}


class EvidenceDocument(BaseModel):
    projectId: str
    sourceType: str
    sourceId: str
    entityId: int
    content: str
    author: str = ""
    createdAt: str
    updatedAt: str
    eventAt: str
    url: str
    labels: list[str] = Field(default_factory=list)


class EvidenceHit(BaseModel):
    chunkId: str
    projectId: str
    refName: str
    sourceType: str
    sourceId: str
    entityId: int
    author: str
    createdAt: str
    updatedAt: str
    eventDate: str
    url: str
    labels: list[str]
    content: str
    score: float


class EvidenceSearchResult(BaseModel):
    items: list[EvidenceHit]
    candidateCount: int
    insufficientEvidence: bool
    candidateLimitReached: bool
    coverageIncomplete: bool


class EmbeddingProvider(Protocol):
    model: str

    async def embed_documents(self, texts: list[str]) -> list[list[float]]: ...

    async def embed_query(self, text: str) -> list[float]: ...


class GeminiEmbeddingProvider:
    def __init__(self, client: httpx.AsyncClient, key: str,
                 model: str = "gemini-embedding-001"):
        self.client = client
        self.key = key
        self.model = model

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        url = ("https://generativelanguage.googleapis.com/v1beta/models/"
               f"{self.model}:batchEmbedContents")
        requests = [{
            "model": f"models/{self.model}",
            "content": {"parts": [{"text": text}]},
            "taskType": "RETRIEVAL_DOCUMENT",
            "outputDimensionality": EMBEDDING_DIMENSIONS,
        } for text in texts]
        # Indexing is offline, so it also waits out short free-tier rate limits.
        response = await self._post(url, {"requests": requests}, DOCUMENT_RETRY_STATUSES)
        embeddings = response.json().get("embeddings", [])
        if len(embeddings) != len(texts):
            raise ValueError("Embedding response count mismatch")
        return [_unit_vector(item.get("values", [])) for item in embeddings]

    async def embed_query(self, text: str) -> list[float]:
        url = ("https://generativelanguage.googleapis.com/v1beta/models/"
               f"{self.model}:embedContent")
        response = await self._post(url, {
            "model": f"models/{self.model}",
            "content": {"parts": [{"text": text}]},
            "taskType": "RETRIEVAL_QUERY",
            "outputDimensionality": EMBEDDING_DIMENSIONS,
        }, QUERY_RETRY_STATUSES)
        return _unit_vector(response.json().get("embedding", {}).get("values", []))

    async def _post(self, url: str, body: dict, retry_statuses: frozenset[int]) -> httpx.Response:
        for attempt in range(EMBEDDING_ATTEMPTS):
            try:
                response = await self.client.post(url, headers={"x-goog-api-key": self.key},
                                                  json=body, timeout=45)
                response.raise_for_status()
                return response
            except httpx.HTTPStatusError as error:
                status = error.response.status_code
                if status not in retry_statuses or attempt == EMBEDDING_ATTEMPTS - 1:
                    raise
                logger.info(json.dumps({"event": "embedding_retry", "status_code": status,
                                        "attempt": attempt + 1}))
            except (httpx.TimeoutException, httpx.NetworkError):
                if attempt == EMBEDDING_ATTEMPTS - 1:
                    raise
                logger.info(json.dumps({"event": "embedding_retry", "error_type": "network",
                                        "attempt": attempt + 1}))
            await asyncio.sleep(2 ** attempt + random.uniform(0, 0.25))
        raise AssertionError("unreachable")


DEFAULT_LOCAL_MODEL = "BAAI/bge-base-en-v1.5"
# Models without a query prompt in their sentence-transformers config need the prefix their card asks for.
QUERY_PREFIXES = {
    "BAAI/bge-base-en-v1.5": "Represent this sentence for searching relevant passages: ",
    "BAAI/bge-small-en-v1.5": "Represent this sentence for searching relevant passages: ",
    "intfloat/multilingual-e5-base": "query: ",
}
DOCUMENT_PREFIXES = {"intfloat/multilingual-e5-base": "passage: "}


@functools.lru_cache(maxsize=2)
def _load_local_model(model: str):
    from sentence_transformers import SentenceTransformer

    loaded = SentenceTransformer(model, device="cpu", truncate_dim=EMBEDDING_DIMENSIONS)
    native = loaded.get_embedding_dimension()
    if native is not None and native < EMBEDDING_DIMENSIONS:
        raise ValueError(f"{model} produces {native}-dimensional vectors; "
                         f"the index needs {EMBEDDING_DIMENSIONS}")
    return loaded


class LocalEmbeddingProvider:
    """Embeds on CPU with sentence-transformers; larger models are truncated to the index dimension."""

    def __init__(self, model: str = DEFAULT_LOCAL_MODEL, loader=_load_local_model):
        self.model = model
        self._loader = loader

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        prefix = DOCUMENT_PREFIXES.get(self.model, "")
        return await asyncio.to_thread(self._encode, [prefix + text for text in texts], None)

    async def embed_query(self, text: str) -> list[float]:
        model = self._loader(self.model)
        if "query" in getattr(model, "prompts", {}):
            return (await asyncio.to_thread(self._encode, [text], "query"))[0]
        return (await asyncio.to_thread(self._encode, [QUERY_PREFIXES.get(self.model, "") + text], None))[0]

    def _encode(self, texts: list[str], prompt_name: str | None) -> list[list[float]]:
        vectors = self._loader(self.model).encode(texts, prompt_name=prompt_name,
                                                  batch_size=16, convert_to_numpy=True)
        return [_unit_vector([float(value) for value in vector]) for vector in vectors]


def configured_embedding(client: httpx.AsyncClient) -> EmbeddingProvider | None:
    """EMBEDDING_PROVIDER=gemini (default) needs GEMINI_API_KEY; local runs EMBEDDING_MODEL on CPU."""
    provider = os.getenv("EMBEDDING_PROVIDER", "gemini").strip().lower()
    if provider == "local":
        return LocalEmbeddingProvider(os.getenv("EMBEDDING_MODEL") or DEFAULT_LOCAL_MODEL)
    if provider != "gemini":
        raise ValueError(f"Unknown EMBEDDING_PROVIDER: {provider}")
    key = os.getenv("GEMINI_API_KEY", "")
    return GeminiEmbeddingProvider(client, key) if key else None


def _unit_vector(values: list[float]) -> list[float]:
    if len(values) != EMBEDDING_DIMENSIONS or not all(math.isfinite(value) for value in values):
        raise ValueError("Invalid embedding")
    magnitude = math.sqrt(sum(value * value for value in values))
    if not magnitude:
        raise ValueError("Empty embedding")
    return [value / magnitude for value in values]


def _chunks(content: str, size: int = 1200) -> list[str]:
    paragraphs = [part.strip() for part in content.splitlines() if part.strip()]
    result: list[str] = []
    current = ""
    for paragraph in paragraphs:
        while len(paragraph) > size:
            if current:
                result.append(current)
                current = ""
            split = paragraph.rfind(" ", 0, size)
            if split < size // 2:
                split = size
            result.append(paragraph[:split].strip())
            paragraph = paragraph[split:].strip()
        if not paragraph:
            continue
        if current and len(current) + len(paragraph) + 1 > size:
            result.append(current)
            current = ""
        current = f"{current}\n{paragraph}" if current else paragraph
    if current:
        result.append(current)
    return result


def is_bot_author(author: str) -> bool:
    return author.strip().lower().endswith("[bot]")


def select_evidence_hits(candidates: list[dict], top_k: int) -> list[dict]:
    selected: list[dict] = []
    source_ids: set[str] = set()
    entity_counts: dict[tuple[str, int], int] = {}
    for item in candidates:
        if item["score"] < 0.30 or is_bot_author(item["author"]):
            continue
        entity = (item["sourceType"].split("_")[0], item["entityId"])
        if item["sourceId"] in source_ids or entity_counts.get(entity, 0) >= 2:
            continue
        selected.append(item)
        source_ids.add(item["sourceId"])
        entity_counts[entity] = entity_counts.get(entity, 0) + 1
        if len(selected) == top_k:
            break
    return selected


class EvidenceIndex:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as database, database:
            database.executescript("""
                CREATE TABLE IF NOT EXISTS evidence (
                    chunk_id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    ref_name TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    entity_id INTEGER NOT NULL,
                    author TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    event_date TEXT NOT NULL,
                    url TEXT NOT NULL,
                    labels TEXT NOT NULL,
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    embedding_model TEXT NOT NULL,
                    embedding BLOB NOT NULL
                );
                CREATE TABLE IF NOT EXISTS index_runs (
                    project_id TEXT NOT NULL,
                    since TEXT NOT NULL,
                    until TEXT NOT NULL,
                    ref_name TEXT NOT NULL,
                    refreshed_at TEXT NOT NULL,
                    truncated INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (project_id, since, until, ref_name)
                );
            """)
            columns = {row[1] for row in database.execute("PRAGMA table_info(evidence)")}
            if "ref_name" not in columns:
                database.execute("ALTER TABLE evidence ADD COLUMN ref_name TEXT NOT NULL DEFAULT ''")
            database.execute("""
                CREATE INDEX IF NOT EXISTS evidence_scope_filter
                    ON evidence(project_id, ref_name, event_date, source_type)
            """)
            run_columns = {row[1] for row in database.execute("PRAGMA table_info(index_runs)")}
            if "truncated" not in run_columns:
                database.execute("ALTER TABLE index_runs ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0")

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path)

    def updated_after(self, project_id: str, since: date, until: date,
                      ref_name: str | None) -> str | None:
        with closing(self._connect()) as database:
            row = database.execute("""
                SELECT refreshed_at, truncated FROM index_runs
                WHERE project_id=? AND since=? AND until=? AND ref_name=?
            """, (project_id, since.isoformat(), until.isoformat(), ref_name or "")).fetchone()
        if not row or row[1]:
            return None
        previous = datetime.fromisoformat(row[0]) - timedelta(minutes=5)
        return previous.isoformat()

    def record_refresh(self, project_id: str, since: date, until: date,
                       ref_name: str | None, truncated: bool) -> None:
        with closing(self._connect()) as database, database:
            database.execute("""
                INSERT INTO index_runs VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, since, until, ref_name)
                DO UPDATE SET refreshed_at=excluded.refreshed_at, truncated=excluded.truncated
            """, (project_id, since.isoformat(), until.isoformat(), ref_name or "",
                  datetime.now(timezone.utc).isoformat(), int(truncated)))

    def coverage_incomplete(self, project_id: str, since: date, until: date,
                            ref_name: str | None) -> bool:
        with closing(self._connect()) as database:
            row = database.execute("""
                SELECT truncated FROM index_runs
                WHERE project_id=? AND ref_name=? AND since<=? AND until>=?
                ORDER BY refreshed_at DESC LIMIT 1
            """, (project_id, ref_name or "", since.isoformat(), until.isoformat())).fetchone()
        return row is None or bool(row[0])

    async def sync(self, project_id: str, documents: list[EvidenceDocument],
                   embedding: EmbeddingProvider, ref_name: str | None = None) -> dict:
        start = time.monotonic()
        scope = ref_name or ""
        pending: list[tuple[EvidenceDocument, str, str, str]] = []
        unchanged: list[tuple[EvidenceDocument, str]] = []
        ids_by_source: dict[str, set[str]] = {}
        truncated = False
        with closing(self._connect()) as database:
            existing = {row[0]: (row[1], row[2]) for row in database.execute(
                "SELECT chunk_id, content_hash, embedding_model FROM evidence "
                "WHERE project_id=? AND ref_name=?", (project_id, scope))}
        for document in documents:
            if document.projectId != project_id or document.sourceType not in SOURCE_TYPES:
                raise ValueError("Evidence outside selected project or source types")
            owner, repo = project_id.split("~")[1:]
            project_url = f"https://github.com/{owner}/{repo}".lower()
            if not (document.url.lower() == project_url or
                    document.url.lower().startswith(project_url + "/")):
                raise ValueError("Evidence URL is outside selected GitHub project")
            datetime.fromisoformat(document.eventAt.replace("Z", "+00:00"))
            if is_bot_author(document.author):
                continue
            contents = _chunks(document.content)
            if len(pending) + len(unchanged) + len(contents) > MAX_CHUNKS:
                truncated = True
                continue
            for index, content in enumerate(contents):
                chunk_id = f"{project_id}:{scope}:{document.sourceId}:{index}"
                ids_by_source.setdefault(document.sourceId, set()).add(chunk_id)
                content_hash = hashlib.sha256(content.encode()).hexdigest()
                if existing.get(chunk_id) != (content_hash, embedding.model):
                    pending.append((document, chunk_id, content_hash, content))
                else:
                    unchanged.append((document, chunk_id))

        embedded_ms = 0
        for offset in range(0, len(pending), EMBEDDING_BATCH_SIZE):
            batch = pending[offset:offset + EMBEDDING_BATCH_SIZE]
            embed_start = time.monotonic()
            vectors = await embedding.embed_documents([item[3] for item in batch])
            embedded_ms += round((time.monotonic() - embed_start) * 1000)
            if len(vectors) != len(batch):
                raise ValueError("Embedding response count mismatch")
            with closing(self._connect()) as database, database:
                for (document, chunk_id, content_hash, content), vector in zip(batch, vectors):
                    vector = _unit_vector(vector)
                    database.execute("""
                        INSERT INTO evidence (
                            chunk_id, project_id, ref_name, source_type, source_id, entity_id,
                            author, created_at, updated_at, event_date, url, labels,
                            content, content_hash, embedding_model, embedding
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(chunk_id) DO UPDATE SET
                            author=excluded.author, created_at=excluded.created_at,
                            updated_at=excluded.updated_at, event_date=excluded.event_date,
                            url=excluded.url, labels=excluded.labels, content=excluded.content,
                            content_hash=excluded.content_hash,
                            embedding_model=excluded.embedding_model, embedding=excluded.embedding
                    """, (chunk_id, project_id, scope, document.sourceType, document.sourceId,
                          document.entityId, document.author, document.createdAt,
                          document.updatedAt, document.eventAt[:10], document.url,
                          json.dumps(document.labels), content, content_hash, embedding.model,
                          struct.pack(f"<{EMBEDDING_DIMENSIONS}f", *vector)))
        with closing(self._connect()) as database, database:
            for document, chunk_id in unchanged:
                database.execute("""
                    UPDATE evidence SET author=?, created_at=?, updated_at=?, event_date=?,
                        url=?, labels=? WHERE chunk_id=?
                """, (document.author, document.createdAt, document.updatedAt,
                      document.eventAt[:10], document.url, json.dumps(document.labels), chunk_id))
            for source_id, valid_ids in ids_by_source.items():
                for (chunk_id,) in database.execute(
                    "SELECT chunk_id FROM evidence WHERE project_id=? AND ref_name=? AND source_id=?",
                    (project_id, scope, source_id)).fetchall():
                    if chunk_id not in valid_ids:
                        database.execute("DELETE FROM evidence WHERE chunk_id=?", (chunk_id,))
            database.execute("""
                DELETE FROM evidence WHERE project_id=? AND ref_name=?
                AND lower(author) LIKE '%[bot]'
            """, (project_id, scope))
        result = {"sources": len(ids_by_source), "embeddedChunks": len(pending),
                  "unchangedChunks": sum(map(len, ids_by_source.values())) - len(pending),
                  "truncated": truncated}
        logger.info(json.dumps({"event": "evidence_index", "project_id": project_id,
                                "ref_name": scope,
                                **result, "embedding_ms": embedded_ms,
                                "duration_ms": round((time.monotonic() - start) * 1000)}))
        return result

    async def search(self, project_id: str, query: str, since: date, until: date,
                     source_types: list[str] | None, top_k: int,
                     embedding: EmbeddingProvider, ref_name: str | None = None) -> dict:
        start = time.monotonic()
        scope = ref_name or ""
        if not 1 <= top_k <= 8 or since > until:
            raise ValueError("Invalid evidence search range or size")
        if source_types and not set(source_types) <= SOURCE_TYPES:
            raise ValueError("Invalid evidence source type")
        sql = ("SELECT chunk_id, source_type, source_id, entity_id, author, created_at, "
               "updated_at, event_date, url, labels, content, embedding FROM evidence "
               "WHERE project_id=? AND ref_name=? AND event_date BETWEEN ? AND ? "
               "AND embedding_model=?")
        params: list[str] = [project_id, scope, since.isoformat(), until.isoformat(), embedding.model]
        if source_types:
            sql += " AND source_type IN (" + ",".join("?" for _ in source_types) + ")"
            params.extend(source_types)
        sql += " ORDER BY event_date DESC LIMIT 2000"
        with closing(self._connect()) as database:
            rows = database.execute(sql, params).fetchall()
        if not rows:
            result = {"items": [], "candidateCount": 0, "insufficientEvidence": True,
                      "candidateLimitReached": False}
        else:
            vector = await embedding.embed_query(query)
            scored = []
            for row in rows:
                candidate = struct.unpack(f"<{EMBEDDING_DIMENSIONS}f", row[11])
                score = sum(a * b for a, b in zip(vector, candidate))
                scored.append((score, row))
            scored.sort(key=lambda item: item[0], reverse=True)
            candidates = [{"chunkId": row[0], "projectId": project_id, "refName": scope,
                      "sourceType": row[1], "sourceId": row[2],
                      "entityId": row[3], "author": row[4], "createdAt": row[5],
                      "updatedAt": row[6], "eventDate": row[7], "url": row[8],
                      "labels": json.loads(row[9]), "content": row[10],
                      "score": round(score, 4)}
                     for score, row in scored]
            items = select_evidence_hits(candidates, top_k)
            result = {"items": items, "candidateCount": len(rows),
                      "insufficientEvidence": not items or items[0]["score"] < 0.40,
                      "candidateLimitReached": len(rows) == 2000}
        result["coverageIncomplete"] = self.coverage_incomplete(project_id, since, until, ref_name)
        logger.info(json.dumps({"event": "evidence_search", "project_id": project_id,
                                "ref_name": scope,
                                "query": query[:120], "since": since.isoformat(),
                                "until": until.isoformat(), "source_types": source_types,
                                "candidate_count": result["candidateCount"],
                                "coverage_incomplete": result["coverageIncomplete"],
                                "top_k": top_k,
                                "source_ids": [item["sourceId"] for item in result["items"]],
                                "returned_source_types": [item["sourceType"] for item in result["items"]],
                                "scores": [item["score"] for item in result["items"]],
                                "duration_ms": round((time.monotonic() - start) * 1000)}))
        return EvidenceSearchResult.model_validate(result).model_dump()
