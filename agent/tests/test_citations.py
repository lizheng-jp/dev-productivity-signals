import json
import unittest

from app.citations import answer_references, evidence_references
from app.main import Source, run_agent
from tests.test_agent import REQUEST, FakeModel


PROJECT_URL = "https://github.com/openai/openai-java"


class CitationTests(unittest.IsolatedAsyncioTestCase):
    def test_answer_references_reads_urls_and_number_mentions(self):
        numbers, external = answer_references(
            "See [PR](https://github.com/OpenAI/openai-java/pull/17#issuecomment-3), MR 18, #19, "
            "issue #20 and https://github.com/other/repo/pull/99. Score 62 in step 1.", PROJECT_URL)
        self.assertEqual(numbers, {17, 18, 19, 20})
        self.assertEqual(external, 1)

    def test_answer_references_ignores_markdown_headings_and_html_entities(self):
        numbers, external = answer_references("# Facts\n## 2 findings\nA&#39;s view", PROJECT_URL)
        self.assertEqual((numbers, external), (set(), 0))

    def test_evidence_references_reads_known_keys_urls_and_retrieved_mentions(self):
        evidence = {"items": [{"iid": 5, "url": f"{PROJECT_URL}/pull/5"},
                              {"entityId": 6, "content": "Duplicate of #7, see https://github.com/x/y/pull/8"}],
                    "metrics": {"mergedCount": 40}, "slowest": [{"number": 9}]}
        self.assertEqual(evidence_references(evidence, PROJECT_URL), {5, 6, 7, 9})

    async def test_agent_marks_cited_hits_and_logs_unsupported_references(self):
        class SearchTools:
            async def execute(self, name, request, args=None):
                if name != "search_project_evidence":
                    return {"metrics": {"mergedCount": 4}}, Source(
                        tool=name, apiPath="/metrics", projectUrl=PROJECT_URL)
                return {"items": [
                    {"url": f"{PROJECT_URL}/pull/17", "sourceId": "mr:17:description",
                     "sourceType": "mr_description", "entityId": 17, "eventDate": "2026-08-03",
                     "score": 0.7, "content": "Blocked on review"},
                    {"url": f"{PROJECT_URL}/issues/21", "sourceId": "issue:21:description",
                     "sourceType": "issue", "entityId": 21, "eventDate": "2026-08-04",
                     "score": 0.6, "content": "Flaky CI"},
                ]}, Source(tool=name, apiPath="/search", projectUrl=PROJECT_URL)

        model = FakeModel([
            {"role": "model", "parts": [{"functionCall": {"name": "get_project_metrics", "args": {}}}]},
            {"role": "model", "parts": [{"functionCall": {
                "name": "search_project_evidence", "args": {"query": "review delay"}}}]},
            {"role": "model", "parts": [{"text": "PR #17 waited on review; PR #404 also did."}]},
        ])
        with self.assertLogs("signals.agent", level="INFO") as logs:
            response = await run_agent(REQUEST, model, SearchTools(), "request-cite", "execution-cite")

        cited = {source.entityId: source.cited for source in response.sources if source.entityId}
        self.assertEqual(cited, {17: True, 21: False})
        self.assertIsNone(response.sources[0].cited)
        check = next(json.loads(line.split(":", 2)[2]) for line in logs.output
                     if '"citation_check"' in line)
        self.assertEqual((check["retrieved_hits"], check["cited_hits"], check["unsupported"]),
                         (2, 1, [404]))


if __name__ == "__main__":
    unittest.main()
