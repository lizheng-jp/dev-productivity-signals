import json
import os
import tempfile
import unittest
from datetime import date
from unittest.mock import AsyncMock, patch

import httpx

from app.main import AskRequest, AskResponse, SignalsTools, GeminiGateway, GitHubRateLimitError, Source, app, run_agent
from app.evidence import EvidenceDocument, EvidenceIndex, EMBEDDING_DIMENSIONS


REQUEST = AskRequest(
    question="Why did productivity change?", projectId="github~openai~openai-java",
    since=date(2026, 8, 1), until=date(2026, 8, 7), refName="main",
)


class FakeModel:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.modes = []

    async def generate(self, contents, mode):
        self.modes.append(mode)
        return next(self.responses)


class FakeTools:
    def __init__(self):
        self.calls = []

    async def execute(self, name, request, args=None):
        self.calls.append((name, request.projectId, args or {}))
        return {"current": {"mergedCount": 4}, "previous": {"mergedCount": 6}}, Source(
            tool=name, apiPath="/api/gitlab/projects/github~openai~openai-java/space-metrics/comparison/project",
            projectUrl="https://github.com/openai/openai-java",
        )


class AgentTests(unittest.IsolatedAsyncioTestCase):
    async def test_metrics_endpoint_exposes_agent_counters_without_project_labels(self):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                     base_url="http://agent.test") as client:
            response = await client.get("/metrics")

        self.assertEqual(response.status_code, 200)
        self.assertIn("signals_agent_executions_total", response.text)
        self.assertIn("signals_agent_model_duration_seconds", response.text)
        self.assertNotIn("github~openai~openai-java", response.text)

    async def test_metrics_then_semantic_evidence_then_cited_answer(self):
        class Embedding:
            model = "test-embedding"

            async def embed_documents(self, texts):
                return [[1.0] + [0.0] * (EMBEDDING_DIMENSIONS - 1) for _ in texts]

            async def embed_query(self, text):
                return [1.0] + [0.0] * (EMBEDDING_DIMENSIONS - 1)

        embedding = Embedding()
        with tempfile.TemporaryDirectory() as directory:
            index = EvidenceIndex(os.path.join(directory, "evidence.sqlite"))
            await index.sync(REQUEST.projectId, [EvidenceDocument(
                projectId=REQUEST.projectId, sourceType="mr_discussion",
                sourceId="mr:17:note:3", entityId=17,
                content="Review waited for maintainer approval.", author="octocat",
                createdAt="2026-08-03T10:00:00Z", updatedAt="2026-08-03T10:00:00Z",
                eventAt="2026-08-03T10:00:00Z",
                url="https://github.com/openai/openai-java/pull/17#issuecomment-3",
            )], embedding, REQUEST.refName)
            model_calls = 0

            def respond(request):
                nonlocal model_calls
                if "generativelanguage.googleapis.com" in str(request.url):
                    model_calls += 1
                    body = json.loads(request.content)
                    if model_calls == 1:
                        self.assertNotIn("search_project_evidence", body["toolConfig"]
                                         ["functionCallingConfig"]["allowedFunctionNames"])
                        name, args = "get_project_comparison", {}
                    elif model_calls == 2:
                        self.assertEqual(body["toolConfig"]["functionCallingConfig"],
                                         {"mode": "ANY", "allowedFunctionNames":
                                          ["search_project_evidence"]})
                        self.assertEqual(body["contents"][-1]["parts"][0]["functionResponse"]
                                         ["response"]["evidence"]["current"]["reviewWaitTime"], 46)
                        name, args = "search_project_evidence", {"query": "review waiting for approval"}
                    else:
                        self.assertEqual(body["toolConfig"]["functionCallingConfig"]["mode"], "AUTO")
                        items = body["contents"][-1]["parts"][0]["functionResponse"]
                        self.assertEqual(items["response"]["evidence"]["items"][0]["sourceId"],
                                         "mr:17:note:3")
                        return httpx.Response(200, json={"candidates": [{"content": {
                            "role": "model", "parts": [{"text": "Measured: 46. Evidence: MR 17. "
                                "Interpretation: approval may contribute."}]
                        }}]})
                    return httpx.Response(200, json={"candidates": [{"content": {
                        "role": "model", "parts": [{"functionCall": {"name": name, "args": args}}]
                    }}]})
                return httpx.Response(200, json={
                    "current": {"reviewWaitTime": 46}, "previous": {"reviewWaitTime": 30},
                    "projectTrends": {}, "periods": {},
                })

            async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
                response = await run_agent(REQUEST, GeminiGateway(client, "key", "gemini-test"),
                                           SignalsTools(client, "http://spring.test", "internal", index, embedding),
                                           "request-rag", "execution-rag")
        self.assertEqual(model_calls, 3)
        self.assertEqual(response.iterations, 3)
        self.assertIn("may contribute", response.answer)
        self.assertTrue(any(source.projectUrl.endswith("#issuecomment-3") for source in response.sources))

    async def test_search_cannot_run_before_structured_tool(self):
        model = FakeModel([{"role": "model", "parts": [{"functionCall": {
            "name": "search_project_evidence", "args": {"query": "review delay"}}}]}])
        with self.assertRaisesRegex(ValueError, "structured evidence first"):
            await run_agent(REQUEST, model, FakeTools(), "r", "e")

    async def test_model_tool_model_http_flow(self):
        model_calls = 0

        def respond(request):
            nonlocal model_calls
            if "generativelanguage.googleapis.com" in str(request.url):
                model_calls += 1
                body = json.loads(request.content)
                if model_calls == 1:
                    self.assertEqual(body["toolConfig"]["functionCallingConfig"]["mode"], "ANY")
                    return httpx.Response(200, json={"candidates": [{"content": {
                        "role": "model", "parts": [{"functionCall": {
                            "name": "get_project_comparison", "args": {}, "id": "call-1"}}]
                    }}]})
                self.assertEqual(body["contents"][-1]["parts"][0]["functionResponse"]["id"], "call-1")
                self.assertEqual(body["contents"][-1]["parts"][0]["functionResponse"]
                                 ["response"]["evidence"]["current"]["spaceTotalScore"], 62)
                return httpx.Response(200, json={"candidates": [{"content": {
                    "role": "model", "parts": [{"text": "Facts: score 62 versus 70."}]
                }}]})
            self.assertNotIn("X-Signals-GitHub-Token", request.headers)
            return httpx.Response(200, json={
                "current": {"spaceTotalScore": 62}, "previous": {"spaceTotalScore": 70},
                "projectTrends": {"spaceTotalScore": {"change": -8}}, "periods": {},
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            answer = await run_agent(
                REQUEST, GeminiGateway(client, "test-model-key", "gemini-test"),
                SignalsTools(client, "http://spring.test"), "request-5", "execution-5")
        self.assertEqual(model_calls, 2)
        self.assertIn("62 versus 70", answer.answer)
        self.assertEqual(answer.sources[0].tool, "get_project_comparison")

    async def test_tool_uses_fixed_project_without_forwarding_a_token(self):
        def respond(request):
            self.assertIn("github~openai~openai-java", str(request.url))
            self.assertNotIn("X-Signals-GitHub-Token", request.headers)
            return httpx.Response(200, json={
                "current": {"spaceTotalScore": 60, "privateData": "discard"},
                "previous": {"spaceTotalScore": 70},
                "projectTrends": {"spaceTotalScore": {"change": -10}},
                "periods": {},
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            tool = SignalsTools(client, "http://spring.test")
            evidence, source = await tool.execute("get_project_comparison", REQUEST)
        self.assertEqual(evidence["current"], {"spaceTotalScore": 60})
        self.assertEqual(source.projectUrl, "https://github.com/openai/openai-java")
        self.assertNotIn("test-secret", source.apiPath)

    async def test_tool_reports_github_rate_limit(self):
        async with httpx.AsyncClient(transport=httpx.MockTransport(
                lambda request: httpx.Response(429, json={"detail": "rate limit"}))) as client:
            tool = SignalsTools(client, "http://spring.test")
            with self.assertRaises(GitHubRateLimitError):
                await tool.execute("get_project_metrics", REQUEST)

    async def test_model_adapter_sends_function_declarations(self):
        def respond(request):
            body = json.loads(request.content)
            self.assertEqual(body["toolConfig"]["functionCallingConfig"]["mode"], "ANY")
            self.assertEqual(request.headers["x-goog-api-key"], "test-model-key")
            return httpx.Response(200, json={"candidates": [{"content": {
                "role": "model", "parts": [{"functionCall": {
                    "name": "get_project_metrics", "args": {}}}]
            }}]})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            model = GeminiGateway(client, "test-model-key", "gemini-test")
            result = await model.generate([{"role": "user", "parts": [{"text": "question"}]}], "ANY")
        self.assertEqual(result["parts"][0]["functionCall"]["name"], "get_project_metrics")

    async def test_merge_lead_question_requires_distribution_after_search(self):
        def respond(request):
            body = json.loads(request.content)
            self.assertEqual(body["toolConfig"]["functionCallingConfig"], {
                "mode": "ANY", "allowedFunctionNames": ["get_merge_lead_distribution"]})
            return httpx.Response(200, json={"candidates": [{"content": {
                "role": "model", "parts": [{"functionCall": {
                    "name": "get_merge_lead_distribution", "args": {}}}]
            }}]})

        contents = [{"role": "user", "parts": [{"text": "Why did merge lead time increase?"}]},
                    {"role": "model", "parts": [{"functionCall": {
                        "name": "get_project_comparison", "args": {}}}]},
                    {"role": "user", "parts": [{"functionResponse": {
                        "name": "get_project_comparison", "response": {"evidence": {}}}}]},
                    {"role": "model", "parts": [{"functionCall": {
                        "name": "search_project_evidence", "args": {"query": "merge delay"}}}]},
                    {"role": "user", "parts": [{"functionResponse": {
                        "name": "search_project_evidence", "response": {"evidence": {}}}}]}]
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            model = GeminiGateway(client, "test-model-key", "gemini-test")
            result = await model.generate(contents, "AUTO")
        self.assertEqual(result["parts"][0]["functionCall"]["name"], "get_merge_lead_distribution")

    async def test_multiple_metric_responses_still_require_evidence_search(self):
        def respond(request):
            body = json.loads(request.content)
            self.assertEqual(body["toolConfig"]["functionCallingConfig"], {
                "mode": "ANY", "allowedFunctionNames": ["search_project_evidence"]})
            return httpx.Response(200, json={"candidates": [{"content": {
                "role": "model", "parts": [{"functionCall": {
                    "name": "search_project_evidence", "args": {"query": "review delay"}}}]
            }}]})

        contents = [{"role": "user", "parts": [{"text": "Why did merge lead time increase?"}]},
                    {"role": "model", "parts": [
                        {"functionCall": {"name": "get_project_metrics", "args": {}}},
                        {"functionCall": {"name": "get_project_comparison", "args": {}}}]},
                    {"role": "user", "parts": [
                        {"functionResponse": {"name": "get_project_metrics", "response": {"evidence": {}}}},
                        {"functionResponse": {"name": "get_project_comparison", "response": {"evidence": {}}}}]}]
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            result = await GeminiGateway(client, "key", "gemini-test").generate(contents, "AUTO")
        self.assertEqual(result["parts"][0]["functionCall"]["name"], "search_project_evidence")

    async def test_final_model_request_has_no_callable_tools(self):
        def respond(request):
            body = json.loads(request.content)
            self.assertNotIn("tools", body)
            self.assertNotIn("toolConfig", body)
            self.assertIn("final text answer", body["systemInstruction"]["parts"][-1]["text"])
            return httpx.Response(200, json={"candidates": [{"content": {
                "role": "model", "parts": [{"text": "Facts: the measured score is 62."}]
            }}]})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            model = GeminiGateway(client, "test-model-key", "gemini-test")
            result = await model.generate([{"role": "user", "parts": [{"text": "question"}]}], "NONE")
        self.assertEqual(result["parts"][0]["text"], "Facts: the measured score is 62.")

    async def test_http_endpoint_requires_internal_key(self):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                     base_url="http://agent.test") as client:
            response = await client.post("/ask", json=REQUEST.model_dump(mode="json"))
        self.assertEqual(response.status_code, 403)

    async def test_request_accepts_free_tier_models_and_rejects_unknown_model(self):
        for model in ("gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.7-flash",
                      "gemini-3.8-flash", "gemini-3.5-flash-lite"):
            parsed = AskRequest(**{**REQUEST.model_dump(), "model": model})
            self.assertEqual(parsed.model, model)
        with self.assertRaisesRegex(ValueError, "Unsupported Agent model"):
            AskRequest(**{**REQUEST.model_dump(), "model": "gemini-unknown"})

    async def test_http_endpoint_uses_selected_model(self):
        answer = AskResponse(requestId="r", executionId="e", answer="Answer", sources=[], iterations=2)
        with patch.dict(os.environ, {"AGENT_INTERNAL_KEY": "test-internal-key",
                                    "GEMINI_API_KEY": "test-model-key"}), \
             patch("app.main.run_agent", new_callable=AsyncMock, return_value=answer) as run:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                         base_url="http://agent.test") as client:
                response = await client.post(
                    "/ask", json={**REQUEST.model_dump(mode="json"), "model": "gemini-3.8-flash"},
                    headers={"X-Agent-Internal-Key": "test-internal-key"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(run.call_args.args[1].model, "gemini-3.8-flash")

    async def test_http_endpoint_preserves_github_rate_limit(self):
        with patch.dict(os.environ, {"AGENT_INTERNAL_KEY": "test-internal-key",
                                    "GEMINI_API_KEY": "test-model-key"}), \
             patch("app.main.run_agent", new_callable=AsyncMock,
                   side_effect=GitHubRateLimitError):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                         base_url="http://agent.test") as client:
                response = await client.post("/ask", json=REQUEST.model_dump(mode="json"),
                                             headers={"X-Agent-Internal-Key": "test-internal-key"})
        self.assertEqual(response.status_code, 429)

    async def test_gemini_rate_limit_is_not_retried(self):
        calls = 0

        def respond(request):
            nonlocal calls
            calls += 1
            return httpx.Response(429, json={"error": {"message": "quota exceeded"}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            model = GeminiGateway(client, "test-model-key", "gemini-test")
            with self.assertRaises(httpx.HTTPStatusError):
                await model.generate([{"role": "user", "parts": [{"text": "question"}]}], "ANY")
        self.assertEqual(calls, 1)

    async def test_http_endpoint_preserves_gemini_rate_limit(self):
        response = httpx.Response(429, request=httpx.Request(
            "POST", "https://generativelanguage.googleapis.com/v1beta/models/test:generateContent"))
        with patch.dict(os.environ, {"AGENT_INTERNAL_KEY": "test-internal-key",
                                    "GEMINI_API_KEY": "test-model-key"}), \
             patch("app.main.run_agent", new_callable=AsyncMock,
                   side_effect=httpx.HTTPStatusError("rate limited", request=response.request,
                                                     response=response)):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                         base_url="http://agent.test") as client:
                result = await client.post("/ask", json=REQUEST.model_dump(mode="json"),
                                           headers={"X-Agent-Internal-Key": "test-internal-key"})
        self.assertEqual(result.status_code, 429)
        self.assertIn("Gemini", result.json()["detail"])

    async def test_grounded_comparison(self):
        model = FakeModel([
            {"role": "model", "parts": [{"functionCall": {
                "name": "get_project_comparison", "args": {}, "id": "1"}}]},
            {"role": "model", "parts": [{"text": "Facts: 4 vs 6. Interpretation: volume fell."}]},
        ])
        tools = FakeTools()
        result = await run_agent(REQUEST, model, tools, "request-1", "execution-1")
        self.assertEqual(result.iterations, 2)
        self.assertEqual(model.modes, ["ANY", "AUTO"])
        self.assertEqual(tools.calls, [("get_project_comparison", REQUEST.projectId, {})])
        self.assertEqual(result.sources[0].projectUrl, "https://github.com/openai/openai-java")

    async def test_multiple_function_calls_share_one_response_turn(self):
        class InspectingModel(FakeModel):
            async def generate(self, contents, mode):
                if len(contents) > 1:
                    responses = contents[-1]["parts"]
                    self_test.assertEqual(
                        [part["functionResponse"]["name"] for part in responses],
                        ["get_project_metrics", "get_project_comparison"])
                    self_test.assertEqual(
                        [part["functionResponse"]["id"] for part in responses],
                        ["call-1", "call-2"])
                return await super().generate(contents, mode)

        self_test = self
        model = InspectingModel([
            {"role": "model", "parts": [
                {"functionCall": {"name": "get_project_metrics", "args": {}, "id": "call-1"}},
                {"functionCall": {"name": "get_project_comparison", "args": {}, "id": "call-2"}},
            ]},
            {"role": "model", "parts": [{"text": "Facts: both metric tools returned data."}]},
        ])
        tools = FakeTools()

        result = await run_agent(REQUEST, model, tools, "request-parallel", "execution-parallel")

        self.assertEqual(result.iterations, 2)
        self.assertEqual(len(tools.calls), 2)
        self.assertEqual(len(result.sources), 2)

    async def test_duplicate_function_calls_reuse_one_backend_result(self):
        class InspectingModel(FakeModel):
            async def generate(self, contents, mode):
                if len(contents) > 1:
                    responses = contents[-1]["parts"]
                    self_test.assertEqual(len(responses), 4)
                    self_test.assertEqual(
                        [part["functionResponse"].get("id") for part in responses],
                        ["call-1", "call-2", "call-3", "call-4"])
                    self_test.assertEqual(responses[0]["functionResponse"]["response"],
                                          responses[2]["functionResponse"]["response"])
                return await super().generate(contents, mode)

        self_test = self
        model = InspectingModel([
            {"role": "model", "parts": [
                {"functionCall": {"name": name, "args": {}, "id": f"call-{index}"}}
                for index, name in enumerate(("get_project_metrics", "get_project_comparison",
                                               "get_project_metrics", "get_project_comparison"), 1)
            ]},
            {"role": "model", "parts": [{"text": "Facts: metrics returned evidence."}]},
        ])
        tools = FakeTools()

        result = await run_agent(REQUEST, model, tools, "request-duplicates", "execution-duplicates")

        self.assertEqual(result.iterations, 2)
        self.assertEqual(len(tools.calls), 2)
        self.assertEqual(len(result.sources), 2)

    async def test_different_arguments_for_same_tool_are_rejected_before_execution(self):
        model = FakeModel([{"role": "model", "parts": [
            {"functionCall": {"name": "get_member_metrics", "args": {"userName": "alice"}}},
            {"functionCall": {"name": "get_member_metrics", "args": {"userName": "bob"}}},
        ]}])
        tools = FakeTools()

        with self.assertRaisesRegex(ValueError, "Invalid tool call"):
            await run_agent(REQUEST, model, tools, "request-conflict", "execution-conflict")
        self.assertEqual(tools.calls, [])

    async def test_multiple_calls_cannot_exceed_total_tool_budget(self):
        calls = [("get_project_metrics", {}), ("get_project_comparison", {}),
                 ("get_member_metrics", {"userName": "octocat"}), ("get_merge_requests", {}),
                 ("get_merge_request_details", {"mrIid": 17}),
                 ("search_project_evidence", {"query": "review"})]
        model = FakeModel([{"role": "model", "parts": [
            {"functionCall": {"name": name, "args": args}} for name, args in calls
        ]}])
        tools = FakeTools()

        with self.assertRaisesRegex(ValueError, "Model exceeded tool budget"):
            await run_agent(REQUEST, model, tools, "request-over-budget", "execution-over-budget")
        self.assertEqual(tools.calls, [])

    async def test_rejects_ungrounded_answer(self):
        model = FakeModel([{"role": "model", "parts": [{"text": "No evidence"}]}])
        with self.assertRaisesRegex(ValueError, "grounded"):
            await run_agent(REQUEST, model, FakeTools(), "request-2", "execution-2")

    async def test_rejects_model_supplied_scope(self):
        model = FakeModel([{"role": "model", "parts": [{"functionCall": {
            "name": "get_project_metrics", "args": {"projectId": "other"}}}]}])
        tools = FakeTools()
        with self.assertRaisesRegex(ValueError, "Invalid tool arguments"):
            await run_agent(REQUEST, model, tools, "request-3", "execution-3")
        self.assertEqual(tools.calls, [])

    async def test_member_tool_filters_response_and_limits_list(self):
        def respond(request):
            self.assertIn("/space-metrics/members", str(request.url))
            return httpx.Response(200, json=[
                {"userCode": f"member-{index}", "spaceTotalScore": index, "secret": "omit"}
                for index in range(35)
            ])

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            evidence, _ = await SignalsTools(client, "http://spring.test").execute(
                "get_member_metrics", REQUEST)
        self.assertEqual(evidence["totalMembers"], 35)
        self.assertTrue(evidence["truncated"])
        self.assertEqual(evidence["members"][0]["userName"], "member-34")
        self.assertNotIn("secret", evidence["members"][0]["metrics"])

    async def test_member_tool_fetches_one_selected_user(self):
        def respond(request):
            self.assertIn("userName=octocat", str(request.url))
            self.assertNotIn("/members", str(request.url))
            return httpx.Response(200, json={"spaceTotalScore": 72, "privateData": "omit"})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            evidence, _ = await SignalsTools(client, "http://spring.test").execute(
                "get_member_metrics", REQUEST, {"userName": "octocat"})
        self.assertEqual(evidence, {"userName": "octocat", "metrics": {"spaceTotalScore": 72}})

    async def test_mr_list_preserves_incomplete_coverage(self):
        def respond(request):
            self.assertEqual(request.headers["X-Agent-Internal-Key"], "test-internal-key")
            return httpx.Response(200, json={"items": [{"iid": 17, "title": "Fix parser"}],
                                              "truncated": True, "selection": "first page only"})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            evidence, _ = await SignalsTools(client, "http://spring.test", "test-internal-key").execute(
                "get_merge_requests", REQUEST)
        self.assertTrue(evidence["truncated"])
        self.assertEqual(evidence["selection"], "first page only")

    async def test_mr_detail_tool_uses_server_key_and_selected_project(self):
        def respond(request):
            self.assertIn("/api/agent/evidence/projects/github~openai~openai-java/merge-requests/17", str(request.url))
            self.assertEqual(request.headers["X-Agent-Internal-Key"], "test-internal-key")
            return httpx.Response(200, json={"iid": 17, "title": "Fix parser", "analysis": {
                "complexity": "medium"}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            evidence, source = await SignalsTools(client, "http://spring.test", "test-internal-key").execute(
                "get_merge_request_details", REQUEST, {"mrIid": 17})
        self.assertEqual(evidence["analysis"]["complexity"], "medium")
        self.assertEqual(source.projectUrl, "https://github.com/openai/openai-java/pull/17")

    async def test_rejects_model_supplied_project_for_mr_tool(self):
        model = FakeModel([{"role": "model", "parts": [{"functionCall": {
            "name": "get_merge_request_details", "args": {"mrIid": 17, "projectId": "github~other~repo"}}}]}])
        tools = FakeTools()
        with self.assertRaisesRegex(ValueError, "Invalid MR tool arguments"):
            await run_agent(REQUEST, model, tools, "request-6", "execution-6")
        self.assertEqual(tools.calls, [])

    async def test_rejects_repeated_tool(self):
        call = {"role": "model", "parts": [{"functionCall": {
            "name": "get_project_metrics", "args": {}}}]}
        model = FakeModel([call, call])
        tools = FakeTools()
        with self.assertRaisesRegex(ValueError, "Invalid tool call"):
            await run_agent(REQUEST, model, tools, "request-4", "execution-4")
        self.assertEqual(len(tools.calls), 1)

    async def test_lists_mrs_then_reads_one_detail_within_tool_budget(self):
        model = FakeModel([
            {"role": "model", "parts": [{"functionCall": {
                "name": "get_merge_requests", "args": {}}}]},
            {"role": "model", "parts": [{"functionCall": {
                "name": "get_merge_request_details", "args": {"mrIid": 17}}}]},
            {"role": "model", "parts": [{"text": "Facts: MR 17 has an existing analysis."}]},
        ])
        tools = FakeTools()

        result = await run_agent(REQUEST, model, tools, "request-7", "execution-7")

        self.assertEqual(model.modes, ["ANY", "AUTO", "AUTO"])
        self.assertEqual(len(result.sources), 2)
        self.assertEqual(tools.calls[0], ("get_merge_requests", REQUEST.projectId, {}))
        self.assertEqual(tools.calls[1], ("get_merge_request_details", REQUEST.projectId, {"mrIid": 17}))

    async def test_five_tool_calls_then_final_answer(self):
        calls = [
            ("get_project_metrics", {}),
            ("get_project_comparison", {}),
            ("get_member_metrics", {"userName": "octocat"}),
            ("get_merge_requests", {}),
            ("get_merge_request_details", {"mrIid": 17}),
        ]
        model = FakeModel([
            {"role": "model", "parts": [{"functionCall": {"name": name, "args": args}}]}
            for name, args in calls
        ] + [{"role": "model", "parts": [{"text": "Facts: five tools returned evidence."}]}])
        tools = FakeTools()

        result = await run_agent(REQUEST, model, tools, "request-8", "execution-8")

        self.assertEqual([call[0] for call in tools.calls], [name for name, _ in calls])
        self.assertEqual(model.modes, ["ANY", "AUTO", "AUTO", "AUTO", "AUTO", "NONE"])
        self.assertEqual(result.iterations, 6)
        self.assertEqual(len(result.sources), 5)

    async def test_distribution_tool_returns_bounded_context(self):
        def respond(request):
            self.assertIn("merge-lead-distribution", str(request.url))
            self.assertEqual(request.headers["X-Agent-Internal-Key"], "private-key")
            return httpx.Response(200, json={"current": {"sampleCount": 3, "p90Hours": 240},
                                             "previous": {"sampleCount": 2, "p90Hours": 24}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            tool = SignalsTools(client, "http://tomcat:8080", "private-key")
            evidence, source = await tool.execute("get_merge_lead_distribution", REQUEST)

        self.assertEqual(evidence["current"]["p90Hours"], 240)
        self.assertEqual(source.tool, "get_merge_lead_distribution")

    async def test_sixth_tool_call_is_rejected(self):
        names = ["get_project_metrics", "get_project_comparison", "get_member_metrics",
                 "get_merge_requests", "get_merge_request_details", "search_project_evidence"]
        model = FakeModel([
            {"role": "model", "parts": [{"functionCall": {"name": name, "args":
                {"mrIid": 17} if name == "get_merge_request_details" else
                {"query": "review"} if name == "search_project_evidence" else {}}}]}
            for name in names
        ])
        tools = FakeTools()

        with self.assertRaisesRegex(ValueError, "Model exceeded tool budget"):
            await run_agent(REQUEST, model, tools, "request-9", "execution-9")

        self.assertEqual(len(tools.calls), 5)
        self.assertEqual(model.modes[-1], "NONE")


if __name__ == "__main__":
    unittest.main()
