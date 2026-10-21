"""Offline regression tests for the spike's evidence reduction."""

import contextlib
import csv
import gzip
import importlib.util
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

DIRECTORY = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("summarize", DIRECTORY / "summarize.py")
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)


class SummaryTest(unittest.TestCase):
    def timing_fixture(self):
        rows = []
        for route, measured in (("INLINE_COUNT", [5, 7, 9, 11, 100]), ("COUNT", [10, 12, 18, 20, 200])):
            for phase, values in (("warmup", [1_000_000, 2_000_000]), ("timed", measured)):
                for run, nanos in enumerate(values, 1):
                    rows.append(dict(vendor="MYSQL", hibernate=SUMMARY.LINES[0], shape="flat", filter="selective",
                                     page="first", route=route, phase=phase, run=str(run), nanos=str(nanos),
                                     status="PASS", total="10000", ids="[1, 2]"))
        return rows

    def test_measured_medians_and_ratio_exclude_warmups_and_resist_outliers(self):
        result, = SUMMARY.timing_rows(self.timing_fixture())
        self.assertEqual((result["inline_median_ns"], result["count_median_ns"]), (9, 18))
        self.assertEqual((result["inline_min_ns"], result["inline_max_ns"]), (5, 100))
        self.assertEqual((result["count_min_ns"], result["count_max_ns"]), (10, 200))
        self.assertEqual((result["inline_min_ms"], result["inline_max_ms"]), ("0.000005", "0.000100"))
        self.assertEqual((result["count_min_ms"], result["count_max_ms"]), ("0.000010", "0.000200"))
        self.assertEqual(result["inline_over_count"], "0.500000")
        self.assertEqual(result["measured_runs_per_route"], 5)
        self.assertEqual(result["excluded_warmups_per_route"], 2)

    def test_incomplete_or_duplicate_run_is_refused(self):
        rows = self.timing_fixture()
        with self.assertRaisesRegex(ValueError, "Incomplete/duplicate"):
            SUMMARY.timing_rows(rows[:-1])
        rows[-1]["run"] = "4"
        with self.assertRaisesRegex(ValueError, "Incomplete/duplicate"):
            SUMMARY.timing_rows(rows)

    def test_failed_measurement_or_changed_row_set_is_refused(self):
        rows = self.timing_fixture()
        rows[-1]["status"] = "FAIL"
        with self.assertRaisesRegex(ValueError, "failed/nonpositive"):
            SUMMARY.timing_rows(rows)
        rows[-1]["status"] = "PASS"
        rows[-1]["ids"] = "[3]"
        with self.assertRaisesRegex(ValueError, "disagree on row ids"):
            SUMMARY.timing_rows(rows)

    def test_parity_distinguishes_status_observation_sql_and_missing_cells(self):
        left = [dict(case="same", status="PASS", value="ids=[1]", sql="select 1")]
        right = [dict(case="same", status="PASS", value="ids=[2]", sql="select 2"),
                 dict(case="extra", status="FAIL", value="error", sql="select 3")]
        missing, changed = SUMMARY.parity_rows("functional", left, right, ("case",), ("value",))
        self.assertEqual((missing["status_6_6"], missing["status_equal"]), ("MISSING", "false"))
        self.assertEqual((changed["status_equal"], changed["observation_equal"], changed["sql_equal"]),
                         ("true", "false", "false"))
        with self.assertRaisesRegex(ValueError, "Duplicate cell"):
            SUMMARY.parity_rows("functional", left + left, right, ("case",), ("value",))

    def test_mysql_root_plan_elapsed_is_separate_from_child_times(self):
        strategy, elapsed = SUMMARY.plan_details(dict(vendor="MYSQL", plan='{} EXPLAIN ANALYZE: '
                                                     '-> Nested loop semijoin  (cost=1) '
                                                     '(actual time=0.018..23.323 rows=10000 loops=1) '
                                                     '-> Index scan (actual time=0.001..1.128 rows=1 loops=1)'))
        self.assertEqual((strategy, elapsed), ("Nested loop semijoin", 23.323))

    def test_postgresql_plan_keeps_join_strategy_and_execution_time(self):
        strategy, elapsed = SUMMARY.plan_details(dict(vendor="POSTGRESQL", plan='[{"Plan": '
                                                     '{"Node Type": "Merge Join", "Join Type": "Semi", '
                                                     '"Plans": [{"Node Type": "Index Only Scan"}]}, '
                                                     '"Execution Time": 5.095}]'))
        self.assertEqual(strategy, "Merge Join/Semi > Index Only Scan")
        self.assertEqual(elapsed, 5.095)

    def test_gzip_input_and_ambiguous_duplicate_are_checked(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "input.tsv"
            with gzip.open(str(path) + ".gz", "wt") as stream:
                stream.write("status\tvalue\nPASS\tbound\n")
            self.assertEqual(SUMMARY.read_rows(path), [dict(status="PASS", value="bound")])
            path.write_text("status\tvalue\nPASS\tstale\n")
            with self.assertRaisesRegex(ValueError, "Ambiguous raw inputs"):
                SUMMARY.read_rows(path)

    def test_preserved_dataset_regenerates_identically_on_duplicate_request(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            for pattern in ("rfc0018-*", "performance-*.tsv*", "plans-*.tsv*", "tck-*", "value-path-observations.tsv"):
                for source in DIRECTORY.glob(pattern):
                    shutil.copyfile(source, target / source.name)
            with contextlib.redirect_stdout(io.StringIO()):
                SUMMARY.main(target)
                generated = {path.name: path.read_bytes() for path in target.glob("*.tsv")
                             if path.name not in {"value-path-observations.tsv"}
                             and not path.name.startswith(("rfc0018-", "performance-6", "performance-7",
                                                          "plans-", "tck-test-results-"))}
                SUMMARY.main(target)
            self.assertEqual(len(generated), 7)
            for name, contents in generated.items():
                self.assertEqual((target / name).read_bytes(), contents, name)
            with (target / "performance-medians.tsv").open() as stream:
                rows = list(csv.DictReader(stream, delimiter="\t"))
            first = [row for row in rows if row["shape"] == "flat" and row["filter"] == "selective"
                     and row["page"] == "first" and row["vendor"] == "MYSQL"]
            self.assertEqual(len(first), 2)
            self.assertTrue(all(float(row["inline_over_count"]) > 1 for row in first))
            self.assertEqual([row["inline_faster"] for row in first], ["false", "false"])
            with (target / "line-parity.tsv").open() as stream:
                parity = list(csv.DictReader(stream, delimiter="\t"))
            self.assertEqual(len(parity), 3068)
            self.assertTrue(all(row["status_equal"] == "true" for row in parity))


if __name__ == "__main__":
    unittest.main()
