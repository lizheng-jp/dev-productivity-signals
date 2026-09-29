import hashlib
import unittest
from datetime import date
from unittest.mock import AsyncMock

import httpx

from app.evidence import EMBEDDING_DIMENSIONS, EvidenceDocument
from app.qdrant_evidence import QdrantEvidenceIndex, QdrantRequestError, _point_id


PROJECT = "github~openai~openai-java"
VECTOR = [1.0] + [0.0] * (EMBEDDING_DIMENSIONS - 1)


class NoEmbedding:
    model = "gemini-embedding-001"

    async def embed_documents(self, texts):
        raise AssertionError("Unchanged content must reuse its vector")

    async def embed_query(self, text):
        raise AssertionError("Empty search scope must not call Gemini")


class QueryEmbedding(NoEmbedding):
    async def embed_query(self, text):
        return VECTOR


class QdrantEvidenceTests(unittest.IsolatedAsyncioTestCase):
    async def test_existing_collection_repairs_missing_payload_index(self):
        index = QdrantEvidenceIndex(None, "http://qdrant:6333")
        index._request = AsyncMock(side_effect=[{
            "result": {"config": {"params": {"vectors": {"size": 768, "distance": "Cosine"}}},
                       "payload_schema": {"project_id": {"data_type": "keyword"}}},
        }, {}])

        await index._ensure_collection("signals_evidence", 768, "Cosine", {
            "project_id": "keyword", "ref_name": "keyword",
        })

        self.assertEqual(index._request.await_count, 2)
        self.assertEqual(index._request.await_args.kwargs["body"]["field_name"], "ref_name")

    async def test_sync_reuses_imported_vector_and_deletes_stale_chunk(self):
        index = QdrantEvidenceIndex(None, "http://qdrant:6333")
        index._ensure_evidence = AsyncMock()
        index._upsert = AsyncMock()
        index._request = AsyncMock(return_value={})
        document = EvidenceDocument(
            projectId=PROJECT, sourceType="mr_discussion", sourceId="mr:1:note:2",
            entityId=1, content="Review blocked", author="octocat",
            createdAt="2026-09-20T00:00:00Z", updatedAt="2026-09-20T00:00:00Z",
            eventAt="2026-09-20T00:00:00Z",
            url="https://github.com/openai/openai-java/pull/1", labels=[],
        )
        chunk_id = f"{PROJECT}:main:mr:1:note:2:0"
        index._get_points = AsyncMock(return_value={_point_id("chunk", chunk_id): {
            "payload": {"content_hash": hashlib.sha256(b"Review blocked").hexdigest(),
                        "embedding_model": NoEmbedding.model},
            "vector": VECTOR,
        }})

        async def stale_scroll(*args, **kwargs):
            yield {"id": "stale-id", "payload": {"chunk_id": chunk_id + ":1",
                                                   "source_id": document.sourceId}}

        index._scroll = stale_scroll
        result = await index.sync(PROJECT, [document], NoEmbedding(), "main")

        self.assertEqual(result, {"sources": 1, "embeddedChunks": 0, "unchangedChunks": 1})
        self.assertEqual(index._upsert.await_args.args[1][0]["vector"], VECTOR)
        self.assertEqual(index._request.await_args.kwargs["body"], {"points": ["stale-id"]})

    async def test_empty_scope_skips_query_embedding(self):
        index = QdrantEvidenceIndex(None, "http://qdrant:6333")
        index._ensure_evidence = AsyncMock()
        index._request = AsyncMock(return_value={"result": {"count": 0}})
        index.coverage_incomplete = AsyncMock(return_value=True)

        result = await index.search(PROJECT, "review", date(2026, 9, 20),
                                    date(2026, 9, 27), ["mr_discussion"], 5,
                                    NoEmbedding(), "main")

        self.assertEqual(result["candidateCount"], 0)
        self.assertTrue(result["insufficientEvidence"])
        must = index._request.await_args.kwargs["body"]["filter"]["must"]
        self.assertIn({"key": "project_id", "match": {"value": PROJECT}}, must)
        self.assertIn({"key": "ref_name", "match": {"value": "main"}}, must)
        self.assertIn({"key": "source_type", "match": {"any": ["mr_discussion"]}}, must)

    async def test_search_skips_legacy_bot_and_returns_human_evidence(self):
        index = QdrantEvidenceIndex(None, "http://qdrant:6333")
        index._ensure_evidence = AsyncMock()
        index.coverage_incomplete = AsyncMock(return_value=False)

        def response(method, path, *, body=None, missing_ok=False):
            if path.endswith("/count"):
                return {"result": {"count": 2}}
            self.assertEqual(body["limit"], 2)
            def point(author, source_id, score):
                return {"score": score, "payload": {
                    "chunk_id": source_id, "source_type": "mr_discussion",
                    "source_id": source_id, "entity_id": 17, "author": author,
                    "created_at": "2026-09-20T00:00:00Z", "updated_at": "2026-09-20T00:00:00Z",
                    "event_date": "2026-09-20", "url": "https://github.com/openai/openai-java/pull/17",
                    "labels": [], "content": "Review delay",
                }}
            return {"result": {"points": [point("reviewer[bot]", "bot", 0.95),
                                          point("alice", "human", 0.80)]}}

        index._request = AsyncMock(side_effect=response)
        result = await index.search(PROJECT, "review", date(2026, 9, 20),
                                    date(2026, 9, 27), None, 1, QueryEmbedding(), "main")
        self.assertEqual([item["sourceId"] for item in result["items"]], ["human"])
        self.assertFalse(result["insufficientEvidence"])

    async def test_sync_removes_legacy_bot_points_even_without_new_documents(self):
        index = QdrantEvidenceIndex(None, "http://qdrant:6333")
        index._ensure_evidence = AsyncMock()
        index._upsert = AsyncMock()
        index._get_points = AsyncMock(return_value={})
        index._request = AsyncMock(return_value={})

        async def legacy_scroll(*args, **kwargs):
            yield {"id": "bot-point", "payload": {"source_type": "mr_description",
                                                  "author": "reviewer[bot]"}}

        index._scroll = legacy_scroll
        await index.sync(PROJECT, [], NoEmbedding(), "main")
        self.assertEqual(index._request.await_args.kwargs["body"], {"points": ["bot-point"]})

    async def test_qdrant_rate_limit_is_not_gemini_error(self):
        async def rate_limited(request):
            return httpx.Response(429, json={"status": "error"})

        async with httpx.AsyncClient(transport=httpx.MockTransport(rate_limited)) as client:
            index = QdrantEvidenceIndex(client, "http://qdrant:6333")
            with self.assertRaises(QdrantRequestError) as caught:
                await index._request("GET", "/collections/signals_evidence")
        self.assertEqual(caught.exception.status_code, 429)


if __name__ == "__main__":
    unittest.main()
