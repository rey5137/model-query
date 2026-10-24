import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("summarize.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def row(vendor, probe, case, status="PASS", value="", sql="select 1"):
    return dict(vendor=vendor, probe=probe, case=case, status=status, value=value, sql=sql)


class SummaryTest(unittest.TestCase):
    def setUp(self):
        self.saved = [row(v, "B.2", case) for v in summary.VENDORS for case in ("CONTENT", "COUNT", "STREAM")]

    def test_iteration_skip_reuses_saved_measurements(self):
        current = [row(v, "B.2", "timings-and-plans", "NOT_RUN", "iteration: -Drfc0022.plans=false")
                   for v in summary.VENDORS]
        self.assertEqual(summary.unique(self.saved), summary.unique(summary.merge_costs(current, self.saved)))

    def test_never_hides_new_failure(self):
        failure = row("H2", "B.2", "COUNT", "FAIL", "results differ")
        self.assertIn(failure, summary.merge_costs([failure], self.saved))

    def test_duplicate_request_key_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate acceptance key"):
            summary.unique([self.saved[0], self.saved[0]])

    def test_missing_saved_cell_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "missing saved B.2"):
            summary.merge_costs([], self.saved[:-1])

    def test_parity_normalizes_whitespace_but_keeps_sql_changes(self):
        a = row("H2", "C.1", "bare", sql="select  ?\nfrom invoice")
        b = dict(a, sql="select ? from invoice")
        self.assertFalse(summary.parity_data([a], [b])[3]["sql"])
        b["sql"] = "select cast(? as bigint) from invoice"
        self.assertEqual(summary.parity_data([a], [b])[3]["sql"], [summary.key(a)])

    def test_parity_normalizes_only_the_source_alias(self):
        a = row("H2", "C.1", "source-body", sql="select cte01_0.bodyValue from cte0 cte01_0 || 8")
        b = dict(a, sql="select var_1_11_0.bodyValue from cte0 var_1_11_0 || 8")
        differences = summary.parity_data([a], [b])[3]
        self.assertEqual(differences["sql"], [summary.key(a)])
        self.assertFalse(differences["sql, source alias normalized"])
        b["sql"] = "select var_1_11_0.bodyValue from cte0 var_1_11_0 || 7"
        self.assertEqual(summary.parity_data([a], [b])[3]["sql, source alias normalized"], [summary.key(a)])

    def test_grid_counts_each_status_and_totals(self):
        rows = [row("H2", "C.1", "a"), row("H2", "C.1", "b", "FAIL"), row("H2", "D.1", "c", "NOT_RUN")]
        header, table = summary.grid_rows([("7.4", rows)])
        self.assertEqual(header, ["H2 7.4", "MySQL 7.4", "PG 7.4"])
        self.assertEqual(table, [("C.1", ["1/1/0", "-", "-"]), ("D.1", ["0/0/1", "-", "-"]),
                                 ("total", ["1/1/1", "-", "-"])])

    def test_unknown_status_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "unknown acceptance status"):
            summary.unique([row("H2", "C.1", "bare", "MAYBE")])


if __name__ == "__main__":
    unittest.main()
