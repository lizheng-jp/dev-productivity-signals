import unittest

from app.answer_eval import grade_row, judge_prompt, summarize


ANSWERABLE = {"query": "q1", "expected": "answer", "keyFacts": [
    {"fact": "It panics.", "sources": ["issue:1:description"]},
    {"fact": "PR #2 returns NaN.", "sources": ["mr:2:description"]},
]}
UNANSWERABLE = {"query": "q2", "expected": "insufficient_evidence", "keyFacts": []}


def answer(*cited, query="q1"):
    return {"query": query, "answer": "text", "sources": [
        {"sourceId": source_id, "cited": True} for source_id in cited]}


class AnswerEvalTests(unittest.TestCase):
    def test_grade_scores_facts_and_citation_precision(self):
        verdict = {"facts": [{"index": 1, "verdict": "covered", "reason": ""},
                             {"index": 2, "verdict": "partial", "reason": ""}],
                   "unsupportedClaims": [], "declined": False}
        row = grade_row(ANSWERABLE, answer("mr:2:comment:5", "mr:9:description"), verdict)
        self.assertEqual(row["factRecall"], 0.75)
        self.assertEqual(row["citationPrecision"], 0.5)
        self.assertEqual(row["citedEntities"], ["mr:2", "mr:9"])

    def test_fact_the_judge_skipped_counts_as_missing(self):
        verdict = {"facts": [{"index": 1, "verdict": "covered", "reason": ""}],
                   "unsupportedClaims": [], "declined": False}
        row = grade_row(ANSWERABLE, answer(), verdict)
        self.assertEqual(row["facts"][1]["verdict"], "missing")
        self.assertEqual(row["factRecall"], 0.5)
        self.assertNotIn("citationPrecision", row)

    def test_summary_separates_declines_and_unsupported_claims(self):
        grades = [
            grade_row(ANSWERABLE, answer("issue:1:description"), {
                "facts": [{"index": 1, "verdict": "covered", "reason": ""},
                          {"index": 2, "verdict": "contradicted", "reason": ""}],
                "unsupportedClaims": ["PR #7 was merged"], "declined": False}),
            grade_row(UNANSWERABLE, answer(query="q2"), {"facts": [], "unsupportedClaims": [], "declined": True}),
        ]
        summary = summarize([ANSWERABLE, UNANSWERABLE], grades)
        self.assertEqual(summary["factRecall"], 0.5)
        self.assertEqual(summary["factVerdicts"]["contradicted"], 1)
        self.assertEqual(summary["answersWithUnsupportedClaims"], 1)
        self.assertEqual(summary["citationPrecision"], 1.0)
        self.assertEqual(summary["declinedWhenUnanswerable"], 1)

    def test_prompt_marks_unanswerable_queries(self):
        prompt = judge_prompt(UNANSWERABLE, "No evidence.", {})
        self.assertIn("none: the sources cannot answer", prompt)
        self.assertIn("No evidence.", prompt)


if __name__ == "__main__":
    unittest.main()
