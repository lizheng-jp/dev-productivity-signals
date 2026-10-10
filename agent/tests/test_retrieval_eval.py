import unittest

from app.evidence import EMBEDDING_DIMENSIONS
from app.retrieval_eval import (recall_at_k, recall_ceiling, reciprocal_rank, reembedded_points,
                                score_query, summarize)


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

    def test_ceiling_and_entity_recall_follow_per_entity_cap(self):
        relevant = ["mr:1:description", "mr:1:comment:2", "mr:1:comment:3", "issue:4:description"]
        self.assertEqual(recall_ceiling(set(relevant)), 0.75)
        result = score_query({"query": "q", "relevant": relevant},
                             search(("mr:1:comment:2", 0.8), ("mr:9:description", 0.7)))
        self.assertEqual(result["entityRecall@3"], 0.5)

    def test_reembedded_points_keep_payload_under_a_model_specific_id(self):
        payload = {"chunk_id": "p:main:mr:1:description:0", "content": "text",
                   "embedding_model": "BAAI/bge-base-en-v1.5"}
        vector = [3.0, 4.0] + [0.0] * (EMBEDDING_DIMENSIONS - 2)
        first, = reembedded_points([payload], [vector], "Qwen/Qwen3-Embedding-0.6B")
        second, = reembedded_points([payload], [vector], "other-model")
        self.assertEqual(first["payload"]["embedding_model"], "Qwen/Qwen3-Embedding-0.6B")
        self.assertEqual(first["payload"]["chunk_id"], payload["chunk_id"])
        self.assertEqual(payload["embedding_model"], "BAAI/bge-base-en-v1.5")
        self.assertNotEqual(first["id"], second["id"])
        self.assertAlmostEqual(first["vector"][0], 0.6)
        with self.assertRaises(ValueError):
            reembedded_points([payload], [], "m")

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
