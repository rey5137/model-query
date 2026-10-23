"""Tests for summarize.py: python3 -m unittest test_summarize (from this directory)."""

import os
import tempfile
import unittest

import summarize

HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = "vendor\thibernate\tpart\tquery\troute\tfork\titeration\tscore_ms\talloc_rate_norm\tstatus\n"


class StatisticsTest(unittest.TestCase):
    def test_t_quantile_matches_tables(self):
        self.assertAlmostEqual(summarize.t_quantile(0.999, 19), 3.579, places=3)
        self.assertAlmostEqual(summarize.t_quantile(0.999, 38), 3.319, places=2)
        self.assertAlmostEqual(summarize.t_quantile(0.975, 10), 2.228, places=3)

    def test_welch_brackets_the_difference(self):
        a = [1.0, 1.1, 0.9, 1.05, 0.95]
        b = [0.5, 0.6, 0.4, 0.55, 0.45]
        diff, lower, upper = summarize.welch(a, b)
        self.assertAlmostEqual(diff, 0.5)
        self.assertLess(lower, diff)
        self.assertGreater(upper, diff)
        self.assertGreater(lower, 0)

    def test_welch_is_antisymmetric(self):
        a, b = [1.0, 1.2, 1.1], [0.7, 0.9, 1.0]
        d1, l1, u1 = summarize.welch(a, b)
        d2, l2, u2 = summarize.welch(b, a)
        self.assertAlmostEqual(d1, -d2)
        self.assertAlmostEqual(l1, -u2)


class LoadingTest(unittest.TestCase):
    def test_rerun_fills_failed_cell_but_never_replaces_passing(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "performance-6.6.tsv"), "w") as f:
                f.write(HEADER)
                f.write("POSTGRESQL\t6.6\tA\ttoday\tRA.1\t1\t1\t1.0\t0\tPASS\n")
                f.write("POSTGRESQL\t6.6\tA\tone\tRA.0\t0\t0\tNaN\t0\tFAIL\n")
            with open(os.path.join(d, "performance-6.6-fill.tsv"), "w") as f:
                f.write(HEADER)
                f.write("POSTGRESQL\t6.6\tA\ttoday\tRA.1\t1\t1\t9.0\t0\tPASS\n")
                f.write("POSTGRESQL\t6.6\tA\tone\tRA.0\t1\t1\t0.5\t0\tPASS\n")
            cells, failed = summarize.load_timings(d, "6.6")
        self.assertEqual(cells[("A", "today", "RA.1")]["ms"], [1.0])
        self.assertEqual(cells[("A", "one", "RA.0")]["ms"], [0.5])
        self.assertEqual(failed, set())

    def test_unfilled_failure_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "performance-6.6.tsv"), "w") as f:
                f.write(HEADER)
                f.write("POSTGRESQL\t6.6\tA\ttoday\tRA.6\t0\t0\tNaN\t0\tFAIL\n")
            _, failed = summarize.load_timings(d, "6.6")
        self.assertEqual(failed, {("A", "today", "RA.6")})


class RulesTest(unittest.TestCase):
    def test_route_failing_ship_condition_cannot_defeat_rule_two(self):
        big = {q: (1.0, 0.5, 1.5) for q in summarize.READS}
        big["today"] = (-0.3, -0.5, -0.1)
        a = {"line": "x", "T": 0.3, "O": {"RA.1": (0.1, 0.0, 0.5)}, "savings": {"RA.5": big},
             "plan_cache_74": "FAIL", "B_upper": 0.01, "ra5_hits": "PASS", "cache_functional": True,
             "gating": (-0.01, -0.02, -0.005)}
        rule, name, _ = summarize.decide_part_a(dict(a, line="6.6"), dict(a, line="7.4"))
        self.assertEqual((rule, name), (2, "Neither"))

    def test_provider_only_when_it_saves_everywhere_and_build_is_cheap(self):
        good = {q: (0.5, 0.3, 0.7) for q in summarize.READS}
        a = {"line": "x", "T": 0.3, "O": {"RA.1": (1.0, 0.8, 1.2)}, "savings": {"RA.5": good},
             "plan_cache_74": "FAIL", "B_upper": 0.01, "ra5_hits": "PASS", "cache_functional": True,
             "gating": (-0.01, -0.02, -0.005)}
        rule, name, _ = summarize.decide_part_a(dict(a, line="6.6"), dict(a, line="7.4"))
        self.assertEqual((rule, name), (3, "Provider only"))

    def test_part_b_uses_rb4_as_rb6_base_when_pb1_fails(self):
        cells = {("B", "flat86k", r): {"ms": [v], "alloc": [0]} for r, v in
                 {"RB.0": 76, "RB.1": 93, "RB.2": 88, "RB.3": 94, "RB.4": 194, "RB.5": 194, "RB.6": 92}.items()}
        b = summarize.part_b(cells)
        self.assertAlmostEqual(b["gate"], 106 / 118)
        self.assertAlmostEqual(b["P-B.2"], (194 - 92) / 194)


class RecordedDataTest(unittest.TestCase):
    """The recorded spike data reproduce the verdicts in 0019-spike-results.md."""

    def test_recorded_verdicts(self):
        text = summarize.report(HERE)
        self.assertIn("**Rule 2: Neither.**", text)
        for line in ("6.6.58.Final", "7.4.11.Final"):
            cells, _ = summarize.load_timings(HERE, line)
            b = summarize.part_b(cells)
            self.assertGreaterEqual(b["gate"], 0.25)
            self.assertLess(b["P-B.1"], 0.10)
            self.assertGreaterEqual(b["P-B.2"], 0.05)
            self.assertLess(b["P-B.3"], 0.15)


if __name__ == "__main__":
    unittest.main()
