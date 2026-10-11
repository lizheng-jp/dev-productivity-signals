import json
import os
import unittest
from unittest.mock import patch

import httpx

from app.main import AskRequest, AskResponse, GeminiGateway, Source, app, run_agent

REQUEST = AskRequest(question="Why did review slow down?", projectId="github~openai~openai-java",
                     since="2026-09-01", until="2026-09-07", refName="main")


def sse(*chunks):
    return "".join(f"data: {json.dumps(chunk)}\r\n\r\n" for chunk in chunks)


def chunk(*parts, usage=None):
    return {"candidates": [{"content": {"role": "model", "parts": list(parts)}}],
            **({"usageMetadata": usage} if usage else {})}


class Tools:
    def __init__(self):
        self.calls = []

    async def execute(self, name, request, args=None):
        self.calls.append(name)
        if name == "search_project_evidence":
            return {"items": [{"sourceId": "mr:1:description", "url": "https://github.com/openai/openai-java/pull/1",
                               "sourceType": "mr_description", "entityId": 1, "eventDate": "2026-09-02",
                               "score": 0.8}]}, Source(tool=name, apiPath="/search",
                                                      projectUrl="https://github.com/openai/openai-java")
        return {"current": {"mergedCount": 4}}, Source(tool=name, apiPath="/metrics",
                                                       projectUrl="https://github.com/openai/openai-java")


class StreamingModel:
    """Replays turns; text and thought parts are delivered through on_delta like the real gateway."""
    tool_policy = "open"

    def __init__(self, turns):
        self.turns = iter(turns)

    async def generate(self, contents, mode):
        return {"role": "model", "parts": next(self.turns)["parts"]}

    async def generate_stream(self, contents, mode, on_delta):
        turn = next(self.turns)
        for kind, text in turn.get("deltas", []):
            await on_delta(kind, text)
        return {"role": "model", "parts": turn["parts"]}


class GatewayStreamTests(unittest.IsolatedAsyncioTestCase):
    async def test_merges_text_drops_thoughts_and_keeps_signatures(self):
        bodies = []

        def respond(request):
            bodies.append(json.loads(request.content))
            self.assertIn("streamGenerateContent", str(request.url))
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, text=sse(
                chunk({"text": "**Planning** I will check.", "thought": True}),
                chunk({"text": "Review "}),
                chunk({"text": "slowed."}),
                chunk({"text": "", "thoughtSignature": "sig-text"},
                      usage={"promptTokenCount": 10, "candidatesTokenCount": 3, "thoughtsTokenCount": 7})))

        deltas = []

        async def on_delta(kind, text):
            deltas.append((kind, text))

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            gateway = GeminiGateway(client, "key", "gemini-test", "open")
            content = await gateway.generate_stream([{"role": "user", "parts": [{"text": "q"}]}], "AUTO", on_delta)
        self.assertEqual(content["parts"], [{"text": "Review slowed.", "thoughtSignature": "sig-text"}])
        self.assertEqual(deltas, [("thought", "**Planning** I will check."), ("text", "Review "), ("text", "slowed.")])
        self.assertEqual(gateway.last_usage, {"input": 10, "output": 3, "thinking": 7})
        self.assertTrue(bodies[0]["generationConfig"]["thinkingConfig"]["includeThoughts"])

    async def test_keeps_function_calls_with_their_signature(self):
        call = {"functionCall": {"name": "search_project_evidence", "args": {"query": "q"}},
                "thoughtSignature": "sig-call"}

        def respond(request):
            return httpx.Response(200, text=sse(chunk({"text": "thinking", "thought": True}), chunk(call),
                                                chunk({"text": ""})))

        async def ignore(kind, text):
            pass

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            content = await GeminiGateway(client, "key", "m", "open").generate_stream([], "ANY", ignore)
        self.assertEqual(content["parts"], [call])


class RunAgentEventTests(unittest.IsolatedAsyncioTestCase):
    async def test_emits_steps_thoughts_and_answer_pieces(self):
        model = StreamingModel([
            {"deltas": [("thought", "Look for review delays.")],
             "parts": [{"functionCall": {"name": "search_project_evidence", "args": {"query": "review delay"}}}]},
            {"deltas": [("text", "Facts: "), ("text", "PR #1.")], "parts": [{"text": "Facts: PR #1."}]},
        ])
        events = []

        async def emit(event):
            events.append(event)

        response = await run_agent(REQUEST, model, Tools(), "r", "e", emit=emit)
        self.assertEqual(response.answer, "Facts: PR #1.")
        self.assertEqual(events, [
            {"type": "thought", "text": "Look for review delays."},
            {"type": "step", "status": "running", "tool": "search_project_evidence", "detail": "review delay"},
            {"type": "step", "status": "done", "tool": "search_project_evidence", "detail": "review delay",
             "count": 1},
            {"type": "answer", "text": "Facts: "},
            {"type": "answer", "text": "PR #1."},
        ])

    async def test_trace_records_tool_evidence_only_when_requested(self):
        def turns():
            return [{"parts": [{"functionCall": {"name": "get_project_metrics", "args": {}}}]},
                    {"parts": [{"text": "Facts."}]}]
        plain = await run_agent(REQUEST, StreamingModel(turns()), Tools(), "r", "e")
        self.assertIsNone(plain.trace)
        traced = await run_agent(REQUEST.model_copy(update={"includeTrace": True}), StreamingModel(turns()),
                                 Tools(), "r", "e")
        self.assertEqual(traced.trace, [{"tool": "get_project_metrics", "args": {},
                                         "evidence": {"current": {"mergedCount": 4}}}])

    async def test_resets_text_that_preceded_a_tool_call(self):
        model = StreamingModel([
            {"deltas": [("text", "Let me check.")],
             "parts": [{"text": "Let me check."}, {"functionCall": {"name": "get_project_metrics", "args": {}}}]},
            {"deltas": [("text", "Done.")], "parts": [{"text": "Done."}]},
        ])
        events = []

        async def emit(event):
            events.append(event)

        await run_agent(REQUEST, model, Tools(), "r", "e", emit=emit)
        types = [event["type"] for event in events]
        self.assertEqual(types[:2], ["answer", "answer_reset"])
        self.assertEqual(events[-1], {"type": "answer", "text": "Done."})


class StreamEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def post(self, side_effect):
        env = {"AGENT_INTERNAL_KEY": "k", "GEMINI_API_KEY": "g"}
        with patch.dict(os.environ, env), patch("app.main.run_agent", side_effect=side_effect):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://agent.test") as client:
                response = await client.post("/ask/stream", json=REQUEST.model_dump(mode="json"),
                                             headers={"X-Agent-Internal-Key": "k"})
        events = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
        return response, events

    async def test_streams_progress_then_done(self):
        async def fake(request, model, tools, request_id, execution_id, emit=None):
            await emit({"type": "step", "status": "running", "tool": "get_project_metrics", "detail": None})
            await emit({"type": "answer", "text": "Answer"})
            return AskResponse(requestId=request_id, executionId=execution_id, answer="Answer", sources=[],
                               iterations=2)

        response, events = await self.post(fake)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.headers["content-type"].startswith("text/event-stream"))
        self.assertEqual([event["type"] for event in events], ["step", "answer", "done"])
        self.assertEqual(events[-1]["response"]["answer"], "Answer")

    async def test_reports_failures_as_an_error_event(self):
        async def fake(request, model, tools, request_id, execution_id, emit=None):
            raise ValueError("Model did not produce a grounded answer")

        response, events = await self.post(fake)
        self.assertEqual(events, [{"type": "error", "status": 502,
                                   "detail": "Agent could not complete this request"}])

    async def test_rejects_wrong_internal_key_before_streaming(self):
        with patch.dict(os.environ, {"AGENT_INTERNAL_KEY": "k", "GEMINI_API_KEY": "g"}):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://agent.test") as client:
                response = await client.post("/ask/stream", json=REQUEST.model_dump(mode="json"),
                                             headers={"X-Agent-Internal-Key": "wrong"})
        self.assertEqual(response.status_code, 403)


if __name__ == "__main__":
    unittest.main()
