import unittest

from app.retrieval_eval import recall_at_k, reciprocal_rank, score_query, summarize


def search(*hits, insufficient=False):
    return {"items": [{"sourceId": source_id, "score": score} for source_id, score in hits],
            "insufficientEvidence": insufficient, "coverageIncomplete": False}


class RetrievalEvalTests(unittest.TestCase):
    def test_recall_and_reciprocal_rank(self):
        retrieved = ["a", "b", "c", "d"]
        self.assertEqual(recall_at_k(retrieved, {"b", "x"}, 1), 0.0)
        self.assertEqual(recall_at_k(retrieved, {"b", "x"}, 3), 0.5)
        self.assertEqual(reciprocal_rank(retrieved, {"c", "d"}), 1 / 3)
        self.assertEqual(reciprocal_rank(retrieved, {"x"}), 0.0)

    def test_summary_separates_answerable_and_unanswerable_queries(self):
        results = [
            score_query({"query": "q1", "relevant": ["a"]}, search(("a", 0.8), ("b", 0.5))),
            score_query({"query": "q2", "relevant": ["c", "d"]}, search(("b", 0.6), ("c", 0.55))),
            score_query({"query": "q3", "relevant": []}, search(("b", 0.45))),
            score_query({"query": "q4", "relevant": []}, search(insufficient=True)),
        ]
        summary = summarize(results)
        self.assertEqual(summary["recall@1"], 0.5)
        self.assertEqual(summary["recall@3"], 0.75)
        self.assertEqual(summary["mrr"], 0.75)
        self.assertEqual(summary["confidentWhenUnanswerable"], 1)
        self.assertEqual(summary["flaggedInsufficientWhenAnswerable"], 0)
        self.assertEqual(summary["meanScoreRelevant"], 0.675)
        self.assertEqual(summary["meanScoreOther"], 0.5167)


if __name__ == "__main__":
    unittest.main()
