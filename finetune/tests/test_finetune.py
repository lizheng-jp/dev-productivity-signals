import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import experiments
import judge
import prepare_data as pd
from codereview_ft import metrics, runinfo
from codereview_ft.formatting import (INVALID, NO_ISSUES_COMMENT, build_system_prompt, build_target,
                                      build_user_prompt, parse_prediction)

LABELS = ["bug", "nitpick", "none", "style"]


def row(i, ctype="bug", negative=False, diff="@@ -1 +1 @@\n-a\n+b", repo=None, quality=0.9, comment=None):
    return {"diff_context": (diff + f"\n// {i}") if diff else "", "reviewer_comment": comment or f"This looks wrong because of {i}.",
            "comment_type": "none" if negative else ctype, "is_negative": negative, "quality_score": quality,
            "repo_name": repo or f"org/repo{i % 7}", "pr_number": i, "file_path": f"src/f{i}.py",
            "pr_title": "Fix thing", "language": "python", "comment_line": 3}


class FormattingTests(unittest.TestCase):
    def test_target_roundtrip_both_orders(self):
        for type_first in (True, False):
            parsed = parse_prediction(build_target("bug", "Off by one → fix.", type_first), LABELS)
            self.assertTrue(parsed.valid)
            self.assertEqual((parsed.type, parsed.comment), ("bug", "Off by one → fix."))

    def test_type_first_key_order(self):
        self.assertTrue(build_target("bug", "x").startswith('{"type"'))
        self.assertTrue(build_target("bug", "x", type_first=False).startswith('{"comment"'))

    def test_parse_handles_fences_and_prose(self):
        text = 'Sure!\n```json\n{"type": "Style", "comment": " rename "}\n```'
        parsed = parse_prediction(text, LABELS)
        self.assertEqual((parsed.valid, parsed.type, parsed.comment), (True, "style", "rename"))

    def test_parse_rejects_garbage_and_unknown_type(self):
        self.assertFalse(parse_prediction("no json here", LABELS).valid)
        bad_type = parse_prediction('{"type": "typo", "comment": "x"}', LABELS)
        self.assertFalse(bad_type.valid)
        self.assertIsNone(bad_type.type)
        self.assertFalse(parse_prediction('{"type": "bug"}', LABELS).valid)
        self.assertFalse(parse_prediction('{"type": "bug", "comment": 5}', LABELS).valid)

    def test_prompts_contain_labels_and_diff(self):
        self.assertIn('"nitpick"', build_system_prompt(LABELS))
        user = build_user_prompt(language="go", file_path="a.go", pr_title="t", diff="+x\n", before_code="old")
        self.assertIn("Code before the change:", user)
        self.assertTrue(user.rstrip().endswith("+x"))
        self.assertNotIn("Code before", build_user_prompt(language="go", file_path="a", pr_title="t", diff="+x"))


class MetricsTests(unittest.TestCase):
    def test_perfect_and_confusion(self):
        gold = ["bug", "none", "style", "bug"]
        self.assertEqual(metrics.macro_f1(gold, gold, LABELS), 1.0)
        pred = ["bug", "none", "bug", INVALID]
        self.assertAlmostEqual(metrics.accuracy(gold, pred), 0.5)
        matrix = metrics.confusion_matrix(gold, pred, LABELS)
        self.assertEqual(matrix["style"]["bug"], 1)
        self.assertEqual(matrix["bug"][INVALID], 1)

    def test_macro_f1_hand_computed(self):
        gold = ["bug", "bug", "style", "style"]
        pred = ["bug", "style", "style", "style"]
        # bug: P=1 R=.5 F1=2/3 ; style: P=2/3 R=1 F1=.8 ; nitpick/none unsupported -> ignored
        self.assertAlmostEqual(metrics.macro_f1(gold, pred, LABELS), (2 / 3 + 0.8) / 2)

    def test_flag_metrics_count_invalid_as_missed(self):
        gold = ["bug", "bug", "none", "none"]
        pred = ["bug", INVALID, "style", "none"]
        flag = metrics.flag_metrics(gold, pred)
        self.assertAlmostEqual(flag["precision"], 0.5)
        self.assertAlmostEqual(flag["recall"], 0.5)

    def test_rouge_l(self):
        self.assertEqual(metrics.rouge_l_f1("use a list here", "use a list here"), 1.0)
        self.assertEqual(metrics.rouge_l_f1("alpha beta", "gamma"), 0.0)
        self.assertEqual(metrics.rouge_l_f1("", "x"), 0.0)
        self.assertAlmostEqual(metrics.rouge_l_f1("a b c d", "a c d e"), 0.75)

    def test_bootstrap_ci_brackets_point_estimate(self):
        gold = ["bug"] * 30 + ["style"] * 30
        pred = ["bug"] * 25 + ["style"] * 5 + ["style"] * 28 + ["bug"] * 2
        point = metrics.macro_f1(gold, pred, LABELS)
        low, high = metrics.bootstrap_ci(
            lambda idx: metrics.macro_f1([gold[i] for i in idx], [pred[i] for i in idx], LABELS), len(gold), 400)
        self.assertLessEqual(low, point)
        self.assertGreaterEqual(high, point)

    def test_summarize_invalid_outputs_hurt(self):
        gold_t, gold_c = ["bug", "none"], ["null check missing", NO_ISSUES_COMMENT]
        good = metrics.summarize(gold_t, gold_c, ["bug", "none"], ["null check missing", ""], [True, True], LABELS, 50)
        bad = metrics.summarize(gold_t, gold_c, ["bug", "none"], ["null check missing", ""], [False, True], LABELS, 50)
        self.assertEqual(good["accuracy"], 1.0)
        self.assertEqual(good["rouge_l_on_positives"], 1.0)
        self.assertEqual(bad["accuracy"], 0.5)
        self.assertEqual(bad["valid_rate"], 0.5)

    def test_majority_baseline(self):
        self.assertEqual(metrics.majority_predictions(["a", "b", "b"], 2), ["b", "b"])


class PrepareDataTests(unittest.TestCase):
    def test_equal_quota_redistributes_from_small_classes(self):
        quota = pd.equal_quota({"a": 2, "b": 100, "c": 100}, 50)
        self.assertEqual(quota["a"], 2)
        self.assertEqual(sum(quota.values()), 50)
        self.assertLessEqual(abs(quota["b"] - quota["c"]), 1)
        self.assertEqual(pd.equal_quota({"a": 3}, 50), {"a": 3})

    def test_clean_row_rules(self):
        cfg = pd.BuildConfig()
        self.assertEqual(pd.clean_row(row(1, diff=""), cfg)[1], "empty_diff")
        self.assertEqual(pd.clean_row(row(1, quality=0.1), cfg)[1], "low_quality")
        self.assertEqual(pd.clean_row(row(1, comment="ok"), cfg)[1], "comment_length")
        self.assertEqual(pd.clean_row(row(1, ctype="none"), cfg)[1], "positive_without_type")
        negative, reason = pd.clean_row(row(1, negative=True, quality=0.0), cfg)  # negatives skip quality filter
        self.assertIsNone(reason)
        self.assertEqual((negative["type"], negative["comment"]), ("none", NO_ISSUES_COMMENT))
        inconsistent = row(1, negative=True)
        inconsistent["comment_type"] = "bug"
        self.assertEqual(pd.clean_row(inconsistent, cfg)[1], "inconsistent_negative")

    def test_after_code_and_repo_are_not_in_the_prompt(self):
        r = row(1)
        r["after_code"] = "SECRET_FIX"
        example = pd._to_example(pd.clean_row(r, pd.BuildConfig())[0], LABELS, pd.BuildConfig())
        prompt = example["messages"][1]["content"]
        self.assertNotIn("SECRET_FIX", prompt)
        self.assertNotIn(r["repo_name"], prompt)

    def test_audit_flags_empty_diff_shortcut(self):
        rows = [row(i) for i in range(20)] + [row(100 + i, negative=True, diff="") for i in range(20)]
        report = pd.audit({"train": rows})
        self.assertTrue(any("empty diff_context" in w for w in report["warnings"]))

    def test_audit_reports_repo_overlap(self):
        report = pd.audit({"train": [row(1, repo="a/x")], "test": [row(2, repo="a/x")]})
        self.assertEqual(report["overlap"]["train~test"]["shared_repos"], 1)

    def test_build_end_to_end(self):
        cfg = pd.BuildConfig(train_size=40, val_size=5, test_size=8, negative_share=0.25)
        types = ["bug", "style", "nitpick"]
        train = [row(i, ctype=types[i % 3], repo=f"train/r{i % 9}") for i in range(120)]
        train += [row(1000 + i, negative=True, repo=f"train/r{i % 9}") for i in range(40)]
        train.append(row(5000, repo="test/r0"))               # same repo as the test split: must be removed
        val = [row(2000 + i, ctype=types[i % 3], repo="val/r1") for i in range(10)]
        test = [row(3000 + i, ctype=types[i % 3], repo="test/r0") for i in range(12)]
        test += [row(3100 + i, negative=True, repo="test/r0") for i in range(4)]
        outputs, meta = pd.build({"train": train, "validation": val, "test": test}, cfg)

        self.assertEqual(len(outputs["train"]), 40)
        self.assertEqual(len(outputs["test"]), 8)
        self.assertEqual(meta["labels"], ["bug", "nitpick", "none", "style"])
        self.assertEqual(meta["class_counts"]["train"]["none"], 10)
        self.assertEqual(meta["dropped"]["train"]["eval_repo_or_pair_overlap"], 1)
        train_repos = {e["meta"]["repo"] for e in outputs["train"]}
        eval_repos = {e["meta"]["repo"] for n in ("validation", "test") for e in outputs[n]}
        self.assertFalse(train_repos & eval_repos)
        example = outputs["train"][0]
        self.assertEqual([m["role"] for m in example["messages"]], ["system", "user", "assistant"])
        self.assertEqual(json.loads(example["messages"][2]["content"])["type"], example["meta"]["type"])
        again, _ = pd.build({"train": train, "validation": val, "test": test}, cfg)
        self.assertEqual([e["id"] for e in again["train"]], [e["id"] for e in outputs["train"]])  # deterministic

    def test_build_warns_when_negatives_vanish(self):
        cfg = pd.BuildConfig(train_size=20, val_size=2, test_size=2)
        train = [row(i, repo=f"t/r{i}") for i in range(30)]
        train += [dict(row(900 + i, negative=True), diff_context="") for i in range(10)]
        _, meta = pd.build({"train": train, "validation": [row(2000, repo="v/r")], "test": [row(3000, repo="x/r")]}, cfg)
        self.assertTrue(meta["warnings"])
        self.assertEqual(meta["dropped"]["train"]["empty_diff"], 10)


class LengthShortcutTests(unittest.TestCase):
    def test_best_length_threshold_finds_perfect_split_and_chance(self):
        chars = [100, 200, 300, 1800, 1900, 2000]
        self.assertEqual(metrics.best_length_threshold(chars, [False] * 3 + [True] * 3)[::2], (300.0, 1.0))
        flipped = metrics.best_length_threshold(chars, [True] * 3 + [False] * 3)
        self.assertEqual((flipped[1], flipped[2]), ("shorter_is_negative", 1.0))
        self.assertAlmostEqual(metrics.best_length_threshold([5, 5, 5, 5], [True, False, True, False])[2], 0.5)

    def test_length_baseline_predictions(self):
        preds = metrics.length_baseline_predictions([100, 1900], 1000, "longer_is_negative", "suggestion")
        self.assertEqual(preds, ["suggestion", "none"])

    def test_length_matched_indices_equalises_classes_per_bin(self):
        chars = [100] * 6 + [1000] * 4 + [1000] * 4
        negative = [False] * 6 + [False] * 4 + [True] * 4   # 6 short positives, no short negatives
        idx = metrics.length_matched_indices(chars, negative)
        self.assertEqual(len(idx), 8)                         # only the 1000-char bin has both classes
        self.assertEqual(sum(negative[i] for i in idx), 4)
        self.assertTrue(all(chars[i] == 1000 for i in idx))

    def test_summarize_reports_length_matched_flag_accuracy(self):
        gold = ["bug", "bug", "none", "none"]
        chars = [1000, 1000, 1000, 1000]
        out = metrics.summarize(gold, ["c"] * 4, ["bug", "none", "none", "none"], ["c"] * 4, [True] * 4,
                                LABELS, 20, diff_chars=chars)
        self.assertEqual(out["flag_length_matched"]["n"], 4)
        self.assertAlmostEqual(out["flag_length_matched"]["balanced_accuracy"], 0.75)
        self.assertAlmostEqual(out["flag_balanced_accuracy"], 0.75)
        # only "bug" has support among type labels: P=1, R=.5 -> F1=2/3
        self.assertAlmostEqual(out["type_macro_f1_positives"], 2 / 3)

    def test_audit_detects_length_and_marker_shortcuts_and_samples(self):
        positives = [row(i, diff="@@ -1 +1 @@\n-a\n+b") for i in range(30)]
        negatives = [row(100 + i, negative=True, diff="plain code\n" * 120) for i in range(30)]
        report = pd.audit({"train": positives + negatives})
        info = report["splits"]["train"]
        self.assertEqual(info["length_only_flag_balanced_accuracy"]["balanced_accuracy"], 1.0)
        self.assertEqual(info["hunk_header_rate_positive"], 1.0)
        self.assertEqual(info["hunk_header_rate_negative"], 0.0)
        self.assertTrue(any("balanced accuracy" in w for w in report["warnings"]))
        self.assertTrue(any("hunk_header_rate" in w for w in report["warnings"]))
        dump = pd.sample_dump(positives + negatives, per_class=2)
        self.assertIn("NEGATIVE", dump)
        self.assertIn("POSITIVE", dump)

    def test_build_length_matches_negatives_and_keeps_diff_chars(self):
        cfg = pd.BuildConfig(train_size=60, val_size=5, test_size=0, negative_share=0.3)
        short, long_ = "x" * 100, "y" * 1500
        train = [row(i, ctype="bug", diff=short, repo=f"t/r{i}") for i in range(80)]
        train += [row(200 + i, ctype="style", diff=long_, repo=f"t/r{i}") for i in range(80)]
        train += [row(400 + i, negative=True, diff=long_, repo=f"t/n{i}") for i in range(100)]
        train += [row(600 + i, negative=True, diff=short, repo=f"t/m{i}") for i in range(2)]
        val = [row(900 + i, repo="v/r") for i in range(6)]
        test = [row(950 + i, repo="x/r") for i in range(6)]
        outputs, meta = pd.build({"train": train, "validation": val, "test": test}, cfg)
        negatives = [e for e in outputs["train"] if e["meta"]["type"] == "none"]
        short_negatives = [e for e in negatives if e["meta"]["diff_chars"] < 300]
        self.assertEqual(len(short_negatives), 2)               # only 2 short negatives exist, all used
        self.assertLess(meta["train_negative_share_achieved"], 0.3)
        self.assertTrue(all("diff_chars" in e["meta"] for e in outputs["test"]))
        self.assertEqual(len(outputs["test"]), 6)                # test_size=0 keeps everything


class TruncationTests(unittest.TestCase):
    def test_last_hunk_incomplete(self):
        complete = "@@ -1,2 +1,2 @@\n ctx\n-a\n+b"
        cut = "@@ -1,5 +1,5 @@\n ctx\n-a\n+b"
        self.assertFalse(pd.last_hunk_incomplete(complete))
        self.assertTrue(pd.last_hunk_incomplete(cut))
        self.assertIsNone(pd.last_hunk_incomplete("no header"))
        two = "@@ -1,1 +1,1 @@\n-a\n+b\n@@ -9,3 +9,3 @@\n ctx"
        self.assertTrue(pd.last_hunk_incomplete(two))   # only the last hunk is judged

    def test_rows_at_the_cap_are_dropped(self):
        cfg = pd.BuildConfig()
        at_cap = row(1, diff="x" * 2000)
        at_cap["diff_context"] = "x" * 2000
        self.assertEqual(pd.clean_row(at_cap, cfg)[1], "diff_at_or_over_cap")
        under = row(2)
        under["diff_context"] = "x" * 1999
        self.assertIsNone(pd.clean_row(under, cfg)[1])

    def test_audit_flags_cap_spike_on_one_class(self):
        positives = [row(i, diff="@@ -1 +1 @@\n-a\n+b" + "p" * i) for i in range(30)]
        negatives = [row(100 + i, negative=True) for i in range(30)]
        for r in negatives:
            r["diff_context"] = "@@ -1,50 +1,50 @@\n" + "x" * 1982   # exactly 2000 chars
        report = pd.audit({"train": positives + negatives})
        info = report["splits"]["train"]
        self.assertEqual(info["modal_diff_length_negative"], {"chars": 2000, "share": 1.0})
        self.assertTrue(any("hard truncation cap" in w for w in report["warnings"]))


def fake_result(name, macro, ci, adapter=None, split_sha="aaa111"):
    return {"name": name, "adapter": adapter, "n": 100, "valid_rate": 0.97, "macro_f1": macro,
            "macro_f1_ci95": ci, "type_macro_f1_positives": 0.4, "flag_length_matched": {"balanced_accuracy": 0.6},
            "split_sha": split_sha, "limit": None}


class ExperimentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.tmp, ignore_errors=True))
        (self.tmp / "data").mkdir()
        (self.tmp / "data" / "meta.json").write_text(json.dumps({"labels": ["bug", "none"]}))

    def make_run(self, exp, tuned_macro, tuned_ci, split_sha="aaa111"):
        results = self.tmp / "results" / exp
        results.mkdir(parents=True)
        (results / "base.json").write_text(json.dumps(fake_result("base", 0.10, [0.08, 0.12], None, split_sha)))
        (results / "tuned.json").write_text(json.dumps(fake_result("tuned", tuned_macro, tuned_ci, "runs/x/adapter",
                                                                   split_sha)))
        (results / "tuned.predictions.jsonl").write_text("{}\n")
        train = self.tmp / "runs" / exp
        train.mkdir(parents=True)
        (train / "train_meta.json").write_text(json.dumps({
            "args": {"base_model": "m", "lora_r": 16, "lr": 2e-4, "epochs": 2, "seed": 0},
            "train_examples": 1000, "gpu": "A40", "train_metrics": {"train_runtime": 1200},
            "final_eval": {"eval_loss": 0.9}}))
        return experiments.record(self.tmp / "experiments", exp, results_dir=results, train_dir=train,
                                  data_dir=self.tmp / "data", hypothesis="h", change=f"change {exp}",
                                  parent=None if exp == "exp01" else "exp01", with_predictions=False)

    def test_record_writes_files_and_headline_is_the_tuned_run(self):
        dest = self.make_run("exp01", 0.30, [0.27, 0.33])
        record = json.loads((dest / "experiment.json").read_text())
        self.assertEqual(record["headline"], "tuned")
        for name in ("train_meta.json", "data_meta.json", "NOTES.md", "results/base.json", "results/tuned.json"):
            self.assertTrue((dest / name).exists(), name)
        self.assertFalse((dest / "results" / "tuned.predictions.jsonl").exists())  # off unless requested
        self.assertIn("training time: 20 min", (dest / "NOTES.md").read_text())

    def test_index_compares_to_parent_and_flags_noise_and_data_mismatch(self):
        self.make_run("exp01", 0.30, [0.27, 0.33])
        self.make_run("exp02", 0.31, [0.28, 0.34])                      # overlapping CI: not a real gain
        self.make_run("exp03", 0.45, [0.42, 0.48], split_sha="bbb222")  # separate CI but different eval data
        index = (self.tmp / "experiments" / "INDEX.md").read_text()
        row = lambda exp: next(line for line in index.splitlines() if line.startswith(f"| {exp} "))
        self.assertIn("+0.010 (CIs overlap)", row("exp02"))
        self.assertIn("CIs separate", row("exp03"))
        self.assertIn("different eval data", row("exp03"))
        self.assertIn("| - |", row("exp01"))   # first run has no parent to compare with

    def test_record_refuses_to_overwrite(self):
        self.make_run("exp01", 0.30, [0.27, 0.33])
        with self.assertRaises(FileExistsError):
            experiments.record(self.tmp / "experiments", "exp01", results_dir=self.tmp / "results" / "exp01",
                               train_dir=None, data_dir=self.tmp / "data", hypothesis="h", change="c", parent=None)

    def test_format_progress(self):
        from codereview_ft.runinfo import format_progress
        line = format_progress(100, 400, 600.0, {"loss": 1.23456, "learning_rate": 2e-4, "epoch": 0.5})
        self.assertIn("step 100/400 (25%)", line)
        self.assertIn("elapsed 10.0 min, ETA 30 min", line)
        self.assertIn("loss=1.235", line)
        self.assertNotIn("epoch", line)
        self.assertIn("ETA 0 min", format_progress(0, 10, 0.0, {}))

    def test_runinfo(self):
        path = self.tmp / "f.txt"
        path.write_text("abc")
        self.assertEqual(runinfo.file_sha(path), runinfo.file_sha(path))
        self.assertEqual(len(runinfo.file_sha(path)), 12)
        self.assertIsNone(runinfo.file_sha(self.tmp / "missing"))
        self.assertEqual(set(runinfo.git_info(self.tmp)), {"commit", "dirty"})


class JudgeTests(unittest.TestCase):
    def test_parse_verdict(self):
        self.assertEqual(judge.parse_verdict('{"winner": "A", "reason": "x"}'), "A")
        self.assertEqual(judge.parse_verdict('text {"winner": "tie", "reason": ""} more'), "tie")
        self.assertIsNone(judge.parse_verdict('{"winner": "C"}'))
        self.assertIsNone(judge.parse_verdict("nothing"))

    def test_aggregate(self):
        summary = judge.aggregate(["first", "second", "second", "tie"])
        self.assertEqual((summary["a_wins"], summary["b_wins"], summary["ties"]), (1, 2, 1))
        self.assertAlmostEqual(summary["b_win_rate_excluding_ties"], 2 / 3)
        self.assertIsNone(judge.aggregate(["tie"])["b_win_rate_excluding_ties"])

    def test_prompt_contains_all_parts(self):
        prompt = judge.build_prompt("DIFF", "REF", "cand-a", "")
        for part in ("DIFF", "REF", "cand-a", "(empty)"):
            self.assertIn(part, prompt)


if __name__ == "__main__":
    unittest.main()
