import json
import os
import tempfile
import unittest
from datetime import date, timedelta
from unittest.mock import AsyncMock, patch

import httpx

from app.evidence import (EMBEDDING_BATCH_SIZE, EMBEDDING_DIMENSIONS, EvidenceDocument, EvidenceIndex,
                          GeminiEmbeddingProvider, LocalEmbeddingProvider, _chunks,
                          configured_embedding, insufficient_evidence_below, select_evidence_hits)
from app.main import IndexRequest, index_project


PROJECT = "github~octocat~Hello-World"


def document(source_id, source_type, content, event_at="2026-09-20T10:00:00Z"):
    entity_id = 17 if source_type.startswith("mr") else 23
    kind = "pull" if source_type.startswith("mr") else "issues"
    return EvidenceDocument(
        projectId=PROJECT, sourceType=source_type, sourceId=source_id,
        entityId=entity_id, content=content, author="octocat",
        createdAt="2026-09-01T10:00:00Z", updatedAt=event_at,
        eventAt=event_at, url=f"https://github.com/octocat/Hello-World/{kind}/{entity_id}",
        labels=["performance"],
    )


class FakeEmbedding:
    model = "fake-embedding"

    def __init__(self):
        self.embedded_texts = []
        self.batch_sizes = []

    def vector(self, text):
        values = [0.0] * EMBEDDING_DIMENSIONS
        values[0 if "review" in text.lower() else 1] = 1.0
        return values

    async def embed_documents(self, texts):
        self.batch_sizes.append(len(texts))
        self.embedded_texts.extend(texts)
        return [self.vector(text) for text in texts]

    async def embed_query(self, text):
        return self.vector(text)


class EvidenceIndexTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.index = EvidenceIndex(os.path.join(self.directory.name, "evidence.sqlite"))
        self.embedding = FakeEmbedding()

    async def asyncTearDown(self):
        self.directory.cleanup()

    async def test_embedding_uses_bounded_batches(self):
        documents = [document(f"mr:{number}:description", "mr_description", f"review {number}")
                     for number in range(EMBEDDING_BATCH_SIZE + 1)]
        result = await self.index.sync(PROJECT, documents, self.embedding)
        self.assertEqual(result["embeddedChunks"], EMBEDDING_BATCH_SIZE + 1)
        self.assertEqual(self.embedding.batch_sizes, [EMBEDDING_BATCH_SIZE, 1])

    async def test_incremental_index_and_metadata_filtered_search(self):
        mr = document("mr:17:description", "mr_description", "Review was waiting for maintainers")
        issue = document("issue:23:description", "issue", "Build pipeline regression",
                         "2026-08-20T10:00:00Z")
        first = await self.index.sync(PROJECT, [mr, issue], self.embedding)
        self.index.record_refresh(PROJECT, date(2026, 8, 1), date(2026, 9, 26), None, False)
        self.assertEqual(first["embeddedChunks"], 2)
        second = await self.index.sync(PROJECT, [mr, issue], self.embedding)
        self.assertEqual(second["embeddedChunks"], 0)
        self.assertEqual(len(self.embedding.embedded_texts), 2)

        result = await self.index.search(PROJECT, "review bottleneck", date(2026, 9, 1),
                                         date(2026, 9, 26), ["mr_description"], 5,
                                         self.embedding)
        self.assertEqual(result["candidateCount"], 1)
        self.assertFalse(result["coverageIncomplete"])
        self.assertEqual(result["items"][0]["sourceId"], "mr:17:description")
        self.assertEqual(result["items"][0]["url"], mr.url)
        self.assertEqual(result["items"][0]["labels"], ["performance"])
        self.assertTrue((await self.index.search("github~other~repo", "review",
            date(2026, 9, 1), date(2026, 9, 26), None, 5, self.embedding))["insufficientEvidence"])

        updated = mr.model_copy(update={"content": "Review blocked by API compatibility"})
        change = await self.index.sync(PROJECT, [updated], self.embedding)
        self.assertEqual(change["embeddedChunks"], 1)
        self.assertEqual(len(self.embedding.embedded_texts), 3)

    async def test_metadata_change_without_new_embedding_and_stale_chunks_removed(self):
        original = document("mr:17:description", "mr_description", "review\n" + "x" * 1300)
        await self.index.sync(PROJECT, [original], self.embedding)
        self.assertGreater(len(_chunks(original.content)), 1)
        moved = original.model_copy(update={"eventAt": "2026-09-25T10:00:00Z"})
        self.assertEqual((await self.index.sync(PROJECT, [moved], self.embedding))["embeddedChunks"], 0)
        shorter = moved.model_copy(update={"content": "review"})
        await self.index.sync(PROJECT, [shorter], self.embedding)
        result = await self.index.search(PROJECT, "review", date(2026, 9, 25),
                                         date(2026, 9, 26), None, 5, self.embedding)
        self.assertEqual(result["candidateCount"], 1)

    async def test_rejects_cross_project_or_non_github_evidence(self):
        mr = document("mr:17:description", "mr_description", "review")
        with self.assertRaisesRegex(ValueError, "outside selected project"):
            await self.index.sync("github~other~repo", [mr], self.embedding)
        with self.assertRaisesRegex(ValueError, "outside selected GitHub project"):
            await self.index.sync(PROJECT, [mr.model_copy(update={"url": "https://evil.test"})],
                                  self.embedding)
        with self.assertRaisesRegex(ValueError, "outside selected GitHub project"):
            await self.index.sync(PROJECT, [mr.model_copy(update={
                "url": "https://github.com/other/repo/pull/17"})], self.embedding)

    async def test_branch_scopes_are_isolated(self):
        mr = document("mr:17:description", "mr_description", "review delay")
        await self.index.sync(PROJECT, [mr], self.embedding, "main")
        other = await self.index.search(PROJECT, "review", date(2026, 9, 1),
                                        date(2026, 9, 26), None, 5, self.embedding, "develop")
        selected = await self.index.search(PROJECT, "review", date(2026, 9, 1),
                                           date(2026, 9, 26), None, 5, self.embedding, "main")
        self.assertEqual(other["candidateCount"], 0)
        self.assertEqual(selected["items"][0]["sourceId"], "mr:17:description")

    async def test_incomplete_coverage_disables_incremental_shortcut(self):
        self.index.record_refresh(PROJECT, date(2026, 9, 1), date(2026, 9, 26), "main", True)
        self.assertIsNone(self.index.updated_after(PROJECT, date(2026, 9, 1),
                                                    date(2026, 9, 26), "main"))
        self.assertTrue(self.index.coverage_incomplete(PROJECT, date(2026, 9, 20),
                                                       date(2026, 9, 26), "main"))

    async def test_sync_removes_legacy_bot_discussions(self):
        human = document("mr:17:comment:1", "mr_discussion", "review delay")
        await self.index.sync(PROJECT, [human], self.embedding)
        with self.index._connect() as database:
            database.execute("""
                INSERT INTO evidence SELECT 'legacy-bot', project_id, ref_name, source_type,
                    'mr:17:comment:2', entity_id, 'reviewer[bot]', created_at, updated_at,
                    event_date, url, labels, content, content_hash, embedding_model, embedding
                FROM evidence WHERE source_id='mr:17:comment:1'
            """)
        await self.index.sync(PROJECT, [], self.embedding)
        result = await self.index.search(PROJECT, "review", date(2026, 9, 20),
                                         date(2026, 9, 20), None, 5, self.embedding)
        self.assertEqual(result["candidateCount"], 1)
        self.assertEqual(result["items"][0]["author"], "octocat")

    async def test_selection_skips_bots_and_diversifies_pull_requests(self):
        candidates = [
            {"sourceType": "mr_discussion", "sourceId": "bot", "entityId": 17,
             "author": "reviewer[bot]", "score": 0.95},
            {"sourceType": "mr_description", "sourceId": "bot-description", "entityId": 19,
             "author": "release[bot]", "score": 0.92},
            {"sourceType": "mr_discussion", "sourceId": "human-1", "entityId": 17,
             "author": "alice", "score": 0.90},
            {"sourceType": "mr_discussion", "sourceId": "human-2", "entityId": 17,
             "author": "bob", "score": 0.85},
            {"sourceType": "mr_discussion", "sourceId": "human-3", "entityId": 17,
             "author": "carol", "score": 0.80},
            {"sourceType": "mr_discussion", "sourceId": "other-pr", "entityId": 18,
             "author": "dave", "score": 0.70},
        ]
        result = select_evidence_hits(candidates, 5)
        self.assertEqual([item["sourceId"] for item in result],
                         ["human-1", "human-2", "other-pr"])


class GeminiEmbeddingTests(unittest.IsolatedAsyncioTestCase):
    async def test_full_refresh_does_not_use_incremental_timestamp(self):
        until = date.today()
        since = until - timedelta(days=6)
        index = type("FakeIndex", (), {})()
        index.updated_after = AsyncMock(return_value="2026-09-27T00:00:00Z")
        index.sync = AsyncMock(return_value={"sources": 0, "embeddedChunks": 0,
                                             "unchangedChunks": 0})
        index.record_refresh = AsyncMock()
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, json={"documents": [], "truncated": False})

        original_client = httpx.AsyncClient
        with patch.dict(os.environ, {
                "AGENT_INTERNAL_KEY": "internal-key", "GEMINI_API_KEY": "test-key",
                "Signals_BACKEND_URL": "http://spring.test"}), \
                patch("app.main.httpx.AsyncClient",
                      side_effect=lambda *args, **kwargs: original_client(
                          transport=httpx.MockTransport(respond))), \
                patch("app.main.configured_index", return_value=index):
            await index_project(PROJECT, IndexRequest(since=since, until=until,
                                                     fullRefresh=True), "internal-key")
        index.updated_after.assert_not_awaited()
        self.assertNotIn("updatedAfter", requests[0].url.params)

    async def test_index_chunk_limit_marks_refresh_incomplete(self):
        until = date.today()
        since = until - timedelta(days=6)
        index = type("FakeIndex", (), {})()
        index.updated_after = AsyncMock(return_value=None)
        index.sync = AsyncMock(return_value={"sources": 1, "embeddedChunks": 250,
                                             "unchangedChunks": 0, "truncated": True})
        index.record_refresh = AsyncMock()
        original_client = httpx.AsyncClient
        transport = httpx.MockTransport(lambda request: httpx.Response(
            200, json={"documents": [], "truncated": False}))
        with patch.dict(os.environ, {
                "AGENT_INTERNAL_KEY": "internal-key", "GEMINI_API_KEY": "test-key",
                "Signals_BACKEND_URL": "http://spring.test"}), \
                patch("app.main.httpx.AsyncClient",
                      side_effect=lambda *args, **kwargs: original_client(transport=transport)), \
                patch("app.main.configured_index", return_value=index):
            result = await index_project(PROJECT, IndexRequest(since=since, until=until),
                                         "internal-key")
        self.assertTrue(result["truncated"])
        self.assertTrue(index.record_refresh.await_args.args[4])

    async def test_uses_distinct_document_and_query_tasks(self):
        calls = []

        def respond(request):
            body = json.loads(request.content)
            calls.append((str(request.url), body))
            self.assertEqual(request.headers["x-goog-api-key"], "test-key")
            vector = [1.0] + [0.0] * (EMBEDDING_DIMENSIONS - 1)
            if "batchEmbedContents" in str(request.url):
                return httpx.Response(200, json={"embeddings": [{"values": vector}]})
            return httpx.Response(200, json={"embedding": {"values": vector}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            provider = GeminiEmbeddingProvider(client, "test-key")
            await provider.embed_documents(["review delay"])
            await provider.embed_query("why did review slow down")
        self.assertEqual(calls[0][1]["requests"][0]["taskType"], "RETRIEVAL_DOCUMENT")
        self.assertEqual(calls[0][1]["requests"][0]["outputDimensionality"],
                         EMBEDDING_DIMENSIONS)
        self.assertEqual(calls[1][1]["taskType"], "RETRIEVAL_QUERY")
        self.assertEqual(calls[1][1]["outputDimensionality"], EMBEDDING_DIMENSIONS)

    async def test_local_embedding_prefixes_queries_and_normalizes(self):
        class FakeModel:
            prompts = {}

            def __init__(self):
                self.calls = []

            def encode(self, texts, prompt_name=None, **kwargs):
                self.calls.append((texts, prompt_name))
                return [[3.0, 4.0] + [0.0] * (EMBEDDING_DIMENSIONS - 2) for _ in texts]

        model = FakeModel()
        provider = LocalEmbeddingProvider("BAAI/bge-base-en-v1.5", loader=lambda name: model)
        documents = await provider.embed_documents(["review delay"])
        query = await provider.embed_query("why did review slow down")
        self.assertEqual(model.calls[0], (["review delay"], None))
        self.assertTrue(model.calls[1][0][0].startswith("Represent this sentence"))
        self.assertAlmostEqual(documents[0][0], 0.6)
        self.assertAlmostEqual(query[1], 0.8)

        model.prompts = {"query": "Instruct: retrieve\nQuery:"}
        await LocalEmbeddingProvider("Qwen/Qwen3-Embedding-0.6B",
                                     loader=lambda name: model).embed_query("why")
        self.assertEqual(model.calls[-1], (["why"], "query"))

    def test_insufficient_evidence_threshold_depends_on_model(self):
        with patch.dict(os.environ, {"EVIDENCE_MIN_TOP_SCORE": ""}):
            self.assertEqual(insufficient_evidence_below("gemini-embedding-001"), 0.40)
            self.assertEqual(insufficient_evidence_below("BAAI/bge-base-en-v1.5"), 0.73)
            self.assertEqual(insufficient_evidence_below("unknown-model"), 0.40)
        with patch.dict(os.environ, {"EVIDENCE_MIN_TOP_SCORE": "0.5"}):
            self.assertEqual(insufficient_evidence_below("BAAI/bge-base-en-v1.5"), 0.5)

    def test_configured_embedding_selects_provider(self):
        client = httpx.AsyncClient()
        with patch.dict(os.environ, {"EMBEDDING_PROVIDER": "local", "EMBEDDING_MODEL": "",
                                     "GEMINI_API_KEY": ""}):
            provider = configured_embedding(client)
            self.assertIsInstance(provider, LocalEmbeddingProvider)
            self.assertEqual(provider.model, "BAAI/bge-base-en-v1.5")
        with patch.dict(os.environ, {"EMBEDDING_PROVIDER": "gemini", "GEMINI_API_KEY": ""}):
            self.assertIsNone(configured_embedding(client))
        with patch.dict(os.environ, {"EMBEDDING_PROVIDER": "gemini", "GEMINI_API_KEY": "key"}):
            self.assertIsInstance(configured_embedding(client), GeminiEmbeddingProvider)
        with patch.dict(os.environ, {"EMBEDDING_PROVIDER": "openai"}), self.assertRaises(ValueError):
            configured_embedding(client)

    async def test_document_embedding_retries_rate_limit_but_query_does_not(self):
        statuses = iter([429, 200, 429])
        vector = [1.0] + [0.0] * (EMBEDDING_DIMENSIONS - 1)

        def respond(request):
            status = next(statuses)
            if status != 200:
                return httpx.Response(status, json={})
            return httpx.Response(200, json={"embeddings": [{"values": vector}]})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            provider = GeminiEmbeddingProvider(client, "test-key")
            with patch("app.evidence.asyncio.sleep", new=AsyncMock()) as sleep:
                self.assertEqual(len(await provider.embed_documents(["review delay"])), 1)
                with self.assertRaises(httpx.HTTPStatusError):
                    await provider.embed_query("why")
        self.assertEqual(sleep.await_count, 1)

    async def test_document_embedding_gives_up_after_bounded_attempts(self):
        attempts = 0

        def respond(request):
            nonlocal attempts
            attempts += 1
            return httpx.Response(503, json={})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            provider = GeminiEmbeddingProvider(client, "test-key")
            with patch("app.evidence.asyncio.sleep", new=AsyncMock()), \
                    self.assertRaises(httpx.HTTPStatusError):
                await provider.embed_documents(["review delay"])
        self.assertEqual(attempts, 3)

    async def test_index_endpoint_forwards_sample_scale_and_widens_chunk_limit(self):
        until = date.today()
        seen = {}

        class FakeIndex:
            async def updated_after(self, project_id, start, end, ref_name):
                return None

            async def sync(self, project_id, documents, embedding, ref_name, max_chunks=250):
                seen["max_chunks"] = max_chunks
                return {"sources": 0, "embeddedChunks": 0, "unchangedChunks": 0}

            async def record_refresh(self, project_id, start, end, ref_name, truncated):
                pass

        def respond(request):
            seen["url"] = str(request.url)
            return httpx.Response(200, json={"documents": [], "truncated": False, "selection": "bounded"})

        original_client = httpx.AsyncClient
        transport = httpx.MockTransport(respond)
        with patch.dict(os.environ, {
                "AGENT_INTERNAL_KEY": "internal-key", "GEMINI_API_KEY": "test-key",
                "Signals_BACKEND_URL": "http://spring.test"}), \
                patch("app.main.httpx.AsyncClient",
                      side_effect=lambda *args, **kwargs: original_client(transport=transport)), \
                patch("app.main.configured_index", return_value=FakeIndex()):
            await index_project(PROJECT, IndexRequest(until=until, sampleScale=4, sampling="stratified"),
                                "internal-key")
        self.assertIn("sampleScale=4", seen["url"])
        self.assertIn("sampling=stratified", seen["url"])
        self.assertEqual(seen["max_chunks"], 1000)
        with patch.dict(os.environ, {
                "AGENT_INTERNAL_KEY": "internal-key", "GEMINI_API_KEY": "test-key",
                "Signals_BACKEND_URL": "http://spring.test", "INDEX_SAMPLING": "recent"}), \
                patch("app.main.httpx.AsyncClient",
                      side_effect=lambda *args, **kwargs: original_client(transport=transport)), \
                patch("app.main.configured_index", return_value=FakeIndex()):
            await index_project(PROJECT, IndexRequest(until=until), "internal-key")
        self.assertNotIn("sampling=", seen["url"])
        with self.assertRaises(ValueError):
            IndexRequest(sampleScale=6)
        with self.assertRaises(ValueError):
            IndexRequest(sampling="random")

    async def test_index_endpoint_reuses_spring_feed_and_skips_unchanged_embedding(self):
        until = date.today()
        since = until - timedelta(days=6)
        source = document("mr:17:description", "mr_description", "Review queue is long",
                          until.isoformat() + "T10:00:00Z")
        counters = {"spring": 0, "embedding": 0}

        class FakeIndex:
            refreshed = False

            async def updated_after(self, project_id, start, end, ref_name):
                return "2026-09-27T00:00:00+00:00" if self.refreshed else None

            async def sync(self, project_id, documents, embedding, ref_name, max_chunks=250):
                if documents:
                    await embedding.embed_documents([item.content for item in documents])
                return {"sources": len(documents), "embeddedChunks": len(documents),
                        "unchangedChunks": 0}

            async def record_refresh(self, project_id, start, end, ref_name, truncated):
                self.refreshed = True

        index = FakeIndex()

        def respond(request):
            if "spring.test" in str(request.url):
                counters["spring"] += 1
                self.assertEqual(request.headers["X-Agent-Internal-Key"], "internal-key")
                if counters["spring"] == 2:
                    self.assertIn("updatedAfter=", str(request.url))
                return httpx.Response(200, json={"documents": [source.model_dump()]
                                                 if counters["spring"] == 1 else [],
                                                 "truncated": False, "selection": "bounded"})
            counters["embedding"] += 1
            values = [1.0] + [0.0] * (EMBEDDING_DIMENSIONS - 1)
            return httpx.Response(200, json={"embeddings": [{"values": values}]})

        original_client = httpx.AsyncClient
        transport = httpx.MockTransport(respond)
        with patch.dict(os.environ, {
                "AGENT_INTERNAL_KEY": "internal-key", "GEMINI_API_KEY": "test-key",
                "Signals_BACKEND_URL": "http://spring.test"}), \
                patch("app.main.httpx.AsyncClient",
                      side_effect=lambda *args, **kwargs: original_client(transport=transport)), \
                patch("app.main.configured_index", return_value=index):
            request = IndexRequest(since=since, until=until)
            first = await index_project(PROJECT, request, "internal-key")
            second = await index_project(PROJECT, request, "internal-key")
        self.assertEqual(first["embeddedChunks"], 1)
        self.assertEqual(second["embeddedChunks"], 0)
        self.assertEqual(counters, {"spring": 2, "embedding": 1})
