#!/usr/bin/env python3
"""Self-test for ac_audit.py, and the test AC-QA-01 names. Run: python3 .github/scripts/ac_audit_selftest.py

Each ac_qa_01_* function builds a throwaway spec/test tree in a temp directory, so no broken fixture lives in the repo.
"""
import json
import sys
import tempfile
from pathlib import Path

import ac_audit

SPEC = """# 99 — Fixture
**Owns:** `AC-AAA-*`.
| ID | Criterion |
|---|---|
| AC-AAA-01 | covered |
| AC-AAA-02 | not covered |
| AC-AAA-03 | (`Future`, M8) later |
"""
FUTURE_FILE = """# 98 — Fixture
**Owns:** `AC-BBB-*`. **Status: `Future` (M8, after 0.1.0).**
| ID | Criterion |
|---|---|
| AC-BBB-01 | no tag in the row |
"""
LATER = "| AC-CCC-01 | belongs to a milestone that has not started |\n"


def run(started, tests, extra=None, m0=None, code=None, catalogue=None):
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        spec = root / "docs" / "spec"
        spec.mkdir(parents=True)
        (spec / "aaa.md").write_text(SPEC, encoding="utf-8")
        (spec / "bbb.md").write_text(FUTURE_FILE, encoding="utf-8")
        (spec / "ccc.md").write_text("# c\n| ID | C |\n|---|---|\n" + LATER, encoding="utf-8")
        t = root / "m" / "src" / "test" / "java"
        t.mkdir(parents=True)
        (t / "T.java").write_text("class T {\n" + "".join("  void %s() {}\n" % n for n in tests) + "}\n")
        scope = {"started": started,
                 "milestones": {"M0": m0 or {"files": ["aaa"]}, "M1": {"files": ["ccc"]}, "M8": {"files": ["bbb"]}},
                 "build_proven": extra or {}}
        if code:
            main = root / "m" / "src" / "main" / "java"
            main.mkdir(parents=True)
            (main / "E.java").write_text('class E { String c = "%s"; }' % code)
        ref = spec / "reference"
        ref.mkdir()
        (ref / "90-errors.md").write_text(catalogue or "| `MQ1001` | x |\nranges: `MQ3001`\u2013`MQ3003`\n", encoding="utf-8")
        (root / "scope.json").write_text(json.dumps(scope), encoding="utf-8")
        return ac_audit.main(["--root", str(root), "--scope", str(root / "scope.json")])


def ac_qa_01_fails_on_an_in_scope_criterion_with_no_test():
    assert run(["M0"], ["ac_aaa_01_x"]) == 1


def ac_qa_01_passes_when_every_in_scope_criterion_has_a_test():
    assert run(["M0"], ["ac_aaa_01_x", "ac_aaa_02_y"]) == 0


def ac_qa_01_id_prefix_must_match_whole_number():
    assert run(["M0"], ["ac_aaa_01_x", "ac_aaa_021_y"]) == 1


def ac_qa_01_future_row_and_future_status_line_are_exempt_until_the_milestone_starts():
    # AC-AAA-03 (row tag) and AC-BBB-01 (file status line) are M8; AC-CCC-01 is M1: none is in scope for M0
    assert run(["M0"], ["ac_aaa_01_x", "ac_aaa_02_y"]) == 0
    # once M8 starts they are enforced
    assert run(["M0", "M8"], ["ac_aaa_01_x", "ac_aaa_02_y"]) == 1
    assert run(["M0", "M8"], ["ac_aaa_01_x", "ac_aaa_02_y", "ac_aaa_03_z", "ac_bbb_01_w"]) == 0


def ac_qa_01_unstarted_milestone_is_enforced_once_started():
    assert run(["M0", "M1"], ["ac_aaa_01_x", "ac_aaa_02_y"]) == 1


def ac_qa_01_build_proven_needs_no_test_but_must_exist():
    assert run(["M0"], ["ac_aaa_01_x"], {"AC-AAA-02": "proven by CI"}) == 0
    assert run(["M0"], ["ac_aaa_01_x", "ac_aaa_02_y"], {"AC-ZZZ-01": "typo"}) == 1


def ac_qa_01_unknown_criterion_in_milestone_ids_fails():
    assert run(["M0"], ["ac_aaa_01_x", "ac_aaa_02_y"], m0={"files": ["aaa"], "ids": ["AC-AAA-99"]}) == 1


def ac_qa_01_unknown_spec_file_in_milestone_files_fails():
    assert run(["M0"], ["ac_aaa_01_x", "ac_aaa_02_y"], m0={"files": ["aaa", "nope"]}) == 1


def ac_rel_07_code_defined_in_reference_90_passes():
    ok = ["ac_aaa_01_x", "ac_aaa_02_y"]
    assert run(["M0"], ok, code="MQ1001") == 0
    assert run(["M0"], ok, code="MQ3002") == 0  # inside a range


def ac_rel_07_code_missing_from_reference_90_fails():
    assert run(["M0"], ["ac_aaa_01_x", "ac_aaa_02_y"], code="MQ1999") == 1


if __name__ == "__main__":
    failed = 0
    for name, fn in sorted(globals().items()):
        if name.startswith(("ac_qa_01_", "ac_rel_07_")) and callable(fn):
            try:
                fn()
                print("ok   " + name)
            except AssertionError:
                failed += 1
                print("FAIL " + name)
    sys.exit(1 if failed else 0)
